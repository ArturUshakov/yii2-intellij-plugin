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

        private val phpDocPropertyRegex = Regex(
            "@property(?:-read|-write)?\\s+([^\\s]+)\\s+\\$([A-Za-z_][A-Za-z0-9_]*)"
        )

        private val singleQuotedStringRegex = Regex("'([^']+)'")

        private val hasOneRegex = Regex(
            "->hasOne\\(\\s*([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)::class"
        )

        private val hasManyRegex = Regex(
            "->hasMany\\(\\s*([\\\\A-Za-z_][\\\\A-Za-z0-9_]*)::class"
        )

        fun getInstance(project: Project): ModelPropertyResolver = INSTANCE
    }

    fun getModelProperties(phpClass: PhpClass): List<ModelProperty> {
        if (!isModelClass(phpClass)) {
            return emptyList()
        }

        val properties = linkedMapOf<String, ModelProperty>()

        collectClassHierarchy(phpClass).forEach { currentClass ->
            getPublicProperties(currentClass).forEach { properties.putIfAbsent(it.name, it) }
            getPhpDocProperties(currentClass).forEach { properties.putIfAbsent(it.name, it) }
            getRelationProperties(currentClass).forEach { properties[it.name] = it }
            getGetterProperties(currentClass).forEach { properties.putIfAbsent(it.name, it) }
            getAttributesMethodProperties(currentClass).forEach { properties.putIfAbsent(it.name, it) }
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

    fun isModelClass(phpClass: PhpClass): Boolean {
        var currentClass: PhpClass? = phpClass

        while (currentClass != null) {
            val fqn = currentClass.fqn
            if (fqn == "\\yii\\db\\ActiveRecord" || fqn == "\\yii\\base\\Model") {
                return true
            }
            currentClass = currentClass.superClass
        }

        return false
    }

    private fun findProperty(phpClass: PhpClass, propertyName: String): ModelProperty? {
        return getModelProperties(phpClass).firstOrNull { it.name == propertyName }
    }

    private fun collectClassHierarchy(phpClass: PhpClass): List<PhpClass> {
        val result = mutableListOf<PhpClass>()
        var currentClass: PhpClass? = phpClass

        while (currentClass != null) {
            result.add(currentClass)
            currentClass = currentClass.superClass
        }

        return result
    }

    private fun getPublicProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.fields
            .filter { field -> hasPublicVisibility(field) }
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
        val docText = phpClass.docComment?.text ?: return emptyList()

        return phpDocPropertyRegex.findAll(docText)
            .mapNotNull { matchResult ->
                val rawType = matchResult.groupValues[1].trim()
                val propertyName = matchResult.groupValues[2].trim()

                if (propertyName.isBlank()) {
                    null
                } else {
                    ModelProperty(
                        name = propertyName,
                        type = buildPhpType(rawType),
                        kind = PropertyKind.PHPDOC,
                        source = phpClass.docComment ?: phpClass
                    )
                }
            }
            .toList()
    }

    private fun getGetterProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.methods
            .filter { method ->
                hasPublicVisibility(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith("get") &&
                    method.name.length > 3
            }
            .map { method ->
                ModelProperty(
                    name = getterNameToPropertyName(method.name),
                    type = method.type,
                    kind = PropertyKind.GETTER,
                    source = method
                )
            }
    }

    private fun getRelationProperties(phpClass: PhpClass): List<ModelProperty> {
        return phpClass.methods
            .filter { method ->
                hasPublicVisibility(method) &&
                    method.parameters.isEmpty() &&
                    method.name.startsWith("get") &&
                    method.name.length > 3 &&
                    isRelationMethod(method)
            }
            .map { method ->
                ModelProperty(
                    name = getterNameToPropertyName(method.name),
                    type = extractRelationType(method),
                    kind = PropertyKind.RELATION,
                    source = method
                )
            }
    }

    private fun getAttributesMethodProperties(phpClass: PhpClass): List<ModelProperty> {
        val attributesMethod = phpClass.methods.firstOrNull { method ->
            method.name == "attributes" && method.parameters.isEmpty()
        } ?: return emptyList()

        val names = singleQuotedStringRegex.findAll(attributesMethod.text)
            .map { matchResult -> matchResult.groupValues[1] }
            .filter { value -> value.isNotBlank() }
            .distinct()
            .toList()

        return names.map { propertyName ->
            ModelProperty(
                name = propertyName,
                type = null,
                kind = PropertyKind.ATTRIBUTE,
                source = attributesMethod
            )
        }
    }

    private fun isRelationMethod(method: Method): Boolean {
        val text = method.text
        return text.contains("->hasOne(") || text.contains("->hasMany(")
    }

    private fun extractRelationType(method: Method): PhpType? {
        val text = method.text

        hasOneRegex.find(text)?.let { matchResult ->
            return buildPhpType(matchResult.groupValues[1].trim())
        }

        hasManyRegex.find(text)?.let { matchResult ->
            return buildPhpType(matchResult.groupValues[1].trim())
        }

        return method.type
    }

    private fun getterNameToPropertyName(getterName: String): String {
        val baseName = getterName.removePrefix("get")
        if (baseName.isEmpty()) {
            return getterName
        }

        return baseName.replaceFirstChar { firstChar ->
            firstChar.lowercase()
        }
    }

    private fun buildPhpType(rawType: String?): PhpType? {
        val normalizedType = rawType
            ?.trim()
            ?.substringBefore("|")
            ?.removeSuffix("[]")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return PhpType().add(normalizedType)
    }

    private fun hasPublicVisibility(field: Field): Boolean {
        return field.modifier.isPublic
    }

    private fun hasPublicVisibility(method: Method): Boolean {
        return method.modifier.isPublic
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