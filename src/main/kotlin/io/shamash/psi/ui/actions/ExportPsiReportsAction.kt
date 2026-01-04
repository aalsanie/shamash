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
package io.shamash.psi.ui

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.shamash.psi.baseline.BaselineConfig
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.export.ShamashPsiReportExportService
import io.shamash.psi.scan.ShamashScanOptions
import io.shamash.psi.ui.actions.PsiActionUtil
import io.shamash.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import java.io.StringReader

class ExportPsiReportsAction(
    private val exportService: ShamashPsiReportExportService = ShamashPsiReportExportService(),
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val findings = ShamashPsiUiStateService.getInstance(project).lastFindings
        if (findings.isEmpty()) {
            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "No PSI findings to export. Run a PSI scan first (or confirm your rules produce findings for this project).",
                NotificationType.WARNING,
            )
            PsiActionUtil.openPsiToolWindow(project)
            ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.DASHBOARD)
            ShamashPsiToolWindowController.getInstance(project).refreshAll()
            return
        }

        // Resolve config (for metadata only; export uses findings from UI state)
        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
            PsiActionUtil.notify(project, "Shamash PSI", "Config file not found; cannot export.", NotificationType.ERROR)
            return
        }
        val yaml = String(vf.contentsToByteArray())

        object : Task.Backgroundable(project, "Shamash PSI Export Reports", false) {
            private var outputDir: java.nio.file.Path? = null
            private var report: io.shamash.psi.export.schema.v1.model.ExportedReport? = null
            private var baselineWritten: Boolean = false

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                val validation = ConfigValidation.loadAndValidateV1(StringReader(yaml))
                if (!validation.ok || validation.config == null) {
                    // export requires a valid config because exporters need metadata and exceptions.
                    return
                }

                val basePath = resolveProjectBasePath(project)
                val result =
                    exportService.export(
                        projectBasePath = basePath,
                        projectName = project.name,
                        toolName = "Shamash PSI",
                        toolVersion = pluginVersion(),
                        findings = findings,
                        baseline = BaselineConfig.Off,
                        exceptionsPreprocessor = null,
                        generatedAtEpochMillis = System.currentTimeMillis(),
                    )

                outputDir = result.outputDir
                report = result.report
                baselineWritten = result.baselineWritten

                ProgressManager.checkCanceled()
            }

            override fun onSuccess() {
                ShamashPsiUiStateService.getInstance(project).updateExport(outputDir, report)
                ShamashPsiToolWindowController.getInstance(project).refreshAll()

                if (outputDir == null || report == null) {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Export failed: config invalid or export could not be created.",
                        NotificationType.ERROR,
                    )
                    return
                }

                PsiActionUtil.notify(
                    project,
                    "Shamash PSI",
                    "Exported reports to: $outputDir",
                    NotificationType.INFORMATION,
                )
            }

            override fun onThrowable(error: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Export failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun resolveProjectBasePath(project: Project): java.nio.file.Path {
        val basePath =
            project.basePath
                ?: project.projectFile?.parent?.path
                ?: com.intellij.openapi.roots.ProjectRootManager
                    .getInstance(project)
                    .contentRoots
                    .firstOrNull()
                    ?.path
                ?: throw IllegalStateException("Project basePath is null; cannot export Shamash reports.")
        return java.nio.file.Path
            .of(basePath)
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
