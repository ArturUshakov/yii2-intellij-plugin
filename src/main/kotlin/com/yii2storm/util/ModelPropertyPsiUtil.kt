package com.yii2storm.util

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.yii2storm.resolver.ModelPropertyResolver

object ModelPropertyPsiUtil {

    fun resolveModelClasses(project: Project, fieldReference: FieldReference): List<PhpClass> {
        val classReference = fieldReference.classReference ?: return emptyList()
        val phpIndex = PhpIndex.getInstance(project)
        val resolver = ModelPropertyResolver.getInstance(project)

        val candidateTypes = linkedSetOf<String>()

        collectTypeNames(classReference.type, phpIndex, project).forEach(candidateTypes::add)
        collectTypeNames(classReference.globalType, phpIndex, project).forEach(candidateTypes::add)

        return candidateTypes
            .flatMap { fqn -> phpIndex.getAnyByFQN(fqn) }
            .filterIsInstance<PhpClass>()
            .filter { phpClass ->
                !isEnumClass(phpClass) && resolver.isModelClass(phpClass)
            }
            .distinctBy { phpClass -> phpClass.fqn }
    }

    fun isMagicModelPropertyAccess(project: Project, fieldReference: FieldReference): Boolean {
        val propertyName = fieldReference.name ?: return false
        if (propertyName.isBlank()) {
            return false
        }

        return resolveModelClasses(project, fieldReference).isNotEmpty()
    }

    fun isOnPropertyName(element: PsiElement, fieldReference: FieldReference): Boolean {
        val propertyName = fieldReference.name ?: return false
        return element.text == propertyName
    }

    private fun isEnumClass(phpClass: PhpClass): Boolean {
        return phpClass.isEnum
    }

    private fun collectTypeNames(type: PhpType?, phpIndex: PhpIndex, project: Project): List<String> {
        if (type == null || type.isEmpty) {
            return emptyList()
        }

        return phpIndex.completeType(project, type, HashSet())
            .types
            .filter { typeName ->
                typeName.isNotBlank() &&
                    !typeName.startsWith("#") &&
                    typeName.startsWith("\\")
            }
    }
}
