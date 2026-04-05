package com.yii2storm.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.visitors.PhpElementVisitor
import com.yii2storm.resolver.ModelPropertyResolver
import com.yii2storm.util.ModelPropertyPsiUtil

class ModelPropertyInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "Unknown Yii2 model magic property"

    override fun getShortName(): String = "Yii2UnknownModelProperty"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PhpElementVisitor() {
            override fun visitPhpFieldReference(fieldReference: FieldReference) {
                val propertyName = fieldReference.name ?: return
                if (propertyName.isBlank()) {
                    return
                }

                val project = fieldReference.project
                val modelClasses = ModelPropertyPsiUtil.resolveModelClasses(project, fieldReference)
                if (modelClasses.isEmpty()) {
                    return
                }

                val resolver = ModelPropertyResolver.getInstance(project)
                val hasProperty = modelClasses.any { phpClass ->
                    resolver.hasProperty(phpClass, propertyName)
                }

                if (!hasProperty) {
                    holder.registerProblem(
                        fieldReference,
                        "Unknown Yii2 model property '$propertyName'"
                    )
                }
            }
        }
    }
}