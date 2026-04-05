package com.yii2storm.util

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.yii2storm.resolver.ModelPropertyResolver

object ModelPropertyPsiUtil {

    /**
     * Resolve all model classes from a field reference expression.
     * Returns only PhpClass instances that extend yii\db\ActiveRecord or yii\base\Model.
     */
    fun resolveModelClasses(project: Project, fieldReference: FieldReference): List<PhpClass> {
        val classReference = fieldReference.classReference ?: return emptyList()
        val phpIndex = PhpIndex.getInstance(project)
        val resolver = ModelPropertyResolver.getInstance(project)

        val candidateTypes = resolveTypeNames(classReference.type, phpIndex, project) +
                resolveTypeNames(classReference.globalType, phpIndex, project)

        return candidateTypes
            .distinct()
            .mapNotNull { fqn -> phpIndex.getClassesByFQN(fqn).firstOrNull() }
            .filter { phpClass -> resolver.isModelClass(phpClass) }
            .distinctBy { it.fqn }
    }

    /**
     * Check if the element is exactly the property name part of a field reference.
     * Used to avoid triggering on arrow operator or other parts of the expression.
     */
    fun isOnPropertyName(element: PsiElement, fieldReference: FieldReference): Boolean {
        val propertyName = fieldReference.name ?: return false
        return element.text == propertyName &&
                element.node?.elementType != PhpTokenTypes.ARROW
    }

    /**
     * Extract FQN type names from a PhpType, filtering out:
     * - pseudo-types starting with #
     * - blank strings
     * - non-FQN strings (not starting with \)
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
}
