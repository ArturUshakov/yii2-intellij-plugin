package com.yii2storm.modelmagic.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.modelmagic.resolver.PropertyKind

class MagicPropertyGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val LOG = Logger.getInstance(MagicPropertyGotoDeclarationHandler::class.java)
    }

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

        val modelClasses = com.yii2storm.modelmagic.util.MagicPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)
        if (modelClasses.isEmpty()) {
            return null
        }

        val targets = collectTargets(modelClasses, propertyName)
        
        // Return null if no targets found (let other handlers take over)
        // Return array with elements if targets found - IDE will show choice popup
        return targets?.takeIf { it.isNotEmpty() }
    }

    private fun collectTargets(
        modelClasses: List<PhpClass>,
        propertyName: String
    ): Array<PsiElement>? {
        // Collect ALL possible targets from all model classes
        val targetsWithPriority = mutableListOf<Triple<PsiElement, Int, String>>()

        modelClasses.forEach { phpClass ->
            // Get all properties, but we need to collect ALL sources, not just the highest priority one
            val allProperties = collectAllPropertySources(phpClass, propertyName)
            
            allProperties.forEach { (psiElement, priority, label) ->
                targetsWithPriority.add(Triple(psiElement, priority, label))
            }
        }

        if (targetsWithPriority.isEmpty()) {
            LOG.debug("No targets found for property: $propertyName")
            return null
        }

        // Sort by priority (highest first) and return unique elements
        val sortedTargets = targetsWithPriority
            .sortedByDescending { it.second }
            .map { it.first }
            .fold(LinkedHashSet<PsiElement>()) { acc, element ->
                if (!acc.contains(element)) {
                    acc.add(element)
                }
                acc
            }
            .toTypedArray()

        LOG.debug("Returning ${sortedTargets.size} unique targets for: $propertyName")
        return sortedTargets.takeIf { it.isNotEmpty() }
    }

    /**
     * Collect ALL sources for a property (PHPDoc, getter, setter, relation, etc.)
     * Returns list of (PsiElement, priority, label)
     */
    private fun collectAllPropertySources(
        phpClass: PhpClass,
        propertyName: String
    ): List<Triple<PsiElement, Int, String>> {
        val results = mutableListOf<Triple<PsiElement, Int, String>>()

        // Check public field
        val field = findFieldInHierarchy(phpClass, propertyName)
        if (field != null) {
            results.add(Triple(field, getPriorityForKind(PropertyKind.FIELD), "Field"))
        }

        // Check getter (might be relation or regular getter)
        val getterName = getterMethodName(propertyName)
        val getter = findMethodInHierarchy(phpClass, getterName)
        if (getter != null) {
            // Check if it's a relation by looking for hasOne/hasMany in the method body
            val isRelation = containsRelationCall(getter)
            val kind = if (isRelation) PropertyKind.RELATION else PropertyKind.GETTER
            val label = if (isRelation) "Relation" else "Getter"

            results.add(Triple(getter, getPriorityForKind(kind), label))
        }

        // Check setter
        val setterName = setterMethodName(propertyName)
        val setter = findMethodInHierarchy(phpClass, setterName)
        if (setter != null) {
            results.add(Triple(setter, getPriorityForKind(PropertyKind.SETTER), "Setter"))
        }

        // Check attributes() method
        val attributesMethod = findMethodInHierarchy(phpClass, "attributes")
        if (attributesMethod != null) {
            // Check if property name is in attributes() return
            val attributesContent = attributesMethod.text
            if (attributesContent.contains("'$propertyName'") || attributesContent.contains("\"$propertyName\"")) {
                results.add(Triple(attributesMethod, getPriorityForKind(PropertyKind.ATTRIBUTE), "Attributes"))
            }
        }

        // Only add PHPDoc if no other code targets were found
        // This prevents showing the full PHPDoc comment when there's already a getter/setter/field
        if (results.isEmpty()) {
            // Check PHPDoc @property
            val phpDocProp = findPhpDocProperty(phpClass, propertyName, "@property")
            if (phpDocProp != null) {
                results.add(Triple(phpDocProp, getPriorityForKind(PropertyKind.PHPDOC), "@property"))
            }

            // Check PHPDoc @property-read
            val phpDocPropRead = findPhpDocProperty(phpClass, propertyName, "@property-read")
            if (phpDocPropRead != null) {
                results.add(Triple(phpDocPropRead, getPriorityForKind(PropertyKind.PHPDOC_READ), "@property-read"))
            }

            // Check PHPDoc @property-write
            val phpDocPropWrite = findPhpDocProperty(phpClass, propertyName, "@property-write")
            if (phpDocPropWrite != null) {
                results.add(Triple(phpDocPropWrite, getPriorityForKind(PropertyKind.PHPDOC_WRITE), "@property-write"))
            }
        }

        return results
    }

    /**
     * Check if a method contains hasOne/hasMany call (Yii2 relation).
     */
    private fun containsRelationCall(method: Method): Boolean {
        var found = false
        method.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (found) return
                if (element is com.jetbrains.php.lang.psi.elements.PhpReference) {
                    val name = element.name
                    if (name == "hasOne" || name == "hasMany") {
                        found = true
                        return
                    }
                }
                super.visitElement(element)
            }
        })
        return found
    }

    /**
     * Find PHPDoc @property annotation and return the doc comment PsiElement.
     */
    private fun findPhpDocProperty(
        phpClass: PhpClass,
        propertyName: String,
        annotationName: String
    ): PsiElement? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val docComment = current.docComment
            if (docComment != null) {
                val regex = Regex("$annotationName\\s+\\S+\\s+\\$${Regex.escape(propertyName)}")
                if (regex.containsMatchIn(docComment.text)) {
                    return docComment
                }
            }
            current = current.superClass
        }
        return null
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
