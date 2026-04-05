package com.yii2storm.reference

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
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.yii2storm.resolver.ModelPropertyResolver
import com.yii2storm.util.ModelPropertyPsiUtil

class ModelPropertyReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {
                    val fieldReference = element.parent as? FieldReference ?: return PsiReference.EMPTY_ARRAY
                    val propertyName = fieldReference.name ?: return PsiReference.EMPTY_ARRAY

                    if (!ModelPropertyPsiUtil.isOnPropertyName(element, fieldReference)) {
                        return PsiReference.EMPTY_ARRAY
                    }

                    if (!ModelPropertyPsiUtil.isMagicModelPropertyAccess(element.project, fieldReference)) {
                        return PsiReference.EMPTY_ARRAY
                    }

                    return arrayOf(
                        ModelPropertyPsiReference(
                            element = element,
                            fieldReference = fieldReference
                        )
                    )
                }
            }
        )
    }
}

private class ModelPropertyPsiReference(
    element: PsiElement,
    private val fieldReference: FieldReference
) : PsiPolyVariantReferenceBase<PsiElement>(element, TextRange(0, element.textLength)) {

    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        return results.firstOrNull()?.element
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val propertyName = fieldReference.name ?: return emptyArray()
        val resolver = ModelPropertyResolver.getInstance(element.project)
        val modelClasses = ModelPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)

        return modelClasses
            .mapNotNull { phpClass -> resolver.resolveProperty(phpClass, propertyName) }
            .distinct()
            .map { target -> PsiElementResolveResult(target) }
            .toTypedArray()
    }
}
