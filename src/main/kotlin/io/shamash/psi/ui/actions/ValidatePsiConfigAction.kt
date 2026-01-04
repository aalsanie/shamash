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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.SchemaValidator
import io.shamash.psi.config.SchemaValidatorNetworkNt
import io.shamash.psi.ui.ShamashPsiToolWindowController
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import java.io.StringReader

class ValidatePsiConfigAction(
    private val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
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

        val yaml = String(vf.contentsToByteArray())

        object : Task.Backgroundable(project, "Shamash PSI Validate Config", false) {
            private var ok = false
            private var errorCount = 0
            private var warnCount = 0
            private var errors = emptyList<io.shamash.psi.config.ValidationError>()

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                val res =
                    ConfigValidation.loadAndValidateV1(
                        reader = StringReader(yaml),
                        schemaValidator = schemaValidator,
                    )

                errors = res.errors
                errorCount = res.errors.count { it.severity.name == "ERROR" }
                warnCount = res.errors.size - errorCount
                ok = res.ok
                ProgressManager.checkCanceled()

                ApplicationManager.getApplication().invokeLater {
                    ShamashPsiUiStateService.getInstance(project).updateValidation(res.errors)
                    ShamashPsiToolWindowController.getInstance(project).refreshAll()
                }
            }

            override fun onSuccess() {
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
