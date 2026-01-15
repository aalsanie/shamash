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
package io.shamash.intellij.plugin.psi.ui.actions

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import io.shamash.intellij.plugin.psi.ui.ShamashPsiToolWindowController
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiConfigLocator
import io.shamash.psi.core.scan.ShamashProjectScanRunner
import io.shamash.psi.core.scan.ShamashScanOptions
import java.io.StringReader

/**
 * UI export action.
 *
 * Export is produced via the single scan entry point.
 */
class ExportPsiReportsAction(
    private val runner: ShamashProjectScanRunner = ShamashProjectScanRunner(),
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null || !vf.isValid) {
            PsiActionUtil.notify(project, "Shamash PSI", "Config file not found; cannot export.", NotificationType.ERROR)
            return
        }

        val yaml =
            try {
                String(vf.contentsToByteArray())
            } catch (t: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Failed to read config file: ${t.message}", NotificationType.ERROR)
                return
            }

        val options =
            ShamashScanOptions(
                exportReports = true,
                // baseline mode is controlled by config/options elsewhere; export action should not override it.
                // If you later add baseline controls in UI settings, wire it here.
                toolName = "Shamash PSI",
                toolVersion = pluginVersion(),
            )

        object : Task.Backgroundable(project, "Shamash PSI Export Reports", false) {
            private lateinit var result: ShamashProjectScanRunner.ScanResult

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = runner.scanProject(project, StringReader(yaml), options, indicator)
            }

            override fun onSuccess() {
                // Update UI state from the *single* source of truth result.
                ShamashPsiUiStateService.getInstance(project).updateFindings(result.findings)
                ShamashPsiUiStateService.getInstance(project).updateExport(result.outputDir, result.exportedReport)

                PsiActionUtil.openPsiToolWindow(project)
                ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.DASHBOARD)
                ShamashPsiToolWindowController.getInstance(project).refreshAll()

                if (result.configErrors.isNotEmpty()) {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Export failed: config invalid.",
                        NotificationType.ERROR,
                    )
                    return
                }

                val outDir = result.outputDir
                val report = result.exportedReport
                if (outDir == null || report == null) {
                    PsiActionUtil.notify(project, "Shamash PSI", "Export failed.", NotificationType.ERROR)
                    return
                }

                PsiActionUtil.notify(
                    project,
                    "Shamash PSI",
                    "Exported reports to: $outDir",
                    NotificationType.INFORMATION,
                )
            }

            override fun onThrowable(error: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", error.message ?: "Export failed.", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
