package com.yii2storm.modelmagic.resolver

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.PhpUseList
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.intellij.openapi.diagnostic.Logger

/**
 * Resolves Yii2 model magic properties from various sources.
 * Uses PsiModificationTracker-based caching for performance.
 */
class MagicPropertyResolver {

    companion object {
        private val LOG = Logger.getInstance(MagicPropertyResolver::class.java)

        private val INSTANCE = MagicPropertyResolver()

        private val YII_MODEL_CLASSES = setOf(
            "\\yii\\db\\ActiveRecord",
            "\\yii\\base\\Model",
            "\\yii\\db\\BaseActiveRecord"
        )

        // Single regex that captures all @property variants
        private val PHP_DOC_PROPERTY_REGEX = Regex(
            "@property(?:-read|-write)?\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)"
        )

        private val PHP_DOC_PROPERTY_READ_REGEX = Regex(
            "@property-read\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)"
        )

        private val PHP_DOC_PROPERTY_WRITE_REGEX = Regex(
            "@property-write\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)"
        )

        private const val GETTER_PREFIX = "get"
        private const val SETTER_PREFIX = "set"

        fun getInstance(project: Project): MagicPropertyResolver = INSTANCE
    }

    /**
     * Get all magic properties for a model class with caching.
     * Priority order (lowest to highest):
     * FIELD < PHPDOC < GETTER < SETTER < RELATION < ATTRIBUTE
     */
    fun getModelProperties(phpClass: PhpClass): List<MagicProperty> {
        if (!isModelClass(phpClass)) {
            return emptyList()
        }

        return CachedValuesManager.getCachedValue(phpClass) {
            val result = computeModelProperties(phpClass)
            // Cache depends on the file containing the class
            CachedValueProvider.Result.create(
                result, 
                phpClass.containingFile
            )
        }
    }

    fun getPropertyType(phpClass: PhpClass, propertyName: String): PhpType? {
        return findProperty(phpClass, propertyName)?.type
    }

    fun resolveProperty(phpClass: PhpClass, propertyName: String): PsiElement? {
        return findProperty(phpClass, propertyName)?.source
    }

    fun hasProperty(phpClass: PhpClass, propertyName: String): Boolean {
        return findProperty(phpClass, propertyName) != null
    }

    /**
     * Check if a class extends yii model classes.
     * Walks up the inheritance chain including traits.
     */
    fun isModelClass(phpClass: PhpClass): Boolean {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<PhpClass>()
        queue.add(phpClass)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.fqn in YII_MODEL_CLASSES) {
                return true
            }
            
            if (!visited.add(current.fqn)) continue

            // Check super class
            current.superClass?.let { queue.add(it) }

