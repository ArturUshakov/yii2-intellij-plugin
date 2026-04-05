package com.yii2storm.modelmagic.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.modelmagic.resolver.MagicProperty
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.resolver.PropertyKind

class MagicPropertyGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null

        // Try field reference ($model->property)
        val fieldReference = element.parentOfType<FieldReference>(withSelf = true) ?: return null
        return getTargetsForFieldReference(element, fieldReference)
    }

    private fun getTargetsForFieldReference(
        element: PsiElement,
        fieldReference: FieldReference
    ): Array<PsiElement>? {
        val propertyName = fieldReference.name ?: return null

        // Only trigger when cursor is on the property name itself
        if (element.text != propertyName) {
            return null
        }

        val resolver = MagicPropertyResolver.getInstance(element.project)
        val modelClasses = com.yii2storm.modelmagic.util.MagicPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)
        if (modelClasses.isEmpty()) {
            return null
        }

        return collectTargets(resolver, modelClasses, propertyName)
    }

    private fun collectTargets(
        resolver: MagicPropertyResolver,
        modelClasses: List<PhpClass>,
        propertyName: String
    ): Array<PsiElement>? {
        // Collect ALL possible targets with their priority scores
        val targetsWithPriority = mutableListOf<Triple<PsiElement, Int, String>>()

        modelClasses.forEach { phpClass ->
            resolver.getModelProperties(phpClass)
                .filter { it.name == propertyName }
                .forEach { property ->
                    val targets = resolvePropertyTargets(phpClass, property, propertyName)
                    targets.forEach { (target, label) ->
                        val priority = getPriorityForKind(property.kind)
                        targetsWithPriority.add(Triple(target, priority, label))
                    }
                }
        }

        if (targetsWithPriority.isEmpty()) {
            return null
        }

        // Sort by priority (highest first) and return unique elements
        val sortedTargets = targetsWithPriority
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .toTypedArray()

        return sortedTargets.takeIf { it.isNotEmpty() }
    }

    /**
     * Get priority score for property kind.
     * Higher score = more important = appears first in navigation list.
     */
    private fun getPriorityForKind(kind: PropertyKind): Int {
        return when (kind) {
            PropertyKind.RELATION -> 100     // Highest priority
            PropertyKind.GETTER -> 90
            PropertyKind.SETTER -> 85
            PropertyKind.ATTRIBUTE -> 80
            PropertyKind.FIELD -> 70
            PropertyKind.PHPDOC -> 50
            PropertyKind.PHPDOC_READ -> 40
            PropertyKind.PHPDOC_WRITE -> 30
        }
    }

    /**
     * Resolve to ALL meaningful PSI elements for the property.
     * Returns list of (target, label) pairs.
     */
    private fun resolvePropertyTargets(
        phpClass: PhpClass,
        property: MagicProperty,
        propertyName: String
    ): List<Pair<PsiElement, String>> {
        return when (property.kind) {
            PropertyKind.RELATION -> {
                val method = findMethodInHierarchy(phpClass, getterMethodName(propertyName))
                if (method != null) listOf(method to "Relation") else emptyList()
            }
            PropertyKind.GETTER -> {
                val method = findMethodInHierarchy(phpClass, getterMethodName(propertyName))
                if (method != null) listOf(method to "Getter") else emptyList()
            }
            PropertyKind.SETTER -> {
                val method = findMethodInHierarchy(phpClass, setterMethodName(propertyName))
                if (method != null) listOf(method to "Setter") else emptyList()
            }
            PropertyKind.FIELD -> {
                val field = findFieldInHierarchy(phpClass, propertyName)
                if (field != null) listOf(field to "Field") else emptyList()
            }
            PropertyKind.PHPDOC -> {
                val docComment = findDocCommentWithProperty(phpClass, propertyName)
                if (docComment != null) listOf(docComment to "@property") else emptyList()
            }
            PropertyKind.PHPDOC_READ -> {
                val docComment = findDocCommentWithPropertyAnnotation(phpClass, propertyName, "@property-read")
                if (docComment != null) listOf(docComment to "@property-read") else emptyList()
            }
            PropertyKind.PHPDOC_WRITE -> {
                val docComment = findDocCommentWithPropertyAnnotation(phpClass, propertyName, "@property-write")
                if (docComment != null) listOf(docComment to "@property-write") else emptyList()
            }
            PropertyKind.ATTRIBUTE -> {
                val method = findMethodInHierarchy(phpClass, "attributes")
                if (method != null) listOf(method to "Attributes") else emptyList()
            }
        }
    }

    private fun findMethodInHierarchy(phpClass: PhpClass, methodName: String): Method? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val method = current.methods.firstOrNull { it.name == methodName }
            if (method != null) return method
            current = current.superClass
        }
        return null
    }

    private fun findFieldInHierarchy(phpClass: PhpClass, fieldName: String): Field? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val field = current.fields.firstOrNull { it.name == fieldName }
            if (field != null) return field
            current = current.superClass
        }
        return null
    }

    private fun findDocCommentWithProperty(phpClass: PhpClass, propertyName: String): PsiElement? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val docComment = current.docComment
            if (docComment != null && docComment.text.contains("@property") && docComment.text.contains("\$${propertyName}")) {
                return docComment
            }
            current = current.superClass
        }
        return null
    }

    private fun findDocCommentWithPropertyAnnotation(
        phpClass: PhpClass,
        propertyName: String,
        annotationName: String
    ): PsiElement? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val docComment = current.docComment
            if (docComment != null &&
                docComment.text.contains(annotationName) &&
                docComment.text.contains("\$${propertyName}")) {
                return docComment
            }
            current = current.superClass
        }
        return null
    }

    private fun getterMethodName(propertyName: String): String {
        // Convert snake_case to camelCase for getter name
        val camelCase = propertyName.split("_")
            .mapIndexed { index, s -> if (index == 0) s else s.replaceFirstChar { it.uppercaseChar() } }
            .joinToString("")
        return "get" + camelCase.replaceFirstChar { it.uppercaseChar() }
    }

    private fun setterMethodName(propertyName: String): String {
        // Convert snake_case to camelCase for setter name
        val camelCase = propertyName.split("_")
            .mapIndexed { index, s -> if (index == 0) s else s.replaceFirstChar { it.uppercaseChar() } }
            .joinToString("")
        return "set" + camelCase.replaceFirstChar { it.uppercaseChar() }
    }

    override fun getActionText(context: DataContext): String? = null
}
