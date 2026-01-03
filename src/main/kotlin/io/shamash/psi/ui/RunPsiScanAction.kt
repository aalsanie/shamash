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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.SchemaValidator
import io.shamash.psi.config.SchemaValidatorNetworkNt
import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.ValidationSeverity
import io.shamash.psi.scan.ShamashProjectScanRunner
import io.shamash.psi.scan.ShamashScanOptions
import io.shamash.psi.ui.actions.PsiActionUtil
import io.shamash.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import java.io.StringReader

class RunPsiScanAction(
    private val runner: ShamashProjectScanRunner = ShamashProjectScanRunner(),
    private val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
            ShamashPsiUiStateService.getInstance(project).updateValidation(
                listOf(
                    ValidationError(
                        path = "settings.configPath",
                        message =
                            "Config file not found. " +
                                "Create a valid PSI config under /shamash/config/psi.yml " +
                                "(or resources/shamash/config/psi.yml).",
                        severity = ValidationSeverity.ERROR,
                    ),
                ),
            )

            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "No PSI schema found. Create one from reference first.",
                NotificationType.WARNING,
            )
            PsiActionUtil.openPsiToolWindow(project)
            ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.CONFIG)
            return
        }

        val yaml = String(vf.contentsToByteArray())
        validateAndScanInBackground(project, yaml)
    }

    private fun validateAndScanInBackground(
        project: com.intellij.openapi.project.Project,
        yaml: String,
    ) {
        object : Task.Backgroundable(project, "Shamash PSI Scan", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                // 1) Validate (background)
                val validation =
                    ConfigValidation.loadAndValidateV1(
                        reader = StringReader(yaml),
                        schemaValidator = schemaValidator,
                    )

                // publish validation on EDT
                ApplicationManager.getApplication().invokeLater {
                    ShamashPsiUiStateService.getInstance(project).updateValidation(validation.errors)
                }

                ProgressManager.checkCanceled()

                // IMPORTANT:
                // - We treat WARNINGS as non-blocking.
                // - We only stop scanning if we have at least one ERROR (validation.ok==false) or config=null.
                if (!validation.ok || validation.config == null) return

                // 2) Scan (background)
                val result =
                    runner.scanProject(
                        project = project,
                        config = validation.config,
                        options =
                            ShamashScanOptions(
                                exportReports = false,
                                baseline = io.shamash.psi.baseline.BaselineConfig.Off,
                                toolName = "Shamash PSI",
                                toolVersion = pluginVersion(),
                                generatedAtEpochMillis = System.currentTimeMillis(),
                            ),
                    )

                ProgressManager.checkCanceled()

                ApplicationManager.getApplication().invokeLater {
                    ShamashPsiUiStateService.getInstance(project).updateFindings(result.findings)
                }
            }

            override fun onSuccess() {
                val state = ShamashPsiUiStateService.getInstance(project)

                // Only treat ERROR validation entries as blocking.
                val hasError = state.lastValidationErrors.any { it.severity == ValidationSeverity.ERROR }
                if (hasError) {
                    PsiActionUtil.notify(project, "Shamash PSI", "Config invalid. Fix errors in Config tab.", NotificationType.WARNING)
                    PsiActionUtil.openPsiToolWindow(project)
                    ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.CONFIG)
                    ShamashPsiToolWindowController.getInstance(project).refreshAll()
                    return
                }

                PsiActionUtil.notify(project, "Shamash PSI", "Scan completed.", NotificationType.INFORMATION)
                PsiActionUtil.openPsiToolWindow(project)

                val controller = ShamashPsiToolWindowController.getInstance(project)
                controller.select(ShamashPsiToolWindowController.Tab.DASHBOARD)
                controller.refreshAll()
            }

            override fun onThrowable(error: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Scan failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
