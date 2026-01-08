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
package io.shamash.psi.ui.dashboard

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import io.shamash.psi.ui.actions.PsiActionUtil
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class DashboardToolbarPanel(
    private val project: Project,
    private val onRefresh: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT)) {
    private val dataContext: DataContext =
        SimpleDataContext
            .builder()
            .add(CommonDataKeys.PROJECT, project)
            .build()

    init {
        add(JButton("Refresh").apply { addActionListener { onRefresh() } })

        add(actionButton("Run Scan", ACTION_RUN_SCAN))
        add(actionButton("Export", ACTION_EXPORT))
        add(actionButton("Validate Config", ACTION_VALIDATE))
    }

    private fun actionButton(
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

                // IMPORTANT: pass DataContext with PROJECT (EMPTY_CONTEXT => e.project == null).
                val event = AnActionEvent.createFromAnAction(action, null, "ShamashPsiDashboardToolbar", dataContext)
                action.actionPerformed(event)

                // Immediate refresh is fine for responsiveness, but background actions will refresh again on success.
                onRefresh()
            }
        }

    companion object {
        private const val ACTION_RUN_SCAN = "io.shamash.psi.actions.RunPsiScanAction"
        private const val ACTION_EXPORT = "io.shamash.psi.actions.ExportPsiReportsAction"
        private const val ACTION_VALIDATE = "io.shamash.psi.actions.ValidatePsiConfigAction"
    }
}
