package com.yii2storm.modelmagic.util

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver

object MagicPropertyPsiUtil {

    /**
     * Resolve all model classes from a field reference expression.
     */
    fun resolveModelClasses(project: Project, fieldReference: FieldReference): List<PhpClass> {
        val classReference = fieldReference.classReference ?: return emptyList()
        return resolveClassesFromType(classReference.type, project) +
                resolveClassesFromType(classReference.globalType, project)
    }

    /**
     * Check if the element is exactly the property name part of a field reference.
     */
    fun isOnPropertyName(element: PsiElement, fieldReference: FieldReference): Boolean {
        val propertyName = fieldReference.name ?: return false
        return element.text == propertyName &&
                element.node?.elementType != PhpTokenTypes.ARROW
    }

    /**
     * Extract FQN type names from a PhpType.
     */
    private fun resolveTypeNames(type: PhpType?, phpIndex: PhpIndex, project: Project): List<String> {
        if (type == null || type.isEmpty) return emptyList()

        return phpIndex.completeType(project, type, mutableSetOf())
            .types
            .filter { typeName ->
                typeName.isNotBlank() &&
                        !typeName.startsWith("#") &&
                        typeName.startsWith("\\")
            }
    }

    /**
     * Resolve classes from a PhpType, filtering to only model classes.
     */
    private fun resolveClassesFromType(type: PhpType?, project: Project): List<PhpClass> {
        if (type == null || type.isEmpty) return emptyList()

        val phpIndex = PhpIndex.getInstance(project)
        val resolver = MagicPropertyResolver.getInstance(project)

        val candidateTypes = resolveTypeNames(type, phpIndex, project)

        return candidateTypes
            .distinct()
            .mapNotNull { fqn -> phpIndex.getClassesByFQN(fqn).firstOrNull() }
            .filter { phpClass -> resolver.isModelClass(phpClass) }
            .distinctBy { it.fqn }
    }
}
