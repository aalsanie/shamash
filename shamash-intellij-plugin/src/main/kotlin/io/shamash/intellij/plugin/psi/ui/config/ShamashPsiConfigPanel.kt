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
package io.shamash.intellij.plugin.psi.ui.config

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import io.shamash.intellij.plugin.psi.ui.actions.PsiActionUtil
import io.shamash.intellij.plugin.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiConfigLocator
import io.shamash.psi.core.config.ValidationError
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

class ShamashPsiConfigPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val dataContext: DataContext =
        SimpleDataContext
            .builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()

    private val configPathLabel = JBLabel()
    private val validationArea = JBTextArea()
    private val supportedRulesArea = JBTextArea()

    // Computed once per panel instance. Rules/specs change only with plugin version, not per refresh.
    private val supportedRulesText: String = PsiRuleSupportView.format(PsiRuleSupportView.compute())

    init {
        val root = JPanel(VerticalLayout(10))
        root.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // --- Config location ---
        root.add(JBLabel("<html><b>Active PSI Config</b></html>"))
        root.add(configPathLabel)

        val buttonsRow = JPanel()
        buttonsRow.add(button("Validate", ACTION_VALIDATE))
        buttonsRow.add(button("Open Reference", ACTION_OPEN_REFERENCE))
        buttonsRow.add(button("Create from Reference", ACTION_CREATE_FROM_REFERENCE))
        root.add(buttonsRow)

        // --- Validation output ---
        root.add(JBLabel("<html><b>Validation</b></html>"))
        validationArea.isEditable = false
        validationArea.lineWrap = true
        validationArea.wrapStyleWord = true
        validationArea.minimumSize = Dimension(200, 120)
        root.add(JBScrollPane(validationArea).apply { preferredSize = Dimension(200, 220) })

        // --- Supported rules ---
        root.add(JBLabel("<html><b>Supported PSI Rules</b></html>"))
        supportedRulesArea.isEditable = false
        supportedRulesArea.lineWrap = true
        supportedRulesArea.wrapStyleWord = true
        root.add(JBScrollPane(supportedRulesArea).apply { preferredSize = Dimension(200, 220) })

        add(root, BorderLayout.CENTER)

        refresh()
    }

    fun refresh() {
        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        configPathLabel.text = vf?.path ?: "<no config found>"

        val errors = ShamashPsiUiStateService.getInstance(project).lastValidationErrors
        validationArea.text = formatValidation(errors)

        supportedRulesArea.text = supportedRulesText
    }

    private fun formatValidation(errors: List<ValidationError>): String {
        if (errors.isEmpty()) return "No validation results yet. Click Validate."

        return buildString {
            val errCount = errors.count { it.severity.name == "ERROR" }
            val warnCount = errors.size - errCount
            append("Errors: ")
                .append(errCount)
                .append(" | Warnings: ")
                .append(warnCount)
                .append("\n\n")

            for (e in errors) {
                append(e.severity.name)
                    .append(" @ ")
                    .append(if (e.path.isBlank()) "<root>" else e.path)
                    .append("\n  ")
                    .append(e.message)
                    .append("\n\n")
            }
        }.trimEnd()
    }

    private fun button(
        label: String,
        actionId: String,
    ): JButton =
        JButton(label).apply {
            addActionListener {
                val action = ActionManager.getInstance().getAction(actionId)
                if (action == null) {
                    PsiActionUtil.notify(project, "Shamash PSI", "Action not found: $actionId", NotificationType.ERROR)
                    return@addActionListener
                }

                // IMPORTANT: pass project via DataContext; EMPTY_CONTEXT makes e.project null.
                val event = AnActionEvent.createFromAnAction(action, null, "ShamashPsiConfigPanel", dataContext)
                action.actionPerformed(event)

                // This refresh is immediate (may be stale for background actions),
                // but the toolwindow controller refresh after background completion will update it.
                refresh()
            }
        }

    companion object {
        // Action IDs should match plugin.xml registration.
        private const val ACTION_VALIDATE = "io.shamash.intellij.plugin.psi.ui.actions.ValidatePsiConfigAction"
        private const val ACTION_OPEN_REFERENCE = "io.shamash.intellij.plugin.psi.ui.actions.OpenPsiReferenceConfigAction"
        private const val ACTION_CREATE_FROM_REFERENCE = "io.shamash.intellij.plugin.psi.ui.actions.CreatePsiConfigFromReferenceAction"
    }
}
