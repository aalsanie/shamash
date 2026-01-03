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
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.SchemaValidator
import io.shamash.psi.config.SchemaValidatorNetworkNt
import io.shamash.psi.ui.ShamashPsiToolWindowController
import io.shamash.psi.ui.settings.ShamashPsiConfigLocator
import java.io.StringReader

/**
 * Validates PSI YAML and updates UI state (Config tab).
 *
 * IMPORTANT:
 * This action must *always* update ShamashPsiUiStateService so panels can render results.
 */
class ValidatePsiConfigAction(
    private val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val vf = ShamashPsiConfigLocator.resolveConfigFile(project)
        if (vf == null) {
            ShamashPsiUiStateService.getInstance(project).updateValidation(
                listOf(
                    io.shamash.psi.config.ValidationError(
                        path = "settings.configPath",
                        message =
                            "Config file not found. Create a valid PSI config " +
                                "under /shamash/config/psi.yml (or resources/shamash/config/psi.yml).",
                        severity = io.shamash.psi.config.ValidationSeverity.ERROR,
                    ),
                ),
            )

            PsiActionUtil.notify(
                project,
                "Shamash PSI",
                "No PSI schema found. " +
                    "Create one from reference first.",
                NotificationType.WARNING,
            )
            PsiActionUtil.openPsiToolWindow(project)
            ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.CONFIG)
            ShamashPsiToolWindowController.getInstance(project).refreshAll()
            return
        }

        val yaml = String(vf.contentsToByteArray())

        val validation =
            ConfigValidation.loadAndValidateV1(
                reader = StringReader(yaml),
                schemaValidator = schemaValidator,
            )

        // Update state on EDT then refresh UI so the Config tab reflects latest results.
        ApplicationManager.getApplication().invokeLater {
            val state = ShamashPsiUiStateService.getInstance(project)
            state.updateValidation(validation.errors)

            // Always refresh panels after state mutation (otherwise Config tab stays stale).
            ShamashPsiToolWindowController.getInstance(project).refreshAll()

            val msg =
                if (validation.errors.any { it.severity.name == "ERROR" }) {
                    "Config invalid. Fix errors in Config tab."
                } else {
                    // include warnings count if any
                    val warns = validation.errors.count { it.severity.name == "WARNING" }
                    if (warns > 0) "Config is valid (with $warns warning(s))." else "Config is valid."
                }

            PsiActionUtil.notify(project, "Shamash PSI", msg, NotificationType.INFORMATION)
            PsiActionUtil.openPsiToolWindow(project)
            ShamashPsiToolWindowController.getInstance(project).select(ShamashPsiToolWindowController.Tab.CONFIG)
        }
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
