package com.yii2storm.modelmagic.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Unknown Yii2 model magic property"

    override fun getShortName(): String = "Yii2UnknownMagicProperty"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PhpElementVisitor() {
            override fun visitPhpFieldReference(fieldReference: FieldReference) {
                val propertyName = fieldReference.name ?: return
                if (propertyName.isBlank()) {
                    return
                }

                val project = fieldReference.project
                val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(project, fieldReference)
                if (modelClasses.isEmpty()) {
                    return
                }

                val resolver = MagicPropertyResolver.getInstance(project)
                if (modelClasses.none { resolver.hasProperty(it, propertyName) }) {
                    holder.registerProblem(fieldReference, "Unknown Yii2 model property '$propertyName'")
                }
            }
        }
    }
}
