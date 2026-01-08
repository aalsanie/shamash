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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.SchemaValidator
import io.shamash.psi.config.SchemaValidatorNetworkNt
import io.shamash.psi.config.ValidationError
import io.shamash.psi.ui.ShamashPsiToolWindowController
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import java.io.StringReader

/**
 * Validates the project's Shamash PSI YAML config and shows results in the Config tab.
 *
 * UX feature:
 * - Does not run scan.
 * - Uses locked-in config validator.
 */
class ValidatePsiConfigAction(
    // Allow overriding for tests; default is the shipped validator.
    private val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null || !vf.isValid) {
            ShamashPsiUiStateService.getInstance(project).updateValidation(emptyList())
            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "No PSI config found. Create one from reference first.",
                NotificationType.WARNING,
            )
            PsiActionUtil.openPsiToolWindow(project)
            ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.CONFIG)
            ShamashPsiToolWindowController.getInstance(project).refreshAll()
            return
        }

        val yaml =
            try {
                String(vf.contentsToByteArray())
            } catch (t: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Failed to read config file: ${t.message}", NotificationType.ERROR)
                return
            }

        object : Task.Backgroundable(project, "Shamash PSI Validate Config", false) {
            private var ok = false
            private var errors: List<ValidationError> = emptyList()
            private var errorCount = 0
            private var warnCount = 0

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                // Make schema validation cancelable.
                // (This is supported by SchemaValidatorNetworkNt; harmless for other validators.)
                try {
                    io.shamash.psi.config.SchemaValidatorNetworkNt.cancelCheck = { ProgressManager.checkCanceled() }
                } catch (_: Throwable) {
                    // ignore - validator impl may not support cancellation hook
                }

                val res =
                    ConfigValidation.loadAndValidateV1(
                        reader = StringReader(yaml),
                        schemaValidator = schemaValidator,
                    )

                ok = res.ok
                errors = res.errors

                // Keep counting logic stable without guessing additional types.
                errorCount = errors.count { it.severity.name == "ERROR" }
                warnCount = errors.size - errorCount

                ProgressManager.checkCanceled()
            }

            override fun onSuccess() {
                // Update UI state once.
                ShamashPsiUiStateService.getInstance(project).updateValidation(errors)

                PsiActionUtil.openPsiToolWindow(project)
                ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.CONFIG)
                ShamashPsiToolWindowController.getInstance(project).refreshAll()

                if (!ok || errorCount > 0) {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Config invalid. Errors: $errorCount | Warnings: $warnCount",
                        NotificationType.ERROR,
                    )
                } else {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Config is valid. Errors: $errorCount | Warnings: $warnCount",
                        NotificationType.INFORMATION,
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Validation failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }
}
