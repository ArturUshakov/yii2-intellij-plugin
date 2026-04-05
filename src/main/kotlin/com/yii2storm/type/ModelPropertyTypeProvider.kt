package com.yii2storm.type

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4
import com.yii2storm.resolver.ModelPropertyResolver
import com.yii2storm.util.ModelPropertyPsiUtil

class ModelPropertyTypeProvider : PhpTypeProvider4 {

    override fun getKey(): Char = '\u03A8'

    override fun getType(psiElement: PsiElement): PhpType? {
        val fieldReference = psiElement as? FieldReference ?: return null
        val propertyName = fieldReference.name ?: return null
        val project = psiElement.project
        val resolver = ModelPropertyResolver.getInstance(project)
        val modelClasses = ModelPropertyPsiUtil.resolveModelClasses(project, fieldReference)

        val resultType = PhpType()

        modelClasses.forEach { phpClass ->
            resolver.getPropertyType(phpClass, propertyName)?.let { propertyType ->
                resultType.add(propertyType)
            }
        }

        return if (resultType.isEmpty) null else resultType
    }

    override fun complete(expression: String, project: Project): PhpType? {
        return null
    }

    override fun getBySignature(
        expression: String,
        visited: MutableSet<String>,
        depth: Int,
        project: Project
    ): MutableCollection<out PhpNamedElement> {
        if (depth > 2) {
            return mutableListOf()
        }

        return expression.split("|")
            .flatMap { fqn -> PhpIndex.getInstance(project).getAnyByFQN(fqn) }
            .filterIsInstance<PhpNamedElement>()
            .distinctBy { element -> element.fqn }
            .toMutableList()
    }
}