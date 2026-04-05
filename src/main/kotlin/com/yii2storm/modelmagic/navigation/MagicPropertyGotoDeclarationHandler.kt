package com.yii2storm.modelmagic.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val fieldReference = element.parentOfType<FieldReference>(withSelf = true) ?: return null
        val propertyName = fieldReference.name ?: return null

        if (!MagicPropertyPsiUtil.isOnPropertyName(element, fieldReference)) {
            return null
        }

        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(element.project, fieldReference)
        if (modelClasses.isEmpty()) {
            return null
        }

        val resolver = MagicPropertyResolver.getInstance(element.project)
        val targets = modelClasses
            .flatMap { phpClass -> resolver.getPropertyTargets(phpClass, propertyName) }
            .sortedByDescending { it.kind.priority }
            .map { it.element }
            .distinct()
            .toTypedArray()

        return targets.takeIf { it.isNotEmpty() }
    }

    override fun getActionText(context: DataContext): String? = null
}
