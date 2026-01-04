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
package io.shamash.psi.ui.actions

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
import io.shamash.psi.ui.ShamashPsiToolWindowController
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import io.shamash.psi.util.ShamashProjectUtil
import java.io.StringReader
import java.nio.file.Path

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

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
            PsiActionUtil.notify(project, "Shamash PSI", "Config file not found; cannot export.", NotificationType.ERROR)
            return
        }

        val yaml = String(vf.contentsToByteArray())

        object : Task.Backgroundable(project, "Shamash PSI Export Reports", false) {
            private var outputDir: Path? = null
            private var report: io.shamash.psi.export.schema.v1.model.ExportedReport? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                try {
                    // Export requires a valid config for metadata/exceptions.
                    val validation = ConfigValidation.loadAndValidateV1(StringReader(yaml))
                    if (!validation.ok || validation.config == null) {
                        return
                    }

                    // IMPORTANT:
                    // Do NOT touch ProjectRootManager/contentRoots here.
                    // That can trigger Gradle project model initialization.
                    val basePath = resolveProjectBasePathSafe(project)

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

                    ProgressManager.checkCanceled()
                } catch (t: Throwable) {
                    // Don’t let IDE crash because some other plugin (Gradle) had broken persisted state.
                    // Surface as a normal export failure.
                    throw RuntimeException("Export failed: ${t.message}", t)
                }
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
                PsiActionUtil.notify(
                    project,
                    "Shamash PSI",
                    error.message ?: "Export failed.",
                    NotificationType.ERROR,
                )
            }
        }.queue()
    }

    /**
     * Safe project base path resolution that avoids touching Gradle-backed project model APIs.
     */
    private fun resolveProjectBasePathSafe(project: Project): Path {
        // 1) Most reliable & cheapest
        project.basePath?.let { return Path.of(it) }

        // 2) Often present in test / unusual setups
        project.projectFile
            ?.parent
            ?.path
            ?.let { return Path.of(it) }

        // 3) Best-effort guess without ProjectRootManager
        val guessed = ShamashProjectUtil.guessProjectDir(project)?.path
        if (!guessed.isNullOrBlank()) return Path.of(guessed)

        throw IllegalStateException("Project base path is null; cannot export Shamash reports.")
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
