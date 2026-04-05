package com.yii2storm.modelmagic.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
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
                val hasProperty = modelClasses.any { phpClass ->
                    resolver.hasProperty(phpClass, propertyName)
                }

                if (!hasProperty) {
                    holder.registerProblem(
                        fieldReference,
                        "Unknown Yii2 model property '$propertyName'",
                        AddPropertyQuickFix(propertyName),
                        CreateGetterQuickFix(propertyName),
                        CreateSetterQuickFix(propertyName)
                    )
                }
            }
        }
    }
}

/**
 * Quick fix: Add @property annotation to the model class.
 */
private class AddPropertyQuickFix(private val propertyName: String) : LocalQuickFix {
    override fun getName(): String = "Add @property annotation for '$propertyName'"

    override fun getFamilyName(): String = "Yii2 Model Magic Property Fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val fieldReference = descriptor.psiElement.parentOfType<FieldReference>() ?: return
        val modelClass = findModelClass(fieldReference) ?: return

        // For now, log a message - full PHPDoc creation requires more complex PSI manipulation
        LOG.info("Add @property annotation for: $propertyName")
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(AddPropertyQuickFix::class.java)
    }
}

/**
 * Quick fix: Create getter method for the property.
 */
private class CreateGetterQuickFix(private val propertyName: String) : LocalQuickFix {
    
    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(CreateGetterQuickFix::class.java)
    }
    
    override fun getName(): String = "Create getter method 'get${propertyName.replaceFirstChar { it.uppercaseChar() }}'"

    override fun getFamilyName(): String = "Yii2 Model Magic Property Fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val fieldReference = descriptor.psiElement.parentOfType<FieldReference>() ?: return
        val modelClass = findModelClass(fieldReference) ?: return

        val getterName = "get${propertyName.replaceFirstChar { it.uppercaseChar() }}"
        
        // Check if method already exists
        if (modelClass.methods.any { it.name == getterName }) {
            return
        }

        // Log for now - actual method creation requires complex PSI manipulation
        LOG.info("Create getter method: $getterName")
    }
}

/**
 * Quick fix: Create setter method for the property.
 */
private class CreateSetterQuickFix(private val propertyName: String) : LocalQuickFix {
    
    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(CreateSetterQuickFix::class.java)
    }
    
    override fun getName(): String = "Create setter method 'set${propertyName.replaceFirstChar { it.uppercaseChar() }}'"

    override fun getFamilyName(): String = "Yii2 Model Magic Property Fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val fieldReference = descriptor.psiElement.parentOfType<FieldReference>() ?: return
        val modelClass = findModelClass(fieldReference) ?: return

        val setterName = "set${propertyName.replaceFirstChar { it.uppercaseChar() }}"
        
        // Check if method already exists
        if (modelClass.methods.any { it.name == setterName }) {
            return
        }

        // Log for now - actual method creation requires complex PSI manipulation
        LOG.info("Create setter method: $setterName")
    }
}

/**
 * Find the model class that contains the field reference.
 */
private fun findModelClass(fieldReference: FieldReference): PhpClass? {
    return fieldReference.parentOfType<PhpClass>()
}
