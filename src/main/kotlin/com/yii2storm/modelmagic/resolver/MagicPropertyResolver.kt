package com.yii2storm.modelmagic.resolver

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpReference
import com.jetbrains.php.lang.psi.elements.PhpUseList
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.yii2storm.modelmagic.settings.ModelMagicProjectSettingsService

class MagicPropertyResolver {

    companion object {
        private val LOG = Logger.getInstance(MagicPropertyResolver::class.java)
        private val INSTANCE = MagicPropertyResolver()
        private val YII_MODEL_CLASSES = setOf(
            "\\yii\\db\\ActiveRecord",
            "\\yii\\base\\Model",
            "\\yii\\db\\BaseActiveRecord",
        )
        private val PHP_DOC_PROPERTY_READ_REGEX = Regex(
            "@property-read\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)",
        )
        private val PHP_DOC_PROPERTY_WRITE_REGEX = Regex(
            "@property-write\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)",
        )
        private const val GETTER_PREFIX = "get"
        private const val SETTER_PREFIX = "set"

        fun getInstance(project: Project): MagicPropertyResolver = INSTANCE
    }

    fun getModelProperties(phpClass: PhpClass): List<MagicProperty> {
        if (!isModelClass(phpClass)) {
            return emptyList()
        }

        return CachedValuesManager.getCachedValue(phpClass) {
            val result = computeModelProperties(phpClass)
            CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
        }
    }

    fun getPropertyVariants(phpClass: PhpClass, propertyName: String): List<MagicProperty> {
        if (!isModelClass(phpClass)) {
            return emptyList()
        }

        val settings = ModelMagicProjectSettingsService.getInstance(phpClass.project)
        val variants = collectClassHierarchy(phpClass)
            .flatMap { collectFrom(it) }
            .filter { normalizedPropertyIdentity(it.name) == normalizedPropertyIdentity(propertyName) }
            .filter { settings.isSourceEnabled(it.kind) }
            .distinctBy { variantKey(it) }

        val hasNonPhpDoc = variants.any { !it.kind.isPhpDoc() }

        return variants
            .filter { !hasNonPhpDoc || !it.kind.isPhpDoc() }
            .sortedWith(
                compareBy<MagicProperty> { settings.navigationPriority(it.kind) }
                    .thenBy { navigationTieBreaker(it.kind) }
                    .thenBy { sourceOrder(it.source) }
            )
    }

    fun getPropertyType(phpClass: PhpClass, propertyName: String): PhpType? {
        return findProperty(phpClass, propertyName)?.type
    }

    fun resolveProperty(phpClass: PhpClass, propertyName: String): PsiElement? {
        return getPropertyVariants(phpClass, propertyName).firstOrNull()?.source
            ?: findProperty(phpClass, propertyName)?.source
    }

    fun hasProperty(phpClass: PhpClass, propertyName: String): Boolean {
        return findProperty(phpClass, propertyName) != null
    }

