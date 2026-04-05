package com.yii2storm.modelmagic.resolver

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

class MagicPropertyResolver {

    companion object {
        private val INSTANCE = MagicPropertyResolver()

        private val yiiModelClasses = setOf(
            "\\yii\\db\\ActiveRecord",
            "\\yii\\base\\Model",
            "\\yii\\db\\BaseActiveRecord"
        )

        private val phpDocPropertyRegex = Regex(
            "@(property|property-read|property-write)\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)"
        )

        private const val getterPrefix = "get"
        private const val setterPrefix = "set"

        fun getInstance(project: Project): MagicPropertyResolver = INSTANCE
    }

    fun getModelProperties(phpClass: PhpClass): List<MagicProperty> {
        if (!isModelClass(phpClass)) {
            return emptyList()
        }

        return CachedValuesManager.getCachedValue(phpClass) {
            CachedValueProvider.Result.create(
                computeModelProperties(phpClass),
                phpClass,
                PsiModificationTracker.MODIFICATION_COUNT
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

    fun findProperty(phpClass: PhpClass, propertyName: String): MagicProperty? {
        return getModelProperties(phpClass).firstOrNull { it.name == propertyName }
    }

    fun getPropertyTargets(phpClass: PhpClass, propertyName: String): List<MagicPropertyTarget> {
        val result = LinkedHashMap<String, MagicPropertyTarget>()

        collectClassHierarchy(phpClass).forEach { currentClass ->
            collectFieldTargets(currentClass, propertyName, result)
            collectPhpDocTargets(currentClass, propertyName, result)
            collectMethodTargets(currentClass, propertyName, result)
            collectAttributesTargets(currentClass, propertyName, result)
        }

        val targets = result.values.sortedByDescending { it.kind.priority }
        val hasConcreteTargets = targets.any { it.kind != PropertyKind.PHPDOC && it.kind != PropertyKind.PHPDOC_READ && it.kind != PropertyKind.PHPDOC_WRITE }

        return if (hasConcreteTargets) {
            targets.filter { it.kind != PropertyKind.PHPDOC && it.kind != PropertyKind.PHPDOC_READ && it.kind != PropertyKind.PHPDOC_WRITE }
        } else {
            targets
        }
    }

    fun getGetterNames(propertyName: String): List<String> {
        return buildAccessorNames(getterPrefix, propertyName)
    }

    fun getSetterNames(propertyName: String): List<String> {
        return buildAccessorNames(setterPrefix, propertyName)
    }

    fun propertyNameToGetter(propertyName: String): String {
        return getGetterNames(propertyName).first()
    }

    fun propertyNameToSetter(propertyName: String): String {
        return getSetterNames(propertyName).first()
    }

    fun isModelClass(phpClass: PhpClass): Boolean {
        return collectClassHierarchy(phpClass).any { it.fqn in yiiModelClasses }
    }

    private fun computeModelProperties(phpClass: PhpClass): List<MagicProperty> {
        val properties = linkedMapOf<String, MagicProperty>()

        collectClassHierarchy(phpClass)
            .asReversed()
            .forEach { currentClass ->
                collectByPriority(properties, getPublicProperties(currentClass))
                collectByPriority(properties, getPhpDocProperties(currentClass))
                collectByPriority(properties, getGetterProperties(currentClass))
                collectByPriority(properties, getSetterProperties(currentClass))
                collectByPriority(properties, getRelationProperties(currentClass))
                collectByPriority(properties, getAttributesMethodProperties(currentClass))
            }

        return properties.values.toList()
    }

    private fun collectByPriority(
        properties: MutableMap<String, MagicProperty>,
        candidates: List<MagicProperty>
    ) {
        candidates.forEach { candidate ->
            val existing = properties[candidate.name]
            if (existing == null || candidate.kind.priority >= existing.kind.priority) {
                properties[candidate.name] = candidate
            }
        }
    }

    private fun collectClassHierarchy(phpClass: PhpClass): List<PhpClass> {
        val result = mutableListOf<PhpClass>()
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque<PhpClass>()
        queue.add(phpClass)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val key = current.fqn.ifBlank { current.name }
            if (!visited.add(key)) {
                continue
            }

            result.add(current)
            getTraits(current).forEach(queue::addLast)
            current.superClass?.let(queue::addLast)
        }

        return result
    }

    private fun getTraits(phpClass: PhpClass): List<PhpClass> {
        return phpClass.children
            .filterIsInstance<PhpUseList>()
            .flatMap { useList ->
                useList.children.filterIsInstance<PhpReference>().mapNotNull { reference ->
                    reference.resolve() as? PhpClass
                }
            }
            .distinctBy { it.fqn }
    }

    private fun getPublicProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.fields
            .filter { it.modifier.isPublic }
            .map { field ->
                MagicProperty(field.name, field.type, PropertyKind.FIELD, field)
            }
    }

    private fun getPhpDocProperties(phpClass: PhpClass): List<MagicProperty> {
        val docComment = phpClass.docComment ?: return emptyList()

        return phpDocPropertyRegex.findAll(docComment.text)
            .mapNotNull { match ->
                val annotation = match.groupValues[1]
                val rawType = match.groupValues[2].trim()
                val propertyName = match.groupValues[3].trim()
                val kind = when (annotation) {
                    "property-read" -> PropertyKind.PHPDOC_READ
                    "property-write" -> PropertyKind.PHPDOC_WRITE
                    else -> PropertyKind.PHPDOC
                }
                propertyName.takeIf { it.isNotBlank() }?.let {
                    MagicProperty(it, buildPhpType(rawType), kind, docComment)
                }
            }
            .toList()
    }

    private fun getGetterProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.methods
            .filter { method ->
                method.modifier.isPublic &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(getterPrefix) &&
                    method.name.length > getterPrefix.length &&
                    !isRelationMethod(method)
            }
            .mapNotNull { method ->
                accessorToPropertyNames(method.name, getterPrefix).firstOrNull()?.let { propertyName ->
                    MagicProperty(propertyName, method.type, PropertyKind.GETTER, method)
                }
            }
    }

