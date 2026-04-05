package com.yii2storm.modelmagic.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.resolver.PropertyKind
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil
import com.intellij.icons.AllIcons

class MagicPropertyCompletionContributor : CompletionContributor() {

    init {
        // Completion for $model->property
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withParent(FieldReference::class.java),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val fieldReference = parameters.position.parent as? FieldReference ?: return
                    addPropertyCompletions(fieldReference, result)
                }
            }
        )
    }

    private fun addPropertyCompletions(
        fieldReference: FieldReference,
        result: CompletionResultSet
    ) {
        val project = fieldReference.project
        val resolver = MagicPropertyResolver.getInstance(project)

        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(project, fieldReference)
        if (modelClasses.isEmpty()) return

        val added = linkedSetOf<String>()

        modelClasses.forEach { phpClass ->
            resolver.getModelProperties(phpClass).forEach { property ->
                if (added.add(property.name)) {
                    val lookupElement = createLookupElement(property)
                    result.addElement(lookupElement)
                }
            }
        }
    }

    private fun createLookupElement(property: com.yii2storm.modelmagic.resolver.MagicProperty): LookupElementBuilder {
        val icon = getIconForKind(property.kind)
        val typeText = property.type?.toString() ?: ""
        val tailText = getKindTailText(property.kind)

        return LookupElementBuilder
            .create(property.name)
            .withIcon(icon)
            .withTypeText(typeText, true)
            .withTailText(tailText, true)
    }

    private fun getIconForKind(kind: PropertyKind): javax.swing.Icon = when (kind) {
        PropertyKind.RELATION -> AllIcons.Nodes.Method // Relation method
        PropertyKind.GETTER -> AllIcons.Nodes.Method // Getter method
        PropertyKind.SETTER -> AllIcons.Nodes.Method // Setter method
        PropertyKind.FIELD -> AllIcons.Nodes.Field // Public field
        PropertyKind.PHPDOC -> AllIcons.Nodes.Property // PHPDoc property
        PropertyKind.PHPDOC_READ -> AllIcons.Nodes.Property // Read-only property
        PropertyKind.PHPDOC_WRITE -> AllIcons.Nodes.Property // Write-only property
        PropertyKind.ATTRIBUTE -> AllIcons.Nodes.Parameter // Attribute/column
    }

    private fun getKindTailText(kind: PropertyKind): String = when (kind) {
        PropertyKind.RELATION -> " (relation)"
        PropertyKind.GETTER -> " (getter)"
        PropertyKind.SETTER -> " (setter)"
        PropertyKind.FIELD -> " (field)"
        PropertyKind.PHPDOC -> " (@property)"
        PropertyKind.PHPDOC_READ -> " (@property-read)"
        PropertyKind.PHPDOC_WRITE -> " (@property-write)"
        PropertyKind.ATTRIBUTE -> " (attribute)"
    }
}
