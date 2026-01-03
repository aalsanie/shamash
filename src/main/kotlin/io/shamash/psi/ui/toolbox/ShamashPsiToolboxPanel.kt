/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.ui.toolbox

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import io.shamash.psi.ui.actions.PsiActionUtil
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class ShamashPsiToolboxPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    init {
        val root = JPanel(VerticalLayout(10))
        root.border = javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)

        root.add(JBLabel("<html><b>Shamash PSI Toolbox</b></html>"))

        root.add(section("Scan"))
        root.add(actionButton("Run PSI Scan", "io.shamash.psi.actions.RunPsiScanAction"))
        root.add(actionButton("Open PSI Dashboard", "io.shamash.psi.actions.OpenPsiDashboardAction"))

        root.add(section("Config"))
        root.add(actionButton("Validate PSI Config", "io.shamash.psi.actions.ValidatePsiConfigAction"))
        root.add(actionButton("Open PSI Reference Config", "io.shamash.psi.actions.OpenPsiReferenceConfigAction"))
        root.add(
            actionButton(
                "Create PSI Config (from Reference)",
                "io.shamash.psi.actions.CreatePsiConfigFromReferenceAction",
            ),
        )

        root.add(section("Export"))
        root.add(actionButton("Export PSI Reports", "io.shamash.psi.actions.ExportPsiReportsAction"))

        add(root, BorderLayout.NORTH)
    }

    private fun section(name: String): JBLabel = JBLabel("<html><br><b>$name</b></html>")

    private fun actionButton(
        label: String,
        actionId: String,
    ): JButton =
        JButton(label).apply {
            addActionListener {
                val action: AnAction? = ActionManager.getInstance().getAction(actionId)
                if (action == null) {
                    PsiActionUtil.notify(project, "Shamash PSI", "Action not found: $actionId", NotificationType.ERROR)
                    return@addActionListener
                }

                // CRITICAL: actions expect a Project in the DataContext.
                // DataContext.EMPTY_CONTEXT makes e.project == null, so nothing happens.
                val ctx: DataContext =
                    SimpleDataContext
                        .builder()
                        .add(CommonDataKeys.PROJECT, project)
                        .build()

                val event =
                    AnActionEvent.createFromAnAction(
                        action,
                        // inputEvent =
                        null,
                        // place =
                        "ShamashPsiToolboxPanel",
                        ctx,
                    )

                try {
                    action.actionPerformed(event)
                } catch (t: Throwable) {
                    PsiActionUtil.notify(project, "Shamash PSI", "Action failed: ${t.message}", NotificationType.ERROR)
                }
            }
        }
}
