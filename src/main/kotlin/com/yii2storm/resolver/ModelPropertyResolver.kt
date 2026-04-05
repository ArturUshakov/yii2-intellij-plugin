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

        private const val RELATION_METHOD_PREFIX = "get"

        fun getInstance(project: Project): ModelPropertyResolver = INSTANCE
    }

    /**
     * Get all magic properties for a model class, collected from the entire hierarchy.
     * Relations override getters and plain properties with the same name.
     * Order: FIELD < PHPDOC < GETTER < RELATION < ATTRIBUTE
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
        getGetterProperties(phpClass).forEach { properties.putIfAbsent(it.name, it) }

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

        return PHP_DOC_PROPERTY_REGEX.findAll(docText)
            .mapNotNull { match ->
                val rawType = match.groupValues[1].trim()
                val propertyName = match.groupValues[2].trim()
                if (propertyName.isBlank()) null
                else ModelProperty(
                    name = propertyName,
                    type = buildPhpType(rawType),
                    kind = PropertyKind.PHPDOC,
                    source = docComment
                )
            }
            .toList()
    }

    /**
     * Find all public getX() methods that are NOT relation methods.
     */
    private fun getGetterProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.methods
            .filter { method ->
                isPublic(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith(RELATION_METHOD_PREFIX) &&
                    method.name.length > RELATION_METHOD_PREFIX.length &&
                    !isRelationMethod(method)
            }
            .map { method ->
                ModelProperty(
                    name = toPropertyName(method.name),
                    type = method.type,
                    kind = PropertyKind.GETTER,
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
                    method.name.startsWith(RELATION_METHOD_PREFIX) &&
                    method.name.length > RELATION_METHOD_PREFIX.length &&
                    isRelationMethod(method)
            }
            .map { method ->
                ModelProperty(
                    name = toPropertyName(method.name),
                    type = extractRelationType(method),
                    kind = PropertyKind.RELATION,
                    source = method
                )
            }
    }

    /**
     * Parse the attributes() method to find additional property names.
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
     * Uses text-based detection on the method body since we need to parse PHP code structure.
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

    // --- Helpers ---

    private fun toPropertyName(getterName: String): String {
        val base = getterName.removePrefix(RELATION_METHOD_PREFIX)
        if (base.isEmpty()) return getterName
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
    FIELD,
    PHPDOC,
    GETTER,
    RELATION,
    ATTRIBUTE
}
