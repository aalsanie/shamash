/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
import com.intellij.openapi.application.ApplicationManager
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
import io.shamash.asm.core.scan.RunOverrides
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmSettingsState
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RunAsmScanAction(
    private val runner: ShamashAsmScanRunner? = null,
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

        val activeRunner = runner ?: ShamashAsmScanRunner(AsmExecution.engineOrNotify(project) ?: return)

        val options =
            ScanOptions(
                projectBasePath = basePath,
                projectName = project.name,
                configPath = configPath,
                includeFactsInResult = settings.isIncludeFactsInMemory(),
            )

        val overrides: RunOverrides? = settings.buildRunOverridesOrNull()

        @NlsSafe val configHint = configPath?.toString() ?: "built-in discovery"

        AsmActionUtil.openAsmToolWindow(project)

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

                    val tab =
                        when {
                            result.hasConfigErrors -> ShamashAsmToolWindowController.Tab.CONFIG
                            !result.isSuccess -> ShamashAsmToolWindowController.Tab.DASHBOARD
                            else -> ShamashAsmToolWindowController.Tab.FINDINGS
                        }
                    tw.select(tab)
                    tw.refreshAll()
                    AsmActionUtil.notify(
                        project,
                        "Shamash ASM",
                        AsmScanPresentation.message(result),
                        AsmScanPresentation.notificationType(result),
                    )
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
            p.exists() && p.isDirectory() -> {
                p
            }

            else -> {
                runCatching {
                    Paths.get(FileUtil.toCanonicalPath(base))
                }.getOrNull()
            }
        }
    }
}
