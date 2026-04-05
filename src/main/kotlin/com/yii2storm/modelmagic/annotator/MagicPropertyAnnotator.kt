package com.yii2storm.modelmagic.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.jetbrains.php.lang.psi.elements.FieldReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.yii2storm.modelmagic.resolver.MagicPropertyResolver
import com.yii2storm.modelmagic.resolver.PropertyKind
import com.yii2storm.modelmagic.util.MagicPropertyPsiUtil

/**
 * Annotates Yii2 model magic properties.
 * Only annotates properties that have a getter, setter, relation, or attribute
 * (excludes plain PHPDoc-only properties).
 */
class MagicPropertyAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only annotate field references: $model->property
        val fieldReference = element.parentOfType<FieldReference>() ?: return

        // Make sure the element being annotated is the property name itself
        val propertyName = fieldReference.name ?: return
        if (element.text != propertyName) return

        val project = element.project
        val modelClasses = MagicPropertyPsiUtil.resolveModelClasses(project, fieldReference)
        if (modelClasses.isEmpty()) return

        val resolver = MagicPropertyResolver.getInstance(project)

        // Check if any model class has this property with a non-PHPDoc kind
        val hasMagicProperty = modelClasses.any { phpClass ->
            resolver.getModelProperties(phpClass)
                .filter { it.name == propertyName }
                .any { property ->
                    // Only annotate if there's a getter, setter, relation, or attribute
                    // Exclude plain PHPDoc-only properties
                    property.kind in listOf(
                        PropertyKind.GETTER,
                        PropertyKind.SETTER,
                        PropertyKind.RELATION,
                        PropertyKind.ATTRIBUTE
                    )
                }
        }

        if (hasMagicProperty) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .create()
        }
    }
}
