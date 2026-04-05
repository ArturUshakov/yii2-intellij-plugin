package com.yii2storm.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.yii2storm.resolver.ModelPropertyResolver
import com.yii2storm.resolver.PropertyKind
import com.yii2storm.util.ModelPropertyPsiUtil

class ModelPropertyCompletionContributor : CompletionContributor() {

    init {
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
                    val project = fieldReference.project
                    val resolver = ModelPropertyResolver.getInstance(project)

                    val modelClasses = ModelPropertyPsiUtil.resolveModelClasses(project, fieldReference)
                    if (modelClasses.isEmpty()) return

                    val added = linkedSetOf<String>()

                    modelClasses.forEach { phpClass ->
                        resolver.getModelProperties(phpClass).forEach { property ->
                            if (added.add(property.name)) {
                                result.addElement(
                                    LookupElementBuilder
                                        .create(property.name)
                                        .withTypeText(property.type?.toString() ?: "", true)
                                        .withTailText(" ${kindLabel(property.kind)}", true)
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    private fun kindLabel(kind: PropertyKind): String = when (kind) {
        PropertyKind.RELATION -> "[relation]"
        PropertyKind.GETTER -> "[getter]"
        PropertyKind.SETTER -> "[setter]"
        PropertyKind.FIELD -> "[field]"
        PropertyKind.PHPDOC -> "[phpdoc]"
        PropertyKind.PHPDOC_READ -> "[phpdoc-read]"
        PropertyKind.PHPDOC_WRITE -> "[phpdoc-write]"
        PropertyKind.ATTRIBUTE -> "[attribute]"
    }
}
