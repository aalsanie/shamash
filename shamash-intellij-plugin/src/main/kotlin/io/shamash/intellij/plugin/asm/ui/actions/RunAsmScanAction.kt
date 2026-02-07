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
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.asm.core.config.ProjectLayout
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.scan.RunOverrides
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import io.shamash.intellij.plugin.asm.registry.AsmRuleRegistryProviders
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RunAsmScanAction(
    private val runner: ShamashAsmScanRunner = defaultRunner(),
) : AnAction(),
    DumbAware {
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

        val configVf: VirtualFile? = ShamashAsmConfigLocator.resolveConfigFile(project)
        val configPath: Path? =
            configVf?.let { vf ->
                runCatching { VfsUtil.virtualToIoFile(vf).toPath() }
                    .getOrElse { Paths.get(vf.path) }
            }

        val settings = ShamashAsmSettingsState.getInstance(project)

        val activeRunner = buildRunner(project, settings) ?: return

        val options =
            ScanOptions(
                projectBasePath = basePath,
                projectName = project.name,
                configPath = configPath,
                // Default is false to avoid keeping large graphs in memory.
                includeFactsInResult = settings.isIncludeFactsInMemory(),
            )

        val overrides: RunOverrides? = settings.buildRunOverridesOrNull()

        @NlsSafe val configHint =
            configPath?.toString()
                ?: "auto-discovery under ${ProjectLayout.ASM_CONFIG_DIR} (${ProjectLayout.ASM_CONFIG_CANDIDATES.joinToString()})"

        AsmActionUtil.openAsmToolWindow(project)

        // If indexing is active, we still start the task immediately (shows progress),
        // then wait inside the background thread until the IDE becomes smart.
        if (DumbService.getInstance(project).isDumb) {
            AsmActionUtil.notify(
                project,
                "Shamash ASM",
                "Indexing in progress. Scan will start automatically when indexing finishes.",
                NotificationType.INFORMATION,
            )
        }

        object : Task.Backgroundable(project, "Shamash ASM Scan", true) {
            @Volatile
            private var scanResult: ScanResult? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                ProgressManager.checkCanceled()

                val dumb = DumbService.getInstance(project)
                if (dumb.isDumb) {
                    indicator.text = "Waiting for indexing to finish"
                    indicator.text2 = "Scan will start automatically"
                    dumb.waitForSmartMode()
                    ProgressManager.checkCanceled()
                }

                indicator.text = "Running ASM scan"
                indicator.text2 = "Config: $configHint"

                scanResult = activeRunner.run(options, overrides = overrides)
                ProgressManager.checkCanceled()
            }

            override fun onSuccess() {
                val result = scanResult

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater

                    ShamashAsmUiStateService.getInstance(project).update(configPath = configPath, scanResult = result)
                    AsmActionUtil.openAsmToolWindow(project)

                    val tw = ShamashAsmToolWindowController.getInstance(project)

                    if (result == null) {
                        tw.select(ShamashAsmToolWindowController.Tab.DASHBOARD)
                        tw.refreshAll()
                        AsmActionUtil.notify(project, "Shamash ASM", "Scan produced no result.", NotificationType.WARNING)
                        return@invokeLater
                    }

                    when {
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
                if (project.isDisposed) return
                AsmActionUtil.notify(
                    project,
                    "Shamash ASM",
                    "Scan failed: ${error.message ?: error::class.java.simpleName}",
                    NotificationType.ERROR,
                )
            }
        }.queue()
    }

    private fun resolveProjectBasePath(project: Project): Path? {
        val base = project.basePath ?: return null
        val p = Paths.get(base)

        return when {
            p.exists() && p.isDirectory() -> p
            else ->
                runCatching {
                    Paths.get(FileUtil.toCanonicalPath(base))
                }.getOrNull()
        }
    }

    companion object {
        private const val SHAMASH_PLUGIN_ID: String = "io.shamash"

        private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId(SHAMASH_PLUGIN_ID))?.version ?: "unknown"

        private fun defaultRunner(): ShamashAsmScanRunner =
            ShamashAsmScanRunner(
                engine = ShamashAsmEngine(toolName = "Shamash ASM", toolVersion = pluginVersion()),
            )

        private fun buildRunner(
            project: Project,
            settings: ShamashAsmSettingsState,
        ): ShamashAsmScanRunner? {
            val toolName = "Shamash ASM"
            val toolVersion = pluginVersion()

            val registryId = settings.getRegistryId()
            if (registryId == null) {
                return ShamashAsmScanRunner(
                    engine = ShamashAsmEngine(toolName = toolName, toolVersion = toolVersion),
                )
            }

            val provider = AsmRuleRegistryProviders.findById(registryId)
            if (provider == null) {
                val available = AsmRuleRegistryProviders.list().joinToString { it.id }
                AsmActionUtil.notify(
                    project,
                    toolName,
                    "Registry '$registryId' not found. " +
                        "Available: ${if (available.isBlank()) "(none)" else available}. " +
                        "Open Run Settings → Registry to pick an installed provider.",
                    NotificationType.ERROR,
                )
                return null
            }

            val registry =
                try {
                    provider.create()
                } catch (t: Throwable) {
                    AsmActionUtil.notify(
                        project,
                        toolName,
                        "Registry '$registryId' failed to initialize: ${t.message ?: t::class.java.simpleName}",
                        NotificationType.ERROR,
                    )
                    return null
                }

            return ShamashAsmScanRunner(
                engine = ShamashAsmEngine(registry = registry, toolName = toolName, toolVersion = toolVersion),
            )
        }
    }
}
