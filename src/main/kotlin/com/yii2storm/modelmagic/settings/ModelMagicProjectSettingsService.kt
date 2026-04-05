package com.yii2storm.modelmagic.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.yii2storm.modelmagic.resolver.PropertyKind

@State(name = "Yii2ModelMagicProjectSettings", storages = [Storage("yii2-model-magic.xml")])
class ModelMagicProjectSettingsService : PersistentStateComponent<ModelMagicProjectSettingsState> {

    private var state = ModelMagicProjectSettingsState()

    override fun getState(): ModelMagicProjectSettingsState = state

    override fun loadState(state: ModelMagicProjectSettingsState) {
        this.state = state
    }

    fun isSourceEnabled(kind: PropertyKind): Boolean = when (kind) {
        PropertyKind.FIELD -> state.enableField
        PropertyKind.GETTER -> state.enableGetter
        PropertyKind.SETTER -> state.enableSetter
        PropertyKind.RELATION -> state.enableRelation
        PropertyKind.ATTRIBUTE -> state.enableAttribute
        PropertyKind.PHPDOC,
        PropertyKind.PHPDOC_READ,
        PropertyKind.PHPDOC_WRITE -> state.enablePhpDoc
    }

    fun resolutionPriority(kind: PropertyKind): Int = when (kind) {
        PropertyKind.GETTER -> state.resolutionGetterPriority
        PropertyKind.RELATION -> state.resolutionRelationPriority
        PropertyKind.SETTER -> state.resolutionSetterPriority
        PropertyKind.FIELD -> state.resolutionFieldPriority
        PropertyKind.ATTRIBUTE -> state.resolutionAttributePriority
        PropertyKind.PHPDOC,
        PropertyKind.PHPDOC_READ,
        PropertyKind.PHPDOC_WRITE -> state.resolutionPhpDocPriority
    }

    fun navigationPriority(kind: PropertyKind): Int = when (kind) {
        PropertyKind.GETTER -> state.navigationGetterPriority
        PropertyKind.RELATION -> state.navigationRelationPriority
        PropertyKind.SETTER -> state.navigationSetterPriority
        PropertyKind.FIELD -> state.navigationFieldPriority
        PropertyKind.ATTRIBUTE -> state.navigationAttributePriority
        PropertyKind.PHPDOC,
        PropertyKind.PHPDOC_READ,
        PropertyKind.PHPDOC_WRITE -> state.navigationPhpDocPriority
    }

    companion object {
        fun getInstance(project: Project): ModelMagicProjectSettingsService = project.service()
    }
}

data class ModelMagicProjectSettingsState(
    var enableField: Boolean = true,
    var enableGetter: Boolean = true,
    var enableSetter: Boolean = true,
    var enableRelation: Boolean = true,
    var enableAttribute: Boolean = true,
    var enablePhpDoc: Boolean = true,
    var resolutionFieldPriority: Int = 20,
    var resolutionGetterPriority: Int = 10,
    var resolutionRelationPriority: Int = 10,
    var resolutionSetterPriority: Int = 10,
    var resolutionAttributePriority: Int = 30,
    var resolutionPhpDocPriority: Int = 40,
    var navigationFieldPriority: Int = 20,
    var navigationGetterPriority: Int = 10,
    var navigationRelationPriority: Int = 10,
    var navigationSetterPriority: Int = 10,
    var navigationAttributePriority: Int = 30,
    var navigationPhpDocPriority: Int = 40,
)
