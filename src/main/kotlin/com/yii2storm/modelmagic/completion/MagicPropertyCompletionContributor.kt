package com.yii2storm.modelmagic.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.yii2storm.modelmagic.resolver.MagicProperty
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.resolver.PropertyKind
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

class MagicPropertyCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withParent(FieldReference::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    val fieldReference = parameters.position.parent as? FieldReference ?: return
                    addPropertyCompletions(fieldReference, result)
                }
            },
        )
    }

    private fun addPropertyCompletions(
        fieldReference: FieldReference,
        result: CompletionResultSet,
    ) {
        val project = fieldReference.project
        val resolver = MagicPropertyResolver.getInstance(project)
        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(project, fieldReference)
        if (modelClasses.isEmpty()) {
            return
        }

        val added = linkedSetOf<String>()

        modelClasses.forEach { phpClass ->
            resolver.getModelProperties(phpClass).forEach { property ->
                if (added.add(property.name)) {
                    result.addElement(createLookupElement(property))
                }
            }
        }
    }

    private fun createLookupElement(property: MagicProperty): LookupElementBuilder {
        val typeText = readableTypeText(property.type)
        val builder = LookupElementBuilder
            .create(property.name)
            .withIcon(iconForKind(property.kind))
            .withTailText(tailTextForKind(property.kind), true)

        return if (typeText.isBlank()) {
            builder
        } else {
            builder.withTypeText(typeText, true)
        }
    }

    private fun readableTypeText(type: com.jetbrains.php.lang.psi.resolve.types.PhpType?): String {
        if (type == null || type.isEmpty) {
            return ""
        }

        val primitiveTypes = linkedSetOf<String>()
        val classTypes = linkedSetOf<String>()

        type.types.forEach { raw ->
            when (val normalized = normalizeTypePart(raw)) {
                null -> Unit
                "int", "string", "bool", "float", "array", "mixed", "object", "callable", "iterable", "void", "null", "false", "true" -> {
                    primitiveTypes.add(normalized)
                }
                else -> classTypes.add(normalized)
            }
        }

        val result = (primitiveTypes + classTypes).toList()
        return result.joinToString("|")
    }

    private fun normalizeTypePart(raw: String): String? {
        val part = raw.trim()
        if (part.isBlank()) {
            return null
        }

        val directPrimitive = when (part) {
            "int", "string", "bool", "float", "array", "mixed", "object", "callable", "iterable", "void", "null", "false", "true" -> part
            else -> null
        }
        if (directPrimitive != null) {
            return directPrimitive
        }

        if (part.endsWith("[]")) {
            val base = normalizeTypePart(part.removeSuffix("[]")) ?: return "array"
            return "$base[]"
        }

        if (part.contains("#C\\")) {
            return part.substringAfter("#C\\").substringAfterLast("\\").takeIf { it.isNotBlank() }
        }

        if (part.startsWith("\\")) {
            return part.substringAfterLast("\\").takeIf { it.isNotBlank() }
        }

        if (part.contains("\\") && !part.contains("#")) {
            return part.substringAfterLast("\\").takeIf { it.isNotBlank() }
        }

        return null
    }

    private fun iconForKind(kind: PropertyKind) = when (kind) {
        PropertyKind.RELATION -> AllIcons.Nodes.Method
        PropertyKind.GETTER -> AllIcons.Nodes.Method
        PropertyKind.SETTER -> AllIcons.Nodes.Method
        PropertyKind.FIELD -> AllIcons.Nodes.Field
        PropertyKind.PHPDOC,
        PropertyKind.PHPDOC_READ,
        PropertyKind.PHPDOC_WRITE -> AllIcons.Nodes.Property
        PropertyKind.ATTRIBUTE -> AllIcons.Nodes.Parameter
    }

    private fun tailTextForKind(kind: PropertyKind): String = when (kind) {
        PropertyKind.RELATION -> " (relation)"
        PropertyKind.GETTER -> " (getter)"
        PropertyKind.SETTER -> " (setter)"
        PropertyKind.FIELD -> ""
        PropertyKind.PHPDOC -> " (@property)"
        PropertyKind.PHPDOC_READ -> " (@property-read)"
        PropertyKind.PHPDOC_WRITE -> " (@property-write)"
        PropertyKind.ATTRIBUTE -> " (attribute)"
    }
}
