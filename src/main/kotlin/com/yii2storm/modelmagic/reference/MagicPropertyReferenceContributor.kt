package com.yii2storm.modelmagic.reference

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.Field
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Register for field references ($model->property)
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement().withParent(FieldReference::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val fieldReference = element.parent as? FieldReference ?: return PsiReference.EMPTY_ARRAY
                    return createReferences(element, fieldReference)
                }
            }
        )
    }

    private fun createReferences(element: PsiElement, fieldReference: FieldReference): Array<PsiReference> {
        val propertyName = fieldReference.name ?: return PsiReference.EMPTY_ARRAY

        if (!MagicPropertyPsiUtil.isOnPropertyName(element, fieldReference)) {
            return PsiReference.EMPTY_ARRAY
        }

        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)
        if (modelClasses.isEmpty()) {
            return PsiReference.EMPTY_ARRAY
        }

        val resolver = MagicPropertyResolver.getInstance(element.project)
        val hasProperty = modelClasses.any { phpClass ->
            resolver.hasProperty(phpClass, propertyName)
        }

        if (!hasProperty) {
            return PsiReference.EMPTY_ARRAY
        }

        return arrayOf(
            MagicPropertyPsiReference(element, propertyName, modelClasses)
        )
    }
}

private class MagicPropertyPsiReference(
    element: PsiElement,
    private val propertyName: String,
    private val modelClasses: List<PhpClass>
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        return results.firstOrNull()?.element
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val allTargets = mutableListOf<PsiElement>()

        modelClasses.forEach { phpClass ->
            // Collect ALL sources for this property, not just the highest priority one
            allTargets.addAll(collectAllPropertyTargets(phpClass, propertyName))
        }

        return allTargets
            .distinct()
            .map { target -> PsiElementResolveResult(target) }
            .distinctBy { it.element }
            .toTypedArray()
    }

    /**
     * Collect ALL PSI elements that define this property:
     * - Getter method (getAge)
     * - Setter method (setAge)
     * - PHPDoc @property
     * - PHPDoc @property-read
     * - Public field
     * - attributes() method (if property is listed there)
     */
    private fun collectAllPropertyTargets(phpClass: PhpClass, propertyName: String): List<PsiElement> {
        val targets = mutableListOf<PsiElement>()
        val resolver = MagicPropertyResolver.getInstance(element.project)

        // Check getter method
        val getterName = resolver.propertyNameToGetter(propertyName)
        val getter = findMethodInHierarchy(phpClass, getterName)
        if (getter != null) targets.add(getter)

        // Check setter method
        val setterName = resolver.propertyNameToSetter(propertyName)
        val setter = findMethodInHierarchy(phpClass, setterName)
        if (setter != null) targets.add(setter)

        // Check public field
        val field = findFieldInHierarchy(phpClass, propertyName)
        if (field != null) targets.add(field)

        // Check attributes() method
        val attributesMethod = findAttributesMethod(phpClass)
        if (attributesMethod != null) {
            // Only add if property name is actually in the method body
            val text = attributesMethod.text
            if (text.contains("'$propertyName'") || text.contains("\"$propertyName\"")) {
                targets.add(attributesMethod)
            }
        }

        // Only add PHPDoc if no other code targets were found
        // This prevents showing the full PHPDoc comment when there's already a getter/setter/field
        if (targets.isEmpty()) {
            // Check PHPDoc @property
            val phpDoc = findPhpDocComment(phpClass, propertyName, "@property")
            if (phpDoc != null) targets.add(phpDoc)

            // Check PHPDoc @property-read
            val phpDocRead = findPhpDocComment(phpClass, propertyName, "@property-read")
            if (phpDocRead != null) targets.add(phpDocRead)

            // Check PHPDoc @property-write
            val phpDocWrite = findPhpDocComment(phpClass, propertyName, "@property-write")
            if (phpDocWrite != null) targets.add(phpDocWrite)
        }

        return targets
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

    private fun findPhpDocComment(phpClass: PhpClass, propertyName: String, annotationName: String): PsiElement? {
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

    private fun findAttributesMethod(phpClass: PhpClass): Method? {
        var current: PhpClass? = phpClass
        while (current != null) {
            val method = current.methods.firstOrNull { it.name == "attributes" && it.parameters.isEmpty() }
            if (method != null) return method
            current = current.superClass
        }
        return null
    }
}
