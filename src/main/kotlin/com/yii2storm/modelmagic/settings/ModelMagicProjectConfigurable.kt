package com.yii2storm.modelmagic.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class ModelMagicProjectConfigurable(
    private val project: Project,
) : Configurable {

    private var panel: JPanel? = null
    private lateinit var fieldCheckBox: JCheckBox
    private lateinit var getterCheckBox: JCheckBox
    private lateinit var relationCheckBox: JCheckBox
    private lateinit var setterCheckBox: JCheckBox
    private lateinit var attributeCheckBox: JCheckBox
    private lateinit var phpDocCheckBox: JCheckBox
    private lateinit var resolutionFieldSpinner: JSpinner
    private lateinit var resolutionGetterSpinner: JSpinner
    private lateinit var resolutionRelationSpinner: JSpinner
    private lateinit var resolutionSetterSpinner: JSpinner
    private lateinit var resolutionAttributeSpinner: JSpinner
    private lateinit var resolutionPhpDocSpinner: JSpinner
    private lateinit var navigationFieldSpinner: JSpinner
    private lateinit var navigationGetterSpinner: JSpinner
    private lateinit var navigationRelationSpinner: JSpinner
    private lateinit var navigationSetterSpinner: JSpinner
    private lateinit var navigationAttributeSpinner: JSpinner
    private lateinit var navigationPhpDocSpinner: JSpinner

    override fun getDisplayName(): String = "Yii2 Model Magic"

    override fun createComponent(): JComponent {
        if (panel != null) {
            return panel as JPanel
        }

        fieldCheckBox = JCheckBox("Field")
        getterCheckBox = JCheckBox("Getter")
        relationCheckBox = JCheckBox("Relation")
        setterCheckBox = JCheckBox("Setter")
        attributeCheckBox = JCheckBox("Attribute")
        phpDocCheckBox = JCheckBox("PHPDoc")
        resolutionFieldSpinner = createPrioritySpinner()
        resolutionGetterSpinner = createPrioritySpinner()
        resolutionRelationSpinner = createPrioritySpinner()
        resolutionSetterSpinner = createPrioritySpinner()
        resolutionAttributeSpinner = createPrioritySpinner()
        resolutionPhpDocSpinner = createPrioritySpinner()
        navigationFieldSpinner = createPrioritySpinner()
        navigationGetterSpinner = createPrioritySpinner()
        navigationRelationSpinner = createPrioritySpinner()
        navigationSetterSpinner = createPrioritySpinner()
        navigationAttributeSpinner = createPrioritySpinner()
        navigationPhpDocSpinner = createPrioritySpinner()

        panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            insets = Insets(4, 8, 4, 8)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0
        addHeader(panel as JPanel, constraints, row++, "Source", "Use in search", "Resolve", "Navigate")
        addSourceRow(panel as JPanel, constraints, row++, "Field", fieldCheckBox, resolutionFieldSpinner, navigationFieldSpinner)
        addSourceRow(panel as JPanel, constraints, row++, "Getter", getterCheckBox, resolutionGetterSpinner, navigationGetterSpinner)
        addSourceRow(panel as JPanel, constraints, row++, "Relation", relationCheckBox, resolutionRelationSpinner, navigationRelationSpinner)
        addSourceRow(panel as JPanel, constraints, row++, "Setter", setterCheckBox, resolutionSetterSpinner, navigationSetterSpinner)
        addSourceRow(panel as JPanel, constraints, row++, "Attribute", attributeCheckBox, resolutionAttributeSpinner, navigationAttributeSpinner)
        addSourceRow(panel as JPanel, constraints, row++, "PHPDoc", phpDocCheckBox, resolutionPhpDocSpinner, navigationPhpDocSpinner)

        constraints.gridx = 0
        constraints.gridy = row
        constraints.weightx = 1.0
        constraints.weighty = 1.0
        constraints.gridwidth = 4
        panel!!.add(JPanel(), constraints)

        reset()
        return panel as JPanel
    }

    override fun isModified(): Boolean {
        val state = ModelMagicProjectSettingsService.getInstance(project).state

        return fieldCheckBox.isSelected != state.enableField ||
            getterCheckBox.isSelected != state.enableGetter ||
            relationCheckBox.isSelected != state.enableRelation ||
            setterCheckBox.isSelected != state.enableSetter ||
            attributeCheckBox.isSelected != state.enableAttribute ||
            phpDocCheckBox.isSelected != state.enablePhpDoc ||
            resolutionFieldSpinner.value as Int != state.resolutionFieldPriority ||
            resolutionGetterSpinner.value as Int != state.resolutionGetterPriority ||
            resolutionRelationSpinner.value as Int != state.resolutionRelationPriority ||
            resolutionSetterSpinner.value as Int != state.resolutionSetterPriority ||
            resolutionAttributeSpinner.value as Int != state.resolutionAttributePriority ||
            resolutionPhpDocSpinner.value as Int != state.resolutionPhpDocPriority ||
            navigationFieldSpinner.value as Int != state.navigationFieldPriority ||
            navigationGetterSpinner.value as Int != state.navigationGetterPriority ||
            navigationRelationSpinner.value as Int != state.navigationRelationPriority ||
            navigationSetterSpinner.value as Int != state.navigationSetterPriority ||
            navigationAttributeSpinner.value as Int != state.navigationAttributePriority ||
            navigationPhpDocSpinner.value as Int != state.navigationPhpDocPriority
    }

    override fun apply() {
        val service = ModelMagicProjectSettingsService.getInstance(project)
        val state = service.state

        state.enableField = fieldCheckBox.isSelected
        state.enableGetter = getterCheckBox.isSelected
        state.enableRelation = relationCheckBox.isSelected
        state.enableSetter = setterCheckBox.isSelected
        state.enableAttribute = attributeCheckBox.isSelected
        state.enablePhpDoc = phpDocCheckBox.isSelected
        state.resolutionFieldPriority = resolutionFieldSpinner.value as Int
        state.resolutionGetterPriority = resolutionGetterSpinner.value as Int
        state.resolutionRelationPriority = resolutionRelationSpinner.value as Int
        state.resolutionSetterPriority = resolutionSetterSpinner.value as Int
        state.resolutionAttributePriority = resolutionAttributeSpinner.value as Int
        state.resolutionPhpDocPriority = resolutionPhpDocSpinner.value as Int
        state.navigationFieldPriority = navigationFieldSpinner.value as Int
        state.navigationGetterPriority = navigationGetterSpinner.value as Int
        state.navigationRelationPriority = navigationRelationSpinner.value as Int
        state.navigationSetterPriority = navigationSetterSpinner.value as Int
        state.navigationAttributePriority = navigationAttributeSpinner.value as Int
        state.navigationPhpDocPriority = navigationPhpDocSpinner.value as Int
    }

    override fun reset() {
        val state = ModelMagicProjectSettingsService.getInstance(project).state

        fieldCheckBox.isSelected = state.enableField
        getterCheckBox.isSelected = state.enableGetter
        relationCheckBox.isSelected = state.enableRelation
        setterCheckBox.isSelected = state.enableSetter
        attributeCheckBox.isSelected = state.enableAttribute
        phpDocCheckBox.isSelected = state.enablePhpDoc
        resolutionFieldSpinner.value = state.resolutionFieldPriority
        resolutionGetterSpinner.value = state.resolutionGetterPriority
        resolutionRelationSpinner.value = state.resolutionRelationPriority
        resolutionSetterSpinner.value = state.resolutionSetterPriority
        resolutionAttributeSpinner.value = state.resolutionAttributePriority
        resolutionPhpDocSpinner.value = state.resolutionPhpDocPriority
        navigationFieldSpinner.value = state.navigationFieldPriority
        navigationGetterSpinner.value = state.navigationGetterPriority
        navigationRelationSpinner.value = state.navigationRelationPriority
        navigationSetterSpinner.value = state.navigationSetterPriority
        navigationAttributeSpinner.value = state.navigationAttributePriority
        navigationPhpDocSpinner.value = state.navigationPhpDocPriority
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun createPrioritySpinner(): JSpinner = JSpinner(SpinnerNumberModel(10, 0, 999, 1))

    private fun addHeader(
        panel: JPanel,
        constraints: GridBagConstraints,
        row: Int,
        sourceLabel: String,
        enabledLabel: String,
        resolutionLabel: String,
        navigationLabel: String,
    ) {
        addCell(panel, constraints, row, 0, JLabel(sourceLabel))
        addCell(panel, constraints, row, 1, JLabel(enabledLabel))
        addCell(panel, constraints, row, 2, JLabel(resolutionLabel))
        addCell(panel, constraints, row, 3, JLabel(navigationLabel))
    }

    private fun addSourceRow(
        panel: JPanel,
        constraints: GridBagConstraints,
        row: Int,
        name: String,
        enabledComponent: JCheckBox,
        resolutionComponent: JSpinner,
        navigationComponent: JSpinner,
    ) {
        addCell(panel, constraints, row, 0, JLabel(name))
        addCell(panel, constraints, row, 1, enabledComponent)
        addCell(panel, constraints, row, 2, resolutionComponent)
        addCell(panel, constraints, row, 3, navigationComponent)
    }

    private fun addCell(
        panel: JPanel,
        constraints: GridBagConstraints,
        row: Int,
        column: Int,
        component: JComponent,
    ) {
        val copy = constraints.clone() as GridBagConstraints
        copy.gridx = column
        copy.gridy = row
        copy.weightx = if (column == 0) 1.0 else 0.0
        panel.add(component, copy)
    }
}
