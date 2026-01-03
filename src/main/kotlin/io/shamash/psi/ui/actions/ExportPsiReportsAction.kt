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

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import io.shamash.psi.baseline.BaselineConfig
import io.shamash.psi.export.ShamashPsiReportExportService
import java.nio.file.Path

class ExportPsiReportsAction(
    private val exportService: ShamashPsiReportExportService = ShamashPsiReportExportService(),
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath
        if (basePath.isNullOrBlank()) {
            PsiActionUtil.notify(project, "Shamash PSI", "Project basePath is null; cannot export.", NotificationType.ERROR)
            return
        }

        val state = ShamashPsiUiStateService.getInstance(project)
        val findings = state.lastFindings
        if (findings.isEmpty()) {
            PsiActionUtil.notify(project, "Shamash PSI", "No PSI findings to export. Run a PSI scan first.", NotificationType.WARNING)
            return
        }

        val projectBasePath = Path.of(basePath)

        val result =
            try {
                exportService.export(
                    projectBasePath = projectBasePath,
                    projectName = project.name,
                    toolName = "Shamash PSI",
                    toolVersion = "0.42.0",
                    findings = findings,
                    baseline = BaselineConfig.Off,
                    exceptionsPreprocessor = null,
                    generatedAtEpochMillis = System.currentTimeMillis(),
                )
            } catch (t: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Export failed: ${t.message}", NotificationType.ERROR)
                return
            }

        state.updateExport(result.outputDir, result.report)

        PsiActionUtil.notify(
            project,
            "Shamash PSI",
            "Exported reports to ${result.outputDir}",
            NotificationType.INFORMATION,
        )
        PsiActionUtil.openPsiToolWindow(project)
    }
}
