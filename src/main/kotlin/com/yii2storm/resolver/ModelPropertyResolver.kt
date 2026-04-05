package com.yii2storm.resolver

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.resolve.types.PhpType

class ModelPropertyResolver private constructor() {

    companion object {
        private val INSTANCE = ModelPropertyResolver()

        private val YII_MODEL_CLASSES = setOf(
            "\\yii\\db\\ActiveRecord",
            "\\yii\\base\\Model"
        )

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

        fun getInstance(project: Project): ModelPropertyResolver = INSTANCE
    }

    /**
     * Get all magic properties for a model class, collected from the entire hierarchy.
     * Priority order (lowest to highest):
     * FIELD < PHPDOC < GETTER < SETTER < RELATION < ATTRIBUTE
     *
     * Relations and attributes override everything, setters override getters for the same property name.
     */
    fun getModelProperties(phpClass: PhpClass): List<ModelProperty> {
        if (!isModelClass(phpClass)) {
            return emptyList()
        }

        val properties = linkedMapOf<String, ModelProperty>()

        collectClassHierarchy(phpClass).forEach { currentClass ->
            collectFrom(currentClass, properties)
        }

        return properties.values.toList()
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
     * Check if a class extends yii\db\ActiveRecord or yii\base\Model.
     * Walks up the inheritance chain.
     */
    fun isModelClass(phpClass: PhpClass): Boolean {
        var current: PhpClass? = phpClass
        while (current != null) {
            if (current.fqn in YII_MODEL_CLASSES) {
                return true
            }
            current = current.superClass
        }
        return false
    }

    // --- Internal collection logic ---

    private fun collectFrom(
        phpClass: PhpClass,
        properties: MutableMap<String, ModelProperty>
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

    private fun findProperty(phpClass: PhpClass, propertyName: String): ModelProperty? {
        return getModelProperties(phpClass).firstOrNull { it.name == propertyName }
    }

    private fun collectClassHierarchy(phpClass: PhpClass): List<PhpClass> {
        val result = mutableListOf<PhpClass>()
        var current: PhpClass? = phpClass
        while (current != null) {
            result.add(current)
            current = current.superClass
        }
        return result
    }

    // --- Property source extractors ---

    private fun getPublicProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.fields
            .filter { field -> isPublic(field) }
            .map { field ->
                ModelProperty(
                    name = field.name,
                    type = field.type,
                    kind = PropertyKind.FIELD,
                    source = field
                )
            }
    }

    private fun getPhpDocProperties(phpClass: PhpClass): List<ModelProperty> {
        val docComment = phpClass.docComment ?: return emptyList()
        val docText = docComment.text

        return buildList {
            // Regular @property declarations
            PHP_DOC_PROPERTY_REGEX.findAll(docText).forEach { match ->
                val rawType = match.groupValues[1].trim()
                val propertyName = match.groupValues[2].trim()
                if (propertyName.isNotBlank()) {
                    add(
                        ModelProperty(
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
                        ModelProperty(
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
                        ModelProperty(
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
     * These define readable magic properties.
     * 
     * Examples:
     *   getDirection_id() -> property "direction_id"
     *   getFullName() -> property "fullName"
     */
    private fun getGetterProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(GETTER_PREFIX) &&
                    method.name.length > GETTER_PREFIX.length &&
                    !isRelationMethod(method)
            }
            .map { method ->
                ModelProperty(
                    name = getterToPropertyName(method.name),
                    type = method.type,
                    kind = PropertyKind.GETTER,
                    source = method
                )
            }
    }

    /**
     * Find all public setX() methods.
     * These define writable magic properties.
     * 
     * Examples:
     *   setDirection_id($value) -> property "direction_id"
     *   setFullName($value) -> property "fullName"
     */
    private fun getSetterProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.name.startsWith(SETTER_PREFIX) &&
                    method.name.length > SETTER_PREFIX.length &&
                    method.parameters.isNotEmpty() &&
                    !isRelationMethod(method)
            }
            .map { method ->
                ModelProperty(
                    name = setterToPropertyName(method.name),
                    type = getSetterParameterType(method),
                    kind = PropertyKind.SETTER,
                    source = method
                )
            }
    }

    /**
     * Find all public getX() methods that contain hasOne/hasMany calls.
     * These override regular getters with the same property name.
     */
    private fun getRelationProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(GETTER_PREFIX) &&
                    method.name.length > GETTER_PREFIX.length &&
                    isRelationMethod(method)
            }
            .map { method ->
                ModelProperty(
                    name = getterToPropertyName(method.name),
                    type = extractRelationType(method),
                    kind = PropertyKind.RELATION,
                    source = method
                )
            }
    }

    /**
     * Parse the attributes() method to find additional property names.
     * These represent database columns that Yii2 ActiveRecord automatically exposes.
     */
    private fun getAttributesMethodProperties(phpClass: PhpClass): List<ModelProperty> {
        val attributesMethod = phpClass.methods.firstOrNull { method ->
            method.name == "attributes" && method.parameters.isEmpty()
        } ?: return emptyList()

        // Extract string literals from the method body
        val stringRegex = Regex("'([^']+)'|\"([^\"]+)\"")
        val names = stringRegex.findAll(attributesMethod.text)
            .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] }.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return names.map { propertyName ->
            ModelProperty(
                name = propertyName,
                type = null,
                kind = PropertyKind.ATTRIBUTE,
                source = attributesMethod
            )
        }.toList()
    }

    // --- Relation detection ---

    /**
     * Detect if a method is a Yii2 relation by checking for hasOne/hasMany calls.
     * Uses text-based detection on the method body.
     */
    private fun isRelationMethod(method: Method): Boolean {
        val text = method.text
        return text.contains("hasOne(") || text.contains("hasMany(")
    }

    /**
     * Extract the related model class FQN from hasOne/hasMany call.
     * Supports patterns like:
     *   $this->hasOne(Model::class, [...])
     *   $this->hasMany(\app\models\Model::class, [...])
     */
    private fun extractRelationType(method: Method): PhpType? {
        val text = method.text

        // Try to find ClassName::class pattern inside hasOne/hasMany
        val classPattern = Regex("(?:hasOne|hasMany)\\s*\\(\\s*([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)::class")
        classPattern.find(text)?.let { match ->
            val fqn = normalizeFqn(match.groupValues[1].trim())
            if (fqn != null) return buildPhpTypeFromFqn(fqn)
        }

        // Fallback: use method return type
        return method.type
    }

    /**
     * Extract parameter type from setter method for better type inference.
     * For setDirection_id($value), tries to find type hint or PHPDoc @param.
     */
    private fun getSetterParameterType(method: Method): PhpType? {
        val firstParam = method.parameters.firstOrNull() ?: return null

        // Try to get type hint from parameter
        val typeHint = firstParam.declaredType
        if (typeHint.isEmpty.not()) {
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
     * setDirection_id -> direction_id
     * setFullName -> fullName
     */
    private fun setterToPropertyName(setterName: String): String {
        val base = setterName.removePrefix(SETTER_PREFIX)
        if (base.isEmpty()) return setterName
        return base.replaceFirstChar { it.lowercaseChar() }
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

data class ModelProperty(
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