    private fun getSetterProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.methods
            .filter { method ->
                method.modifier.isPublic &&
                    method.parameters.isNotEmpty() &&
                    method.name.startsWith(setterPrefix) &&
                    method.name.length > setterPrefix.length &&
                    !isRelationMethod(method)
            }
            .mapNotNull { method ->
                accessorToPropertyNames(method.name, setterPrefix).firstOrNull()?.let { propertyName ->
                    MagicProperty(propertyName, getSetterParameterType(method), PropertyKind.SETTER, method)
                }
            }
    }

    private fun getRelationProperties(phpClass: PhpClass): List<MagicProperty> {
        return phpClass.methods
            .filter { method ->
                method.modifier.isPublic &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(getterPrefix) &&
                    method.name.length > getterPrefix.length &&
                    isRelationMethod(method)
            }
            .mapNotNull { method ->
                accessorToPropertyNames(method.name, getterPrefix).firstOrNull()?.let { propertyName ->
                    MagicProperty(propertyName, extractRelationType(method), PropertyKind.RELATION, method)
                }
            }
    }

    private fun getAttributesMethodProperties(phpClass: PhpClass): List<MagicProperty> {
        val attributesMethod = phpClass.methods.firstOrNull { it.name == "attributes" && it.parameters.isEmpty() }
            ?: return emptyList()

        val body = attributesMethod.text
        val returnArrayMatch = Regex("return\\s*\\[(.*?)]", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(body)
            ?: return emptyList()

        return Regex("['\"]([A-Za-z_][A-Za-z0-9_]*)['\"]")
            .findAll(returnArrayMatch.groupValues[1])
            .map { it.groupValues[1] }
            .distinct()
            .map { propertyName ->
                MagicProperty(propertyName, null, PropertyKind.ATTRIBUTE, attributesMethod)
            }
            .toList()
    }

    private fun collectFieldTargets(
        phpClass: PhpClass,
        propertyName: String,
        result: MutableMap<String, MagicPropertyTarget>
    ) {
        phpClass.fields
            .firstOrNull { it.modifier.isPublic && it.name == propertyName }
            ?.let { field ->
                result.putIfAbsent(targetKey(field), MagicPropertyTarget(field, PropertyKind.FIELD))
            }
    }

    private fun collectPhpDocTargets(
        phpClass: PhpClass,
        propertyName: String,
        result: MutableMap<String, MagicPropertyTarget>
    ) {
        val docComment = phpClass.docComment ?: return
        phpDocPropertyRegex.findAll(docComment.text).forEach { match ->
            if (match.groupValues[3] != propertyName) {
                return@forEach
            }
            val kind = when (match.groupValues[1]) {
                "property-read" -> PropertyKind.PHPDOC_READ
                "property-write" -> PropertyKind.PHPDOC_WRITE
                else -> PropertyKind.PHPDOC
            }
            result.putIfAbsent(targetKey(docComment, kind.name), MagicPropertyTarget(docComment, kind))
        }
    }

    private fun collectMethodTargets(
        phpClass: PhpClass,
        propertyName: String,
        result: MutableMap<String, MagicPropertyTarget>
    ) {
        phpClass.methods
            .filter { it.modifier.isPublic }
            .forEach { method ->
                when {
                    method.parameters.isEmpty() && method.name.startsWith(getterPrefix) -> {
                        if (propertyName in accessorToPropertyNames(method.name, getterPrefix)) {
                            val kind = if (isRelationMethod(method)) PropertyKind.RELATION else PropertyKind.GETTER
                            result.putIfAbsent(targetKey(method), MagicPropertyTarget(method, kind))
                        }
                    }
                    method.parameters.isNotEmpty() && method.name.startsWith(setterPrefix) -> {
                        if (propertyName in accessorToPropertyNames(method.name, setterPrefix)) {
                            result.putIfAbsent(targetKey(method), MagicPropertyTarget(method, PropertyKind.SETTER))
                        }
                    }
                }
            }
    }

    private fun collectAttributesTargets(
        phpClass: PhpClass,
        propertyName: String,
        result: MutableMap<String, MagicPropertyTarget>
    ) {
        val attributesMethod = phpClass.methods.firstOrNull { it.name == "attributes" && it.parameters.isEmpty() }
            ?: return
        if (getAttributesMethodProperties(phpClass).any { it.name == propertyName }) {
            result.putIfAbsent(targetKey(attributesMethod, PropertyKind.ATTRIBUTE.name), MagicPropertyTarget(attributesMethod, PropertyKind.ATTRIBUTE))
        }
    }

    private fun isRelationMethod(method: Method): Boolean {
        val text = method.text
        return Regex("\\breturn\\b[\\s\\S]*?\\b(hasOne|hasMany)\\s*\\(")
            .containsMatchIn(text)
    }

    private fun extractRelationType(method: Method): PhpType? {
        val text = method.text
        val classReferenceMatch = Regex("\\b(?:hasOne|hasMany)\\s*\\(\\s*([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)::class")
            .find(text)
        if (classReferenceMatch != null) {
            return buildPhpTypeFromFqn(normalizeFqn(classReferenceMatch.groupValues[1]) ?: return method.type)
        }

        val stringReferenceMatch = Regex("\\b(?:hasOne|hasMany)\\s*\\(\\s*['\"]([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)['\"]")
            .find(text)
        if (stringReferenceMatch != null) {
            return buildPhpTypeFromFqn(normalizeFqn(stringReferenceMatch.groupValues[1]) ?: return method.type)
        }

        return method.type
    }

    private fun getSetterParameterType(method: Method): PhpType? {
        val firstParam = method.parameters.firstOrNull() ?: return null
        if (!firstParam.declaredType.isEmpty) {
            return firstParam.declaredType
        }

        val docComment = method.docComment?.text ?: return null
        return Regex("@param\\s+([^\\s]+)").find(docComment)?.groupValues?.getOrNull(1)?.let(::buildPhpType)
    }

    private fun accessorToPropertyNames(methodName: String, prefix: String): List<String> {
        val base = methodName.removePrefix(prefix)
        if (base.isBlank()) {
            return emptyList()
        }

        val lcFirst = base.replaceFirstChar { it.lowercaseChar() }
        val snakeCase = camelToSnake(base).replaceFirstChar { it.lowercaseChar() }

        return linkedSetOf(lcFirst, snakeCase)
            .filter { it.isNotBlank() }
    }

    private fun buildAccessorNames(prefix: String, propertyName: String): List<String> {
        val normalized = propertyName.trim()
        if (normalized.isBlank()) {
            return emptyList()
        }

        val direct = prefix + normalized.replaceFirstChar { it.uppercaseChar() }
        val camelized = prefix + snakeToPascal(normalized)

        return linkedSetOf(camelized, direct)
            .filter { it.length > prefix.length }
    }

    private fun snakeToPascal(name: String): String {
        return name.split('_')
            .filter { it.isNotBlank() }
            .joinToString("") { part ->
                part.replaceFirstChar { it.uppercaseChar() }
            }
    }

    private fun camelToSnake(name: String): String {
        return name.replace(Regex("(?<!^)([A-Z])"), "_$1")
    }

    private fun buildPhpType(rawType: String?): PhpType? {
        val normalized = rawType
            ?.trim()
            ?.substringBefore('|')
            ?.removeSuffix("[]")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return PhpType().add(normalizeFqnOrPrimitive(normalized))
    }

    private fun buildPhpTypeFromFqn(fqn: String): PhpType {
        return PhpType().add(fqn)
    }

    private fun normalizeFqnOrPrimitive(name: String): String {
        return if (name.startsWith("\\") || name.lowercase() in primitiveTypes) {
            name
        } else {
            "\\$name"
        }
    }

    private fun normalizeFqn(name: String): String? {
        if (name.isBlank()) {
            return null
        }
        return if (name.startsWith("\\")) name else "\\$name"
    }

    private fun targetKey(element: PsiElement, suffix: String = ""): String {
        return buildString {
            append(element.containingFile?.virtualFile?.path ?: "")
            append(':')
            append(element.textOffset)
            append(':')
            append(suffix)
        }
    }

    private val primitiveTypes = setOf(
        "int", "integer", "float", "double", "string", "bool", "boolean", "array", "mixed", "callable", "iterable", "object", "self", "static", "void"
    )
}

data class MagicProperty(
    val name: String,
    val type: PhpType?,
    val kind: PropertyKind,
    val source: PsiElement
)

data class MagicPropertyTarget(
    val element: PsiElement,
    val kind: PropertyKind
)

enum class PropertyKind(val priority: Int) {
    FIELD(10),
    PHPDOC(20),
    PHPDOC_READ(21),
    PHPDOC_WRITE(21),
    GETTER(30),
    SETTER(40),
    RELATION(50),
    ATTRIBUTE(60)
}
