package com.yii2storm.modelmagic.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val fieldReference = element.parentOfType<FieldReference>(withSelf = true) ?: return null
        val propertyName = fieldReference.name ?: return null
        if (element.text != propertyName) {
            return null
        }

        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)
        if (modelClasses.isEmpty()) {
            return null
        }

        val targets = collectTargets(modelClasses, propertyName, element.project)
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }

    private fun collectTargets(
        modelClasses: List<PhpClass>,
        propertyName: String,
        project: com.intellij.openapi.project.Project,
    ): List<PsiElement> {
        val resolver = MagicPropertyResolver.getInstance(project)

        return modelClasses
            .flatMap { resolver.getPropertyVariants(it, propertyName) }
            .map { it.source }
            .distinct()
    }
}
