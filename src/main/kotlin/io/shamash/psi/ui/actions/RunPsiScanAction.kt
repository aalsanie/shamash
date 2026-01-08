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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import io.shamash.psi.baseline.BaselineConfig
import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.ValidationSeverity
import io.shamash.psi.scan.ShamashProjectScanRunner
import io.shamash.psi.scan.ShamashScanOptions
import io.shamash.psi.ui.ShamashPsiToolWindowController
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import java.io.StringReader

class RunPsiScanAction(
    private val runner: ShamashProjectScanRunner = ShamashProjectScanRunner(),
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
            val errors =
                listOf(
                    ValidationError(
                        path = "settings.configPath",
                        message =
                            "Config file not found. Create one from reference or place it under shamash/config/psi.yml " +
                                "(or resources/shamash/config/psi.yml), or set an explicit path in settings.",
                        severity = ValidationSeverity.ERROR,
                    ),
                )

            ShamashPsiUiStateService.getInstance(project).updateValidation(errors)

            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "No PSI config found. Create one from reference first.",
                NotificationType.WARNING,
            )

            PsiActionUtil.openPsiToolWindow(project)
            val tw = ShamashPsiToolWindowController.getInstance(project)
            tw.select(ShamashPsiToolWindowController.Tab.CONFIG)
            tw.refreshAll()
            return
        }

        FileDocumentManager.getInstance().saveAllDocuments()
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val doc = FileDocumentManager.getInstance().getDocument(vf)
        val yaml = doc?.text ?: String(vf.contentsToByteArray())
        scanInBackground(project, yaml)
    }

    private fun scanInBackground(
        project: Project,
        yaml: String,
    ) {
        object : Task.Backgroundable(project, "Shamash PSI Scan", true) {
            private var validationErrors: List<ValidationError> = emptyList()
            private var findings = emptyList<io.shamash.psi.engine.Finding>()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                val result =
                    runner.scanProject(
                        project = project,
                        configReader = StringReader(yaml),
                        options =
                            ShamashScanOptions.ide(
                                toolVersion = pluginVersion(),
                                baseline = BaselineConfig.OFF,
                                exportReports = false,
                            ),
                        indicator = indicator,
                    )

                validationErrors = result.configErrors
                findings = result.findings

                ProgressManager.checkCanceled()
            }

            override fun onSuccess() {
                // publish once on EDT
                ApplicationManager.getApplication().invokeLater {
                    ShamashPsiUiStateService.getInstance(project).updateValidation(validationErrors)
                    ShamashPsiUiStateService.getInstance(project).updateFindings(findings)

                    val tw = ShamashPsiToolWindowController.getInstance(project)

                    PsiActionUtil.openPsiToolWindow(project)

                    if (validationErrors.any { it.severity == ValidationSeverity.ERROR }) {
                        tw.select(ShamashPsiToolWindowController.Tab.CONFIG)
                        tw.refreshAll()

                        PsiActionUtil.notify(
                            project,
                            "Shamash PSI",
                            "Config invalid. Fix errors in Config tab.",
                            NotificationType.WARNING,
                        )
                        return@invokeLater
                    }

                    val count = findings.size
                    if (count == 0) {
                        PsiActionUtil.notify(
                            project,
                            "Shamash PSI",
                            "Scan completed: 0 findings. (Either the project is clean, or rules produced no findings for the scanned sources.)",
                            NotificationType.INFORMATION,
                        )
                    } else {
                        PsiActionUtil.notify(
                            project,
                            "Shamash PSI",
                            "Scan completed: $count findings.",
                            NotificationType.INFORMATION,
                        )
                    }

                    // With a dedicated Findings tab, land the user there after scan.
                    tw.select(ShamashPsiToolWindowController.Tab.FINDINGS)
                    tw.refreshAll()
                }
            }

            override fun onThrowable(error: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Scan failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
