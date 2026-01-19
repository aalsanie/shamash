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
package io.shamash.intellij.plugin.psi.ui.dashboard

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import io.shamash.intellij.plugin.psi.ui.actions.PsiActionUtil
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class DashboardToolbarPanel(
    private val project: Project,
    private val onRefresh: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT)),
    UiDataProvider {
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

                // Never call action.actionPerformed(...) directly.
                // actionPerformed is @ApiStatus.OverrideOnly; IntelliJ Platform must dispatch it.
                ActionManager.getInstance().tryToExecute(action, null, this@DashboardToolbarPanel, "ShamashPsiDashboardToolbar", true)

                // Immediate refresh is fine for responsiveness, but background actions will refresh again on success.
                onRefresh()
            }
        }

    override fun uiDataSnapshot(sink: DataSink) {
        sink.set(CommonDataKeys.PROJECT, project)
    }

    companion object {
        private const val ACTION_RUN_SCAN = "io.shamash.intellij.plugin.psi.ui.actions.RunPsiScanAction"
        private const val ACTION_EXPORT = "io.shamash.intellij.plugin.psi.ui.actions.ExportPsiReportsAction"
        private const val ACTION_VALIDATE = "io.shamash.intellij.plugin.psi.ui.actions.ValidatePsiConfigAction"
    }
}
