/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.intellij.plugin.asm.ui.runsettings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.fields.IntegerField
import com.intellij.util.ui.JBUI
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import io.shamash.intellij.plugin.asm.registry.AsmRuleRegistryProviders
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class ShamashAsmRunSettingsPanel(
    private val project: Project,
) : Disposable {
    private val root = JBPanel<JBPanel<*>>(BorderLayout())

    private val registryCombo = ComboBox<RegistryChoice>()

    private val applyOverrides = JBCheckBox("Apply overrides")
    private val showAdvanced = JBCheckBox("Show advanced telemetry")

    private val scopeCombo = ComboBox(ScopeChoice.entries.toTypedArray())
    private val followSymlinksCombo = ComboBox(BoolChoice.entries.toTypedArray())

    private val maxClassesField = intField("", min = 1)
    private val maxJarBytesField = intField("", min = 1)
    private val maxClassBytesField = intField("", min = 1)

    private val hint = JBLabel().apply { foreground = JBColor.GRAY }

    init {
        root.border = JBUI.Borders.empty(10)

        val stack =
            JBPanel<JBPanel<*>>().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

        stack.add(sectionHeader("Engine"))
        stack.add(Box.createVerticalStrut(JBUI.scale(6)))
        stack.add(row("Registry", registryCombo))
        stack.add(Box.createVerticalStrut(JBUI.scale(10)))

        stack.add(sectionHeader("Run Overrides"))
        stack.add(Box.createVerticalStrut(JBUI.scale(6)))
        stack.add(row("", applyOverrides))
        stack.add(Box.createVerticalStrut(JBUI.scale(6)))
        stack.add(row("Scope", scopeCombo))
        stack.add(row("Follow symlinks", followSymlinksCombo))
        stack.add(row("Max classes", maxClassesField))
        stack.add(row("Max jar bytes", maxJarBytesField))
        stack.add(row("Max class bytes", maxClassBytesField))
        stack.add(Box.createVerticalStrut(JBUI.scale(10)))
        stack.add(sectionHeader("UI"))
        stack.add(Box.createVerticalStrut(JBUI.scale(6)))
        stack.add(row("", showAdvanced))
        stack.add(Box.createVerticalStrut(JBUI.scale(10)))
        stack.add(hint.apply { alignmentX = JComponent.LEFT_ALIGNMENT })

        root.add(stack, BorderLayout.NORTH)

        rebuildRegistryChoices()

        bind()
        ShamashAsmUiStateService.getInstance(project).addListener(this) { refresh() }
        refresh()
    }

    fun component(): JComponent = root

    fun refresh() {
        if (project.isDisposed) return

        val settings = ShamashAsmSettingsState.getInstance(project)

        selectRegistry(settings.getRegistryId())

        applyOverrides.isSelected = settings.isApplyOverrides()
        showAdvanced.isSelected = settings.isShowAdvancedTelemetry()

        scopeCombo.selectedItem = ScopeChoice.from(settings.getOverrideScope())
        followSymlinksCombo.selectedItem = BoolChoice.from(settings.getOverrideFollowSymlinks())

        setNullableInt(maxClassesField, settings.getOverrideMaxClasses())
        setNullableInt(maxJarBytesField, settings.getOverrideMaxJarBytes())
        setNullableInt(maxClassBytesField, settings.getOverrideMaxClassBytes())

        setEnabledByApplyToggle()

        val applied =
            ShamashAsmUiStateService
                .getInstance(project)
                .getState()
                ?.scanResult
                ?.appliedOverrides
                ?.scan
        hint.text =
            when {
                applied == null -> "Applied overrides: (none)"
                else -> "Applied overrides: ${formatApplied(applied)}"
            }
    }

    override fun dispose() {
        // listener auto-unregistered by state service
    }

    private fun bind() {
        registryCombo.addActionListener {
            val choice = registryCombo.selectedItem as? RegistryChoice ?: return@addActionListener
            ShamashAsmSettingsState.getInstance(project).setRegistryId(choice.id)
        }

        applyOverrides.addActionListener {
            val settings = ShamashAsmSettingsState.getInstance(project)
            settings.setApplyOverrides(applyOverrides.isSelected)
            setEnabledByApplyToggle()
        }

        showAdvanced.addActionListener {
            ShamashAsmSettingsState.getInstance(project).setShowAdvancedTelemetry(showAdvanced.isSelected)
            // dashboard reacts on next refresh via its own listener; this is immediate persistence.
        }

        scopeCombo.addActionListener {
            val choice = (scopeCombo.selectedItem as? ScopeChoice) ?: return@addActionListener
            ShamashAsmSettingsState.getInstance(project).setOverrideScope(choice.toScopeOrNull())
        }

        followSymlinksCombo.addActionListener {
            val choice = (followSymlinksCombo.selectedItem as? BoolChoice) ?: return@addActionListener
            ShamashAsmSettingsState.getInstance(project).setOverrideFollowSymlinks(choice.toBoolOrNull())
        }

        maxClassesField.document.addDocumentListener { persistInts() }
        maxJarBytesField.document.addDocumentListener { persistInts() }
        maxClassBytesField.document.addDocumentListener { persistInts() }
    }

    private fun persistInts() {
        val settings = ShamashAsmSettingsState.getInstance(project)
        settings.setOverrideMaxClasses(readIntFieldOrNull(maxClassesField))
        settings.setOverrideMaxJarBytes(readIntFieldOrNull(maxJarBytesField))
        settings.setOverrideMaxClassBytes(readIntFieldOrNull(maxClassBytesField))
    }

    private fun setIntField(
        field: IntegerField,
        value: Int?,
    ) {
        if (value == null) {
            field.text = ""
        } else {
            field.value = value
        }
    }

    private fun readIntFieldOrNull(field: IntegerField): Int? {
        val t = field.text?.trim().orEmpty()
        if (t.isEmpty()) return null
        return t.toIntOrNull()
    }

    private fun setEnabledByApplyToggle() {
        val enabled = applyOverrides.isSelected
        scopeCombo.isEnabled = enabled
        followSymlinksCombo.isEnabled = enabled
        maxClassesField.isEnabled = enabled
        maxJarBytesField.isEnabled = enabled
        maxClassBytesField.isEnabled = enabled
    }

    private fun setNullableInt(
        field: IntegerField,
        value: Int?,
    ) {
        if (value == null) {
            field.text = ""
        } else {
            field.value = value
        }
    }

    private fun readNullableInt(field: IntegerField): Int? {
        val raw = field.text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return raw.toIntOrNull()
    }

    private fun sectionHeader(text: String): JComponent =
        JBLabel(text).apply {
            border = JBUI.Borders.empty(0, 0, 4, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }

    private fun row(
        label: String,
        component: JComponent,
    ): JComponent {
        val p =
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT
            }

        if (label.isNotBlank()) {
            p.add(JBLabel(label).apply { preferredSize = Dimension(JBUI.scale(150), preferredSize.height) })
        } else {
            p.add(JBLabel("").apply { preferredSize = Dimension(JBUI.scale(150), preferredSize.height) })
        }
        p.add(component)
        return p
    }

    private fun intField(
        placeHolder: String,
        min: Int,
    ): IntegerField {
        val f =
            IntegerField(null, min, Int.MAX_VALUE).apply {
                this.emptyText.text = placeHolder
                this.columns = 12
            }
        // Ensure it can be cleared to represent null.
        f.text = ""
        return f
    }

    private fun rebuildRegistryChoices() {
        val choices = ArrayList<RegistryChoice>()
        choices += RegistryChoice("(default) Built-in rules", null)

        for (p in AsmRuleRegistryProviders.list()) {
            val label =
                if (p.displayName.isBlank()) {
                    p.id
                } else {
                    "${p.displayName} (${p.id})"
                }
            choices += RegistryChoice(label, p.id)
        }

        registryCombo.model = javax.swing.DefaultComboBoxModel(choices.toTypedArray())
    }

    private fun selectRegistry(registryId: String?) {
        val wanted = registryId?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted == null) {
            registryCombo.selectedIndex = 0
            return
        }

        val model = registryCombo.model
        for (i in 0 until model.size) {
            val c = model.getElementAt(i)
            if (c.id == wanted) {
                registryCombo.selectedIndex = i
                return
            }
        }

        val missing = RegistryChoice("Missing: $wanted", wanted)
        val list = ArrayList<RegistryChoice>(model.size + 1)
        for (i in 0 until model.size) list += model.getElementAt(i)
        list += missing
        registryCombo.model = javax.swing.DefaultComboBoxModel(list.toTypedArray())
        registryCombo.selectedItem = missing
    }

    private data class RegistryChoice(
        private val label: String,
        val id: String?,
    ) {
        override fun toString(): String = label
    }

    private enum class ScopeChoice(
        private val label: String,
        private val scope: ScanScope?,
    ) {
        INHERIT("(inherit from YAML)", null),
        PROJECT_ONLY("PROJECT_ONLY", ScanScope.PROJECT_ONLY),
        ALL_SOURCES("ALL_SOURCES", ScanScope.ALL_SOURCES),
        PROJECT_WITH_EXTERNAL_BUCKETS("PROJECT_WITH_EXTERNAL_BUCKETS", ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS),
        ;

        override fun toString(): String = label

        fun toScopeOrNull(): ScanScope? = scope

        companion object {
            fun from(scope: ScanScope?): ScopeChoice = entries.firstOrNull { it.scope == scope } ?: INHERIT
        }
    }

    private enum class BoolChoice(
        private val label: String,
        private val value: Boolean?,
    ) {
        INHERIT("(inherit from YAML)", null),
        TRUE("true", true),
        FALSE("false", false),
        ;

        override fun toString(): String = label

        fun toBoolOrNull(): Boolean? = value

        companion object {
            fun from(value: Boolean?): BoolChoice = entries.firstOrNull { it.value == value } ?: INHERIT
        }
    }

    private fun formatApplied(ov: io.shamash.asm.core.scan.ScanOverrides): String {
        val parts = ArrayList<String>(5)
        ov.scope?.let { parts += "scope=$it" }
        ov.followSymlinks?.let { parts += "followSymlinks=$it" }
        ov.maxClasses?.let { parts += "maxClasses=$it" }
        ov.maxJarBytes?.let { parts += "maxJarBytes=$it" }
        ov.maxClassBytes?.let { parts += "maxClassBytes=$it" }
        return if (parts.isEmpty()) "(none)" else parts.joinToString(" ")
    }
}

private fun javax.swing.text.Document.addDocumentListener(onChange: () -> Unit) {
    val app = ApplicationManager.getApplication()
    val l =
        object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = change()

            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = change()

            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = change()

            private fun change() {
                if (app.isDispatchThread) onChange() else app.invokeLater { onChange() }
            }
        }
    addDocumentListener(l)
}
