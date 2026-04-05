package com.yii2storm.modelmagic.type

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.resolve.types.PhpType
import com.jetbrains.php.lang.psi.resolve.types.PhpTypeProvider4
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyTypeProvider : PhpTypeProvider4 {

    override fun getKey(): Char = key

    override fun getType(psiElement: PsiElement): PhpType? {
        val fieldReference = psiElement as? FieldReference ?: return null
        val propertyName = fieldReference.name ?: return null
        val project = psiElement.project
        val resolver = MagicPropertyResolver.getInstance(project)
        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(project, fieldReference)

        if (modelClasses.isEmpty()) {
            return null
        }

        val resultType = PhpType()
        modelClasses.forEach { phpClass ->
            resolver.getPropertyType(phpClass, propertyName)?.let(resultType::add)
        }

        if (resultType.isEmpty) {
            return null
        }

        return PhpType().add(buildSignature(resultType.types.toList()))
    }

    override fun complete(expression: String, project: Project): PhpType? {
        val resultType = PhpType()
        decodeSignature(expression).forEach(resultType::add)
        return resultType.takeUnless { it.isEmpty }
    }

    override fun getBySignature(
        expression: String,
        visited: MutableSet<String>,
        depth: Int,
        project: Project
    ): MutableCollection<out PhpNamedElement> {
        if (depth > maxDepth) {
            return mutableListOf()
        }

        return decodeSignature(expression)
            .filter { it.startsWith("\\") }
            .flatMap { fqn -> PhpIndex.getInstance(project).getClassesByFQN(fqn) }
            .filterIsInstance<PhpNamedElement>()
            .distinctBy { it.fqn }
            .toMutableList()
    }

    private fun buildSignature(types: List<String>): String {
        return buildString {
            append(key)
            append(signatureSeparator)
            append(types.joinToString(typeSeparator.toString()))
        }
    }

    private fun decodeSignature(expression: String): List<String> {
        val payload = expression.substringAfter(signatureSeparator, expression)
        return payload.split(typeSeparator)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    companion object {
        private const val key = '\u03A8'
        private const val maxDepth = 10
        private const val signatureSeparator = '\u00B7'
        private const val typeSeparator = '|'
    }
}
