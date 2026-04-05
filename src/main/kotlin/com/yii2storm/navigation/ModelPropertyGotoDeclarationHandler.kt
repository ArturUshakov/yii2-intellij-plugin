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

        val targets = linkedSetOf<PsiElement>()

        modelClasses.forEach { phpClass ->
            resolver.getModelProperties(phpClass)
                .filter { it.name == propertyName }
                .forEach { property ->
                    val target = resolvePropertyTarget(phpClass, property, propertyName)
                    if (target != null) {
                        targets.add(target)
                    }
                }
        }

        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    /**
     * Resolve to the most meaningful PSI element for the property:
     * - RELATION: the getter method that defines the relation
     * - GETTER: the getter method
     * - FIELD: the field declaration itself
     * - PHPDOC: the @property line in the doc comment of the class that declares it
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
            PropertyKind.FIELD -> findFieldInHierarchy(phpClass, propertyName)
            PropertyKind.PHPDOC -> findDocCommentWithProperty(phpClass, propertyName)
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

    override fun getActionText(context: DataContext): String? = null
}