    fun isModelClass(phpClass: PhpClass): Boolean {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<PhpClass>()
        queue.add(phpClass)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.fqn in YII_MODEL_CLASSES) {
                return true
            }
            if (!visited.add(current.fqn)) {
                continue
            }
            current.superClass?.let(queue::add)
            getTraits(current).forEach { trait ->
                if (trait.fqn !in visited) {
                    queue.add(trait)
                }
            }
        }

        return false
    }

    fun propertyNameToGetter(propertyName: String): String {
        val variants = propertyNameVariants(propertyName)
        val getterCandidates = variants.map { GETTER_PREFIX + it.replaceFirstChar(Char::uppercaseChar) }
        return getterCandidates.first()
    }

    fun propertyNameToSetter(propertyName: String): String {
        val variants = propertyNameVariants(propertyName)
        val setterCandidates = variants.map { SETTER_PREFIX + it.replaceFirstChar(Char::uppercaseChar) }
        return setterCandidates.first()
    }

    fun propertyNameToGetterCandidates(propertyName: String): List<String> {
        return propertyNameVariants(propertyName)
            .map { GETTER_PREFIX + it.replaceFirstChar(Char::uppercaseChar) }
            .distinct()
    }

    fun propertyNameToSetterCandidates(propertyName: String): List<String> {
        return propertyNameVariants(propertyName)
            .map { SETTER_PREFIX + it.replaceFirstChar(Char::uppercaseChar) }
            .distinct()
    }

    private fun computeModelProperties(phpClass: PhpClass): List<MagicProperty> {
        LOG.debug("Computing magic properties for ${phpClass.fqn}")
        val settings = ModelMagicProjectSettingsService.getInstance(phpClass.project)

        return collectClassHierarchy(phpClass)
            .flatMap { collectFrom(it) }
            .filter { settings.isSourceEnabled(it.kind) }
            .groupBy { normalizedPropertyIdentity(it.name) }
            .mapNotNull { (_, variants) ->
                val selected = variants.minWithOrNull(
                    compareBy<MagicProperty> { settings.resolutionPriority(it.kind) }
                        .thenBy { resolutionTieBreaker(it.kind) }
                        .thenBy { sourceOrder(it.source) }
                ) ?: return@mapNotNull null
                selected.copy(name = preferredDisplayName(selected, variants))
            }
            .sortedBy { it.name }
    }

    private fun collectFrom(phpClass: PhpClass): List<MagicProperty> {
        return buildList {
            addAll(getPublicProperties(phpClass))
            addAll(getPhpDocProperties(phpClass))
            addAll(getGetterProperties(phpClass))
            addAll(getSetterProperties(phpClass))
            addAll(getRelationProperties(phpClass))
            addAll(getAttributesMethodProperties(phpClass))
        }
    }

    private fun findProperty(phpClass: PhpClass, propertyName: String): MagicProperty? {
        return getModelProperties(phpClass).firstOrNull { it.name == propertyName }
    }

    private fun collectClassHierarchy(phpClass: PhpClass): List<PhpClass> {
        val result = mutableListOf<PhpClass>()
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<PhpClass>()
        queue.add(phpClass)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.fqn)) {
                continue
            }
            result.add(current)
            current.superClass?.let(queue::add)
            getTraits(current).forEach { trait ->
                if (trait.fqn !in visited) {
                    queue.add(trait)
                }
            }
        }

        return result
    }

    private fun getTraits(phpClass: PhpClass): List<PhpClass> {
        return phpClass.children
            .filterIsInstance<PhpUseList>()
            .flatMap { traitUse ->
                traitUse.children.filterIsInstance<PhpReference>().mapNotNull { ref ->
                    ref.resolve() as? PhpClass
                }
            }
    }

    private fun getPublicProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.fields
            .filter(::isPublic)
            .map { field ->
                MagicProperty(
                    name = field.name,
                    type = field.type,
                    kind = PropertyKind.FIELD,
                    source = field,
                )
            }
    }

    private fun getPhpDocProperties(phpClass: PhpClass): List<MagicProperty> {
        val docComment = phpClass.docComment ?: return emptyList()
        val docText = docComment.text

        return buildList {
            Regex("@property\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)")
                .findAll(docText)
                .forEach { match ->
                    add(
                        MagicProperty(
                            name = match.groupValues[2].trim(),
                            type = buildPhpType(match.groupValues[1].trim()),
                            kind = PropertyKind.PHPDOC,
                            source = docComment,
                        ),
                    )
                }

            PHP_DOC_PROPERTY_READ_REGEX.findAll(docText).forEach { match ->
                add(
                    MagicProperty(
                        name = match.groupValues[2].trim(),
                        type = buildPhpType(match.groupValues[1].trim()),
                        kind = PropertyKind.PHPDOC_READ,
                        source = docComment,
                    ),
                )
            }

            PHP_DOC_PROPERTY_WRITE_REGEX.findAll(docText).forEach { match ->
                add(
                    MagicProperty(
                        name = match.groupValues[2].trim(),
                        type = buildPhpType(match.groupValues[1].trim()),
                        kind = PropertyKind.PHPDOC_WRITE,
                        source = docComment,
                    ),
                )
            }
        }.filter { it.name.isNotBlank() }
    }

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
                    name = accessorToPropertyName(method.name, GETTER_PREFIX),
                    type = getGetterReturnType(method),
                    kind = PropertyKind.GETTER,
                    source = method,
                )
            }
    }

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
                    name = accessorToPropertyName(method.name, SETTER_PREFIX),
                    type = getSetterParameterType(method),
                    kind = PropertyKind.SETTER,
                    source = method,
                )
            }
    }

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
                    name = accessorToPropertyName(method.name, GETTER_PREFIX),
                    type = extractRelationType(method) ?: getGetterReturnType(method),
                    kind = PropertyKind.RELATION,
                    source = method,
                )
            }
    }

    private fun getAttributesMethodProperties(phpClass: PhpClass): List<MagicProperty> {
        val attributesMethod = phpClass.methods.firstOrNull { method ->
            method.name == "attributes" && method.parameters.isEmpty()
        } ?: return emptyList()

        val propertyNames = mutableListOf<String>()
        attributesMethod.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                val text = element.text
                if ((text.startsWith("'") && text.endsWith("'") && text.length > 2) ||
                    (text.startsWith("\"") && text.endsWith("\"") && text.length > 2)
                ) {
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
                source = attributesMethod,
            )
        }
    }

    private fun isRelationMethod(method: Method): Boolean {
        return method.children.any(::isHasOneOrHasManyCall)
    }

    private fun isHasOneOrHasManyCall(element: PsiElement): Boolean {
        if (element is PhpReference) {
            val name = element.name
            if (name == "hasOne" || name == "hasMany") {
                return true
            }
        }
        return element.children.any(::isHasOneOrHasManyCall)
    }

    private fun extractRelationType(method: Method): PhpType? {
        val relationCall = findRelationCall(method) ?: return method.type
        val firstArgText = relationCall.parent?.text.orEmpty()
        if (firstArgText.contains("::class")) {
            val className = firstArgText.substringAfter("(").substringBefore("::class").trim()
            normalizeFqn(className)?.let(::buildPhpTypeFromFqn)?.let { return it }
        }
        return method.type
    }

    private fun findRelationCall(method: Method): PhpReference? {
        val visitor = RelationCallVisitor()
        method.accept(visitor)
        return visitor.found
    }

    private class RelationCallVisitor : com.intellij.psi.PsiRecursiveElementVisitor() {
        var found: PhpReference? = null

        override fun visitElement(element: PsiElement) {
            if (found != null) {
                return
            }
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

    private fun getSetterParameterType(method: Method): PhpType? {
        val firstParam = method.parameters.firstOrNull() ?: return null
        val typeHint = firstParam.declaredType
        if (!typeHint.isEmpty) {
            return typeHint
        }

        val docComment = method.docComment?.text ?: return null
        val paramPattern = Regex("@param\\s+([^\\s]+)")
        paramPattern.find(docComment)?.let { match ->
            return buildPhpType(match.groupValues[1].trim())
        }

        return null
    }

    private fun accessorToPropertyName(accessorName: String, prefix: String): String {
        val base = accessorName.removePrefix(prefix)
        if (base.isEmpty()) {
            return accessorName
        }

        val tokens = splitPropertyTokens(base)
        if (tokens.isEmpty()) {
            return base.replaceFirstChar(Char::lowercaseChar)
        }

        return tokens.first().lowercase() + tokens.drop(1).joinToString("") {
            it.lowercase().replaceFirstChar(Char::uppercaseChar)
        }
    }


    private fun getGetterReturnType(method: Method): PhpType? {
        val declaredType = method.declaredType
        if (!declaredType.isEmpty) {
            return declaredType
        }

        val docComment = method.docComment?.text
        if (!docComment.isNullOrBlank()) {
            Regex("""@return\s+([^\s]+)""").find(docComment)?.let { match ->
                buildPhpType(match.groupValues[1].trim())?.let { return it }
            }
        }

        val methodType = method.type
        if (!methodType.isEmpty && methodType.types.all { !it.contains('#') }) {
            return methodType
        }

        return null
    }

    private fun preferredDisplayName(selected: MagicProperty, variants: List<MagicProperty>): String {
        if (selected.kind == PropertyKind.GETTER || selected.kind == PropertyKind.SETTER || selected.kind == PropertyKind.RELATION) {
            return selected.name
        }

        return variants.firstOrNull { !it.name.contains('_') }?.name ?: selected.name
    }

    private fun normalizedPropertyIdentity(name: String): String {
        val tokens = splitPropertyTokens(name)
        return if (tokens.isEmpty()) name.lowercase() else tokens.joinToString("_") { it.lowercase() }
    }

    private fun propertyNameVariants(propertyName: String): List<String> {
        val tokens = splitPropertyTokens(propertyName)
        if (tokens.isEmpty()) {
            return listOf(propertyName)
        }

        val lowerSnake = tokens.joinToString("_") { it.lowercase() }
        val lowerCamel = tokens.first().lowercase() + tokens.drop(1).joinToString("") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
        val legacy = tokens.joinToString("_") { token ->
            if (token.length == 1) token.uppercase() else token.replaceFirstChar(Char::uppercaseChar).lowercase().replaceFirstChar(Char::uppercaseChar)
        }
        val pascal = tokens.joinToString("") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }

        return listOf(propertyName, lowerSnake, lowerCamel, legacy, pascal).distinct()
    }

    private fun splitPropertyTokens(name: String): List<String> {
        return name
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
            .split("_")
            .filter { it.isNotBlank() }
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

    private fun buildPhpTypeFromFqn(fqn: String): PhpType = PhpType().add(fqn)

    private fun isPublic(field: Field): Boolean = field.modifier.isPublic

    private fun isPublic(method: Method): Boolean = method.modifier.isPublic

    private fun normalizeFqn(name: String): String? {
        if (name.isBlank()) {
            return null
        }
        return if (name.startsWith("\\")) name else "\\$name"
    }

    private fun variantKey(property: MagicProperty): String {
        return "${property.kind}:${property.source.textOffset}:${property.name}"
    }

    private fun resolutionTieBreaker(kind: PropertyKind): Int = when (kind) {
        PropertyKind.GETTER -> 10
        PropertyKind.RELATION -> 20
        PropertyKind.SETTER -> 30
        PropertyKind.FIELD -> 40
        PropertyKind.ATTRIBUTE -> 50
        PropertyKind.PHPDOC -> 60
        PropertyKind.PHPDOC_READ -> 70
        PropertyKind.PHPDOC_WRITE -> 80
    }

    private fun navigationTieBreaker(kind: PropertyKind): Int = when (kind) {
        PropertyKind.GETTER -> 10
        PropertyKind.RELATION -> 20
        PropertyKind.SETTER -> 30
        PropertyKind.FIELD -> 40
        PropertyKind.ATTRIBUTE -> 50
        PropertyKind.PHPDOC -> 60
        PropertyKind.PHPDOC_READ -> 70
        PropertyKind.PHPDOC_WRITE -> 80
    }

    private fun sourceOrder(source: PsiElement): Int = source.textOffset

    private fun PropertyKind.isPhpDoc(): Boolean = this == PropertyKind.PHPDOC || this == PropertyKind.PHPDOC_READ || this == PropertyKind.PHPDOC_WRITE
}

data class MagicProperty(
    val name: String,
    val type: PhpType?,
    val kind: PropertyKind,
    val source: PsiElement,
)

enum class PropertyKind {
    FIELD,
    PHPDOC,
    PHPDOC_READ,
    PHPDOC_WRITE,
    GETTER,
    SETTER,
    RELATION,
    ATTRIBUTE,
}
