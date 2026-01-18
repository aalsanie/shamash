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
package io.shamash.intellij.plugin.asm.ui.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaValidator
import io.shamash.asm.core.config.SchemaValidatorNetworkNt
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import java.io.StringReader
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Validates the project's Shamash ASM YAML config and shows results in the Config tab.
 *
 * UX feature:
 * - Does not run scan.
 * - Uses locked-in asm-core config validator.
 */
class ValidateAsmConfigAction(
    // Allow overriding for tests; default is the shipped validator.
    private val schemaValidator: SchemaValidator = SchemaValidatorNetworkNt,
) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return

        val vf = ShamashAsmConfigLocator.resolveConfigFile(project)
        if (vf == null || !vf.isValid) {
            // Clear the last result (or keep it; clearing is safer to avoid showing stale validation).
            ShamashAsmUiStateService.getInstance(project).clear()

            AsmActionUtil.notify(
                project,
                "Shamash ASM",
                "No ASM config found. Create one from reference first.",
                NotificationType.WARNING,
            )
            AsmActionUtil.openAsmToolWindow(project)
            val tw = ShamashAsmToolWindowController.getInstance(project)
            tw.select(ShamashAsmToolWindowController.Tab.CONFIG)
            tw.refreshAll()
            return
        }

        val yaml: String =
            try {
                String(vf.contentsToByteArray())
            } catch (t: Throwable) {
                AsmActionUtil.notify(
                    project,
                    "Shamash ASM",
                    "Failed to read config file: ${t.message ?: t::class.java.simpleName}",
                    NotificationType.ERROR,
                )
                return
            }

        val configPath: Path =
            runCatching { VfsUtil.virtualToIoFile(vf).toPath() }
                .getOrElse { Paths.get(vf.path) }

        val basePath: Path? = resolveProjectBasePath(project.basePath)
        if (basePath == null) {
            AsmActionUtil.notify(
                project,
                "Shamash ASM",
                "Cannot resolve project base path.",
                NotificationType.ERROR,
            )
            return
        }

        object : Task.Backgroundable(project, "Shamash ASM Validate Config", false) {
            private var ok = false
            private var errors: List<ValidationError> = emptyList()
            private var errorCount = 0
            private var warnCount = 0
            private var typedConfig = null as io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1?

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                // Allow networknt validator to honor cancellation inside IntelliJ.
                SchemaValidatorNetworkNt.cancelCheck = { ProgressManager.checkCanceled() }

                val res =
                    ConfigValidation.loadAndValidateV1(
                        reader = StringReader(yaml),
                        schemaValidator = schemaValidator,
                    )

                ok = res.ok
                errors = res.errors
                typedConfig = res.config

                // Keep counting logic stable without depending on enum imports in UI module.
                errorCount = errors.count { it.severity.name == "ERROR" }
                warnCount = errors.size - errorCount

                ProgressManager.checkCanceled()
            }

            override fun onSuccess() {
                // Put validation results into UI state as a ScanResult that contains config + configErrors only.
                val scanResult =
                    ScanResult(
                        options =
                            ScanOptions(
                                projectBasePath = basePath,
                                projectName = project.name,
                                configPath = configPath,
                                schemaValidator = schemaValidator,
                                includeFactsInResult = false,
                            ),
                        configPath = configPath,
                        config = typedConfig,
                        configErrors = errors,
                        scanErrors = emptyList(),
                        origins = emptyList(),
                        classUnits = 0,
                        truncated = false,
                        factsErrors = emptyList(),
                        engine = null,
                    )

                ShamashAsmUiStateService.getInstance(project).update(configPath = configPath, scanResult = scanResult)

                AsmActionUtil.openAsmToolWindow(project)
                val tw = ShamashAsmToolWindowController.getInstance(project)

                // Redirect to Dashboard ONLY upon successful validation (no ERROR).
                if (ok && errorCount == 0) {
                    tw.select(ShamashAsmToolWindowController.Tab.DASHBOARD)
                } else {
                    tw.select(ShamashAsmToolWindowController.Tab.CONFIG)
                }
                tw.refreshAll()

                if (!ok || errorCount > 0) {
                    AsmActionUtil.notify(
                        project,
                        "Shamash ASM",
                        "Config invalid. Errors: $errorCount | Warnings: $warnCount",
                        NotificationType.ERROR,
                    )
                } else {
                    AsmActionUtil.notify(
                        project,
                        "Shamash ASM",
                        "Config is valid. Errors: $errorCount | Warnings: $warnCount",
                        NotificationType.INFORMATION,
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                AsmActionUtil.notify(
                    project,
                    "Shamash ASM",
                    "Validation failed: ${error.message ?: error::class.java.simpleName}",
                    NotificationType.ERROR,
                )
            }
        }.queue()
    }

    private fun resolveProjectBasePath(basePath: String?): Path? {
        val base = basePath?.trim().orEmpty()
        if (base.isEmpty()) return null

        // Canonicalize to reduce surprises with symlinks and mixed separators.
        return runCatching { Paths.get(FileUtil.toCanonicalPath(base)).normalize() }.getOrNull()
    }
}