            // Check traits
            getTraits(current).forEach { trait ->
                if (trait.fqn !in visited) {
                    queue.add(trait)
                }
            }
        }
        return false
    }

    // --- Internal computation (no caching) ---

    private fun computeModelProperties(phpClass: PhpClass): List<MagicProperty> {
        LOG.debug("Computing magic properties for ${phpClass.fqn}")
        
        val properties = linkedMapOf<String, MagicProperty>()
        val hierarchy = collectClassHierarchy(phpClass)

        hierarchy.forEach { currentClass ->
            collectFrom(currentClass, properties)
        }

        return properties.values.toList()
    }

    private fun collectFrom(
        phpClass: PhpClass,
        properties: MutableMap<String, MagicProperty>
    ) {
        // Lower-priority sources first (putIfAbsent = first wins)
        getPublicProperties(phpClass).forEach { properties.putIfAbsent(it.name, it) }
        getPhpDocProperties(phpClass).forEach { properties.putIfAbsent(it.name, it) }

        // Getters - only public methods that are NOT relations
        getGetterProperties(phpClass).forEach { properties.putIfAbsent(it.name, it) }

        // Setters override getters (if both exist for same property)
        getSetterProperties(phpClass).forEach { properties[it.name] = it }

        // Higher-priority sources override (direct put)
        getRelationProperties(phpClass).forEach { properties[it.name] = it }
        getAttributesMethodProperties(phpClass).forEach { properties.putIfAbsent(it.name, it) }
    }

    private fun findProperty(phpClass: PhpClass, propertyName: String): MagicProperty? {
        return getModelProperties(phpClass).firstOrNull { it.name == propertyName }
    }

    /**
     * Collect class hierarchy including traits.
     */
    private fun collectClassHierarchy(phpClass: PhpClass): List<PhpClass> {
        val result = mutableListOf<PhpClass>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<PhpClass>()
        queue.add(phpClass)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.fqn)) continue

            result.add(current)

            // Add super class
            current.superClass?.let { queue.add(it) }

            // Add traits
            getTraits(current).forEach { trait ->
                if (trait.fqn !in visited) {
                    queue.add(trait)
                }
            }
        }
        return result
    }

    /**
     * Get traits used by a class.
     */
    private fun getTraits(phpClass: PhpClass): List<PhpClass> {
        return phpClass.children
            .filterIsInstance<PhpUseList>()
            .flatMap { traitUse ->
                traitUse.children.filterIsInstance<PhpReference>().mapNotNull { ref ->
                    ref.resolve() as? PhpClass
                }
            }
    }

    // --- Property source extractors ---

    private fun getPublicProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.fields
            .filter { field -> isPublic(field) }
            .map { field ->
                MagicProperty(
                    name = field.name,
                    type = field.type,
                    kind = PropertyKind.FIELD,
                    source = field
                )
            }
    }

    private fun getPhpDocProperties(phpClass: PhpClass): List<MagicProperty> {
        val docComment = phpClass.docComment ?: return emptyList()
        val docText = docComment.text

        return buildList {
            // Regular @property declarations (excluding -read/-write)
            Regex("@property\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)").findAll(docText).forEach { match ->
                val rawType = match.groupValues[1].trim()
                val propertyName = match.groupValues[2].trim()
                if (propertyName.isNotBlank()) {
                    add(
                        MagicProperty(
                            name = propertyName,
                            type = buildPhpType(rawType),
                            kind = PropertyKind.PHPDOC,
                            source = docComment
                        )
                    )
                }
            }

            // @property-read declarations (read-only)
            PHP_DOC_PROPERTY_READ_REGEX.findAll(docText).forEach { match ->
                val rawType = match.groupValues[1].trim()
                val propertyName = match.groupValues[2].trim()
                if (propertyName.isNotBlank()) {
                    add(
                        MagicProperty(
                            name = propertyName,
                            type = buildPhpType(rawType),
                            kind = PropertyKind.PHPDOC_READ,
                            source = docComment
                        )
                    )
                }
            }

            // @property-write declarations (write-only)
            PHP_DOC_PROPERTY_WRITE_REGEX.findAll(docText).forEach { match ->
                val rawType = match.groupValues[1].trim()
                val propertyName = match.groupValues[2].trim()
                if (propertyName.isNotBlank()) {
                    add(
                        MagicProperty(
                            name = propertyName,
                            type = buildPhpType(rawType),
                            kind = PropertyKind.PHPDOC_WRITE,
                            source = docComment
                        )
                    )
                }
            }
        }
    }

    /**
     * Find all public getX() methods that are NOT relation methods.
     */
    private fun getGetterProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(GETTER_PREFIX) &&
                    method.name.length > GETTER_PREFIX.length &&
                    !isRelationMethod(method)
            }
            .map { method ->
                MagicProperty(
                    name = getterToPropertyName(method.name),
                    type = method.type,
                    kind = PropertyKind.GETTER,
                    source = method
                )
            }
    }

    /**
     * Find all public setX() methods.
     */
    private fun getSetterProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.name.startsWith(SETTER_PREFIX) &&
                    method.name.length > SETTER_PREFIX.length &&
                    method.parameters.isNotEmpty() &&
                    !isRelationMethod(method)
            }
            .map { method ->
                MagicProperty(
                    name = setterToPropertyName(method.name),
                    type = getSetterParameterType(method),
                    kind = PropertyKind.SETTER,
                    source = method
                )
            }
    }

    /**
     * Find all public getX() methods that contain hasOne/hasMany calls.
     */
    private fun getRelationProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(GETTER_PREFIX) &&
                    method.name.length > GETTER_PREFIX.length &&
                    isRelationMethod(method)
            }
            .map { method ->
                MagicProperty(
                    name = getterToPropertyName(method.name),
                    type = extractRelationType(method),
                    kind = PropertyKind.RELATION,
                    source = method
                )
            }
    }

    /**
     * Parse the attributes() method to find additional property names.
     * Uses PSI tree walking instead of regex.
     */
    private fun getAttributesMethodProperties(phpClass: PhpClass): List<MagicProperty> {
        val attributesMethod = phpClass.methods.firstOrNull { method ->
            method.name == "attributes" && method.parameters.isEmpty()
        } ?: return emptyList()

        // Use PSI tree to find string literals in the return array
        val propertyNames = mutableListOf<String>()
        attributesMethod.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                // Match string literals like 'column_name' or "column_name"
                val text = element.text
                if ((text.startsWith("'") && text.endsWith("'") && text.length > 2) ||
                    (text.startsWith("\"") && text.endsWith("\"") && text.length > 2)) {
                    val name = text.substring(1, text.length - 1)
                    if (name.isNotBlank()) {
                        propertyNames.add(name)
                    }
                }
            }
        })

        return propertyNames.distinct().map { propertyName ->
            MagicProperty(
                name = propertyName,
                type = null,
                kind = PropertyKind.ATTRIBUTE,
                source = attributesMethod
            )
        }
    }

    // --- Relation detection ---

    /**
     * Detect if a method is a Yii2 relation using PSI tree.
     */
    private fun isRelationMethod(method: Method): Boolean {
        return method.children.any { child ->
            isHasOneOrHasManyCall(child)
        }
    }

    /**
     * Recursively check if element contains hasOne/hasMany call.
     */
    private fun isHasOneOrHasManyCall(element: PsiElement): Boolean {
        if (element is PhpReference) {
            val name = element.name
            if (name == "hasOne" || name == "hasMany") {
                return true
            }
        }
        return element.children.any { isHasOneOrHasManyCall(it) }
    }

    /**
     * Extract the related model class FQN from hasOne/hasMany call.
     */
    private fun extractRelationType(method: Method): PhpType? {
        // Try to find first argument of hasOne/hasMany
        val relationCall = findRelationCall(method) ?: return method.type

        // Find the first parameter using visitor
        val visitor = FirstParameterVisitor()
        method.accept(visitor)
        val firstArg = visitor.firstParam ?: return method.type

        // Try ClassName::class pattern
        if (firstArg is PhpReference && firstArg.text.endsWith("::class")) {
            val className = firstArg.text.removeSuffix("::class").trim()
            val fqn = normalizeFqn(className)
            if (fqn != null) return buildPhpTypeFromFqn(fqn)
        }

        // Try string literal 'app\models\Model' or "app\models\Model"
        val text = firstArg.text.trim()
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            val className = text.substring(1, text.length - 1)
            val fqn = normalizeFqn(className)
            if (fqn != null) return buildPhpTypeFromFqn(fqn)
        }

        // Fallback: use method return type
        return method.type
    }

    /**
     * Find hasOne/hasMany call in method body.
     */
    private fun findRelationCall(method: Method): PhpReference? {
        method.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            var result: PhpReference? = null

            override fun visitElement(element: PsiElement) {
                if (result != null) return
                if (element is PhpReference) {
                    val name = element.name
                    if (name == "hasOne" || name == "hasMany") {
                        result = element
                        return
                    }
                }
                super.visitElement(element)
            }
        })
        // The visitor pattern above doesn't work well, use alternative
        val visitor = RelationCallVisitor()
        method.accept(visitor)
        return visitor.found
    }

    private class RelationCallVisitor : com.intellij.psi.PsiRecursiveElementVisitor() {
        var found: PhpReference? = null

        override fun visitElement(element: PsiElement) {
            if (found != null) return
            if (element is PhpReference) {
                val name = element.name
                if (name == "hasOne" || name == "hasMany") {
                    found = element
                    return
                }
            }
            super.visitElement(element)
        }
    }

    /**
     * Extract parameter type from setter method.
     */
    private fun getSetterParameterType(method: Method): PhpType? {
        val firstParam = method.parameters.firstOrNull() ?: return null

        // Try to get type hint from parameter
        val typeHint = firstParam.declaredType
        if (!typeHint.isEmpty) {
            return typeHint
        }

        // Try to extract from @param in doc comment
        val docComment = method.docComment?.text ?: return null
        val paramPattern = Regex("@param\\s+([^\\s]+)")
        paramPattern.find(docComment)?.let { match ->
            return buildPhpType(match.groupValues[1].trim())
        }

        return null
    }

    // --- Helpers ---

    /**
     * Convert getter method name to property name.
     * getDirection_id -> direction_id
     * getFullName -> fullName
     */
    private fun getterToPropertyName(getterName: String): String {
        val base = getterName.removePrefix(GETTER_PREFIX)
        if (base.isEmpty()) return getterName
        return base.replaceFirstChar { it.lowercaseChar() }
    }

    /**
     * Convert setter method name to property name.
     */
    private fun setterToPropertyName(setterName: String): String {
        val base = setterName.removePrefix(SETTER_PREFIX)
        if (base.isEmpty()) return setterName
        return base.replaceFirstChar { it.lowercaseChar() }
    }

    /**
     * Convert property name to getter method name.
     * direction_id -> getDirection_id
     * fullName -> getFullName
     * user_id -> getUserId (converts snake_case to camelCase)
     */
    fun propertyNameToGetter(propertyName: String): String {
        val camelCase = snakeToCamel(propertyName)
        return GETTER_PREFIX + camelCase.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Convert property name to setter method name.
     */
    fun propertyNameToSetter(propertyName: String): String {
        val camelCase = snakeToCamel(propertyName)
        return SETTER_PREFIX + camelCase.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Convert snake_case to camelCase.
     * user_id -> userId
     * direction_id -> directionId
     */
    private fun snakeToCamel(name: String): String {
        val parts = name.split("_")
        return parts[0] + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    private fun buildPhpType(rawType: String?): PhpType? {
        val normalized = rawType
            ?.trim()
            ?.substringBefore("|")
            ?.removeSuffix("[]")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return PhpType().add(normalized)
    }

    private fun buildPhpTypeFromFqn(fqn: String): PhpType {
        return PhpType().add(fqn)
    }

    private fun isPublic(field: Field): Boolean = field.modifier.isPublic

    private fun isPublic(method: Method): Boolean = method.modifier.isPublic

    private fun normalizeFqn(name: String): String? {
        if (name.isBlank()) return null
        return if (name.startsWith("\\")) name else "\\$name"
    }
}

data class MagicProperty(
    val name: String,
    val type: PhpType?,
    val kind: PropertyKind,
    val source: PsiElement
)

enum class PropertyKind {
    FIELD,              // Public field declaration
    PHPDOC,             // @property in PHPDoc
    PHPDOC_READ,        // @property-read in PHPDoc
    PHPDOC_WRITE,       // @property-write in PHPDoc
    GETTER,             // getX() method
    SETTER,             // setX() method
    RELATION,           // getX() with hasOne/hasMany
    ATTRIBUTE           // Database column from attributes()
}

/**
 * Visitor to find the first parameter in a method call.
 */
private class FirstParameterVisitor : com.intellij.psi.PsiRecursiveElementVisitor() {
    var firstParam: PsiElement? = null

    override fun visitElement(element: PsiElement) {
        if (firstParam != null) return
        if (element is com.jetbrains.php.lang.psi.elements.ParameterList) {
            val params = element.children.filterIsInstance<com.jetbrains.php.lang.psi.elements.Parameter>()
            if (params.isNotEmpty()) {
                firstParam = params[0]
                return
            }
        }
        super.visitElement(element)
    }
}
