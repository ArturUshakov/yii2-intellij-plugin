package com.yii2storm.modelmagic.annotator

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Custom text attributes for Yii2 model magic properties.
 * Uses method color to indicate that these properties are backed by methods (getters/setters).
 */
object MagicPropertyTextAttributes {
    val KEY: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "YII2_MODEL_MAGIC_PROPERTY",
        DefaultLanguageHighlighterColors.INSTANCE_METHOD
    )
}
