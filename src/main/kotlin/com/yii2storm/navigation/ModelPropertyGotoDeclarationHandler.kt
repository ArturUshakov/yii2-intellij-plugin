package com.yii2storm.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.resolver.ModelProperty
import com.yii2storm.resolver.ModelPropertyResolver
import com.yii2storm.resolver.PropertyKind

class ModelPropertyGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val fieldReference = element.parentOfType<FieldReference>(withSelf = true) ?: return null
        val propertyName = fieldReference.name ?: return null

        // Only trigger when cursor is on the property name itself
        if (element.text != propertyName) {
            return null
        }

        val resolver = ModelPropertyResolver.getInstance(element.project)
        val modelClasses = com.yii2storm.util.ModelPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)
        if (modelClasses.isEmpty()) {
            return null
        }

        // Collect ALL possible targets with their priority scores
        val targetsWithPriority = mutableListOf<Pair<PsiElement, Int>>()

        modelClasses.forEach { phpClass ->
            resolver.getModelProperties(phpClass)
                .filter { it.name == propertyName }
                .forEach { property ->
                    val target = resolvePropertyTarget(phpClass, property, propertyName)
                    if (target != null) {
                        val priority = getPriorityForKind(property.kind)
                        targetsWithPriority.add(target to priority)
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
     * Resolve to the most meaningful PSI element for the property:
     * - RELATION: the getter method that defines the relation
     * - GETTER: the getter method
     * - SETTER: the setter method
     * - FIELD: the field declaration itself
     * - PHPDOC: the @property line in the doc comment of the class that declares it
     * - PHPDOC_READ: the @property-read line in the doc comment
     * - PHPDOC_WRITE: the @property-write line in the doc comment
     * - ATTRIBUTE: the attributes() method
     */
    private fun resolvePropertyTarget(
        phpClass: PhpClass,
        property: ModelProperty,
        propertyName: String
    ): PsiElement? {
        return when (property.kind) {
            PropertyKind.RELATION -> findMethodInHierarchy(phpClass, getterMethodName(propertyName))
            PropertyKind.GETTER -> findMethodInHierarchy(phpClass, getterMethodName(propertyName))
            PropertyKind.SETTER -> findMethodInHierarchy(phpClass, setterMethodName(propertyName))
            PropertyKind.FIELD -> findFieldInHierarchy(phpClass, propertyName)
            PropertyKind.PHPDOC -> findDocCommentWithProperty(phpClass, propertyName)
            PropertyKind.PHPDOC_READ -> findDocCommentWithPropertyRead(phpClass, propertyName, "@property-read")
            PropertyKind.PHPDOC_WRITE -> findDocCommentWithPropertyRead(phpClass, propertyName, "@property-write")
            PropertyKind.ATTRIBUTE -> findMethodInHierarchy(phpClass, "attributes")
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

    /**
     * Find the doc comment that actually contains the @property declaration,
     * not just any doc comment in the hierarchy.
     */
    private fun findDocCommentWithProperty(phpClass: PhpClass, propertyName: String): PsiElement? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val docComment = current.docComment
            if (docComment != null && docComment.text.contains("\$${propertyName}")) {
                return docComment
            }
            current = current.superClass
        }
        return null
    }

    private fun getterMethodName(propertyName: String): String {
        return "get" + propertyName.replaceFirstChar { it.uppercaseChar() }
    }

    private fun setterMethodName(propertyName: String): String {
        return "set" + propertyName.replaceFirstChar { it.uppercaseChar() }
    }

    /**
     * Find the doc comment that contains @property-read or @property-write declaration.
     */
    private fun findDocCommentWithPropertyRead(
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

    override fun getActionText(context: DataContext): String? = null
}
