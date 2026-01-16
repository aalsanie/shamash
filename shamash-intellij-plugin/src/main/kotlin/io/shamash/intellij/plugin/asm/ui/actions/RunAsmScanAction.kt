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

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.asm.core.config.ProjectLayout
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RunAsmScanAction(
    private val runner: ShamashAsmScanRunner = ShamashAsmScanRunner(),
) : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return

        val basePath = resolveProjectBasePath(project)
        if (basePath == null) {
            AsmActionUtil.notify(
                project,
                "Shamash ASM",
                "Cannot resolve project base path.",
                NotificationType.ERROR,
            )
            return
        }

        // Prefer explicit/auto-discovered config file if available, but the runner can also discover it.
        val configVf: VirtualFile? = ShamashAsmConfigLocator.resolveConfigFile(project)
        val configPath: Path? =
            configVf?.let { vf ->
                // Use VfsUtil to resolve symlinks/canonical locations where possible.
                // (If it fails, fall back to the raw path.)
                runCatching { VfsUtil.virtualToIoFile(vf).toPath() }
                    .getOrElse { Paths.get(vf.path) }
            }

        val options =
            ScanOptions(
                projectBasePath = basePath,
                projectName = project.name,
                configPath = configPath,
                // schemaValidator defaults to SchemaValidatorNetworkNt in asm-core
                includeFactsInResult = false,
            )

        @NlsSafe val configHint =
            configPath?.toString()
                ?: "auto-discovery under ${ProjectLayout.ASM_CONFIG_DIR} (${ProjectLayout.ASM_CONFIG_CANDIDATES.joinToString()})"

        AsmActionUtil.openAsmToolWindow(project)

        var scanResult: ScanResult? = null

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Shamash ASM Scan", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Running ASM scan"
                    indicator.text2 = "Config: $configHint"

                    scanResult = runner.run(options)

                    ProgressManager.checkCanceled()
                }

                override fun onSuccess() {
                    val result = scanResult

                    ApplicationManager.getApplication().invokeLater {
                        // Publish result to toolwindow state.
                        ShamashAsmUiStateService.getInstance(project).update(configPath = configPath, scanResult = result)

                        AsmActionUtil.openAsmToolWindow(project)

                        // Navigate based on result shape.
                        val tw = ShamashAsmToolWindowController.getInstance(project)

                        if (result == null) {
                            tw.select(ShamashAsmToolWindowController.Tab.DASHBOARD)
                            tw.refreshAll()

                            AsmActionUtil.notify(
                                project,
                                "Shamash ASM",
                                "Scan produced no result.",
                                NotificationType.WARNING,
                            )
                            return@invokeLater
                        }

                        when {
                            // Hard config failure => Config tab
                            result.hasConfigErrors -> {
                                tw.select(ShamashAsmToolWindowController.Tab.CONFIG)
                                tw.refreshAll()

                                AsmActionUtil.notify(
                                    project,
                                    "Shamash ASM",
                                    "Config invalid. Fix errors in Config tab.",
                                    NotificationType.WARNING,
                                )
                            }

                            // Engine executed => Findings tab (even if no findings; it’s the primary output view)
                            result.hasEngineResult -> {
                                tw.select(ShamashAsmToolWindowController.Tab.FINDINGS)
                                tw.refreshAll()

                                val findingsCount = result.engine?.findings?.size ?: 0
                                val hasEngineErrors = result.engine?.hasErrors == true

                                val msg =
                                    when {
                                        hasEngineErrors -> "Scan finished with engine errors. See Dashboard for details."
                                        findingsCount == 0 -> "Scan complete. No findings. Make sure to build before scanning."
                                        else -> "Scan complete. Findings: $findingsCount"
                                    }

                                AsmActionUtil.notify(
                                    project,
                                    "Shamash ASM",
                                    msg,
                                    if (hasEngineErrors) NotificationType.WARNING else NotificationType.INFORMATION,
                                )
                            }

                            // No engine result typically means config discovery/read/scan failures.
                            else -> {
                                tw.select(ShamashAsmToolWindowController.Tab.DASHBOARD)
                                tw.refreshAll()

                                AsmActionUtil.notify(
                                    project,
                                    "Shamash ASM",
                                    "Scan did not reach engine execution. See Dashboard for details.",
                                    NotificationType.WARNING,
                                )
                            }
                        }
                    }
                }

                override fun onThrowable(error: Throwable) {
                    AsmActionUtil.notify(
                        project,
                        "Shamash ASM",
                        "Scan failed: ${error.message ?: error::class.java.simpleName}",
                        NotificationType.ERROR,
                    )
                }
            },
        )
    }

    private fun resolveProjectBasePath(project: Project): Path? {
        val base = project.basePath ?: return null
        val p = Paths.get(base)

        // Safety: IntelliJ can sometimes yield non-directory paths in special cases;
        // prefer directory.
        return when {
            p.exists() && p.isDirectory() -> p
            else ->
                runCatching {
                    // Try to canonicalize; may still be non-directory if project opened from a file.
                    Paths.get(FileUtil.toCanonicalPath(base))
                }.getOrNull()
        }
    }

    @Suppress("unused")
    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
