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

/**
 * Provides type inference for magic model properties.
 * Uses the signature-based caching mechanism of PhpTypeProvider4.
 *
 * The flow:
 * 1. getType() is called first — returns a signature string with our key
 * 2. complete() is called later — resolves the signature back to actual types
 * 3. getBySignature() is the fallback for signature resolution
 */
class ModelPropertyTypeProvider : PhpTypeProvider4 {

    override fun getKey(): Char = KEY

    override fun getType(psiElement: PsiElement): PhpType? {
        val fieldReference = psiElement as? FieldReference ?: return null
        val propertyName = fieldReference.name ?: return null
        val project = psiElement.project
        val resolver = ModelPropertyResolver.getInstance(project)
        val modelClasses = ModelPropertyPsiUtil.resolveModelClasses(project, fieldReference)

        if (modelClasses.isEmpty()) return null

        val resultType = PhpType()

        modelClasses.forEach { phpClass ->
            resolver.getPropertyType(phpClass, propertyName)?.let { propertyType ->
                resultType.add(propertyType)
            }
        }

        if (resultType.isEmpty) return null

        // Return a signature that will be resolved later by complete()
        val signature = StringBuilder().append(KEY).append(signatureSeparator).append(resultType.types.joinToString("|"))
        return PhpType().add(signature.toString())
    }

    override fun complete(expression: String, project: Project): PhpType? {
        // This is called to resolve our signature back to actual types
        val types = expression.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.startsWith("\\") }

        if (types.isEmpty()) return null

        val resultType = PhpType()
        types.forEach { resultType.add(it) }
        return resultType
    }

    override fun getBySignature(
        expression: String,
        visited: MutableSet<String>,
        depth: Int,
        project: Project
    ): MutableCollection<out PhpNamedElement> {
        if (depth > MAX_DEPTH) return mutableListOf()

        return expression.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.startsWith("\\") }
            .flatMap { fqn -> PhpIndex.getInstance(project).getClassesByFQN(fqn) }
            .filterIsInstance<PhpNamedElement>()
            .distinctBy { it.fqn }
            .toMutableList()
    }

    companion object {
        private const val KEY = '\u03A8'
        private const val MAX_DEPTH = 10
        private const val signatureSeparator = '\u00B7'
    }
}
