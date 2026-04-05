package com.yii2storm.modelmagic.reference

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
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
        if (modelClasses.none { resolver.hasProperty(it, propertyName) }) {
            return PsiReference.EMPTY_ARRAY
        }

        return arrayOf(MagicPropertyPsiReference(element, propertyName, modelClasses))
    }
}

private class MagicPropertyPsiReference(
    element: PsiElement,
    private val propertyName: String,
    private val modelClasses: List<PhpClass>
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        return multiResolve(false).firstOrNull()?.element
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val resolver = MagicPropertyResolver.getInstance(element.project)

        return modelClasses
            .flatMap { phpClass -> resolver.getPropertyTargets(phpClass, propertyName) }
            .sortedByDescending { it.kind.priority }
            .map { target -> PsiElementResolveResult(target.element) }
            .distinctBy { it.element }
            .toTypedArray()
    }
}
