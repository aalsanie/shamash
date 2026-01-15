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
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * UI export action.
 *
 * Export is produced via the single ASM scan entry point (ShamashAsmScanRunner),
 * and is controlled purely by config.export (enabled/formats/outputDir/overwrite).
 */
class ExportAsmReportsAction(
    private val runner: ShamashAsmScanRunner = ShamashAsmScanRunner(),
) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return

        val basePath = resolveProjectBasePath(project.basePath)
        if (basePath == null) {
            AsmActionUtil.notify(project, "Shamash ASM", "Cannot resolve project base path.", NotificationType.ERROR)
            return
        }

        val vf = ShamashAsmConfigLocator.resolveConfigFile(project)
        if (vf == null || !vf.isValid) {
            AsmActionUtil.notify(project, "Shamash ASM", "Config file not found; cannot export.", NotificationType.ERROR)
            return
        }

        val configPath: Path =
            runCatching { VfsUtil.virtualToIoFile(vf).toPath() }
                .getOrElse { Paths.get(vf.path) }

        val options =
            ScanOptions(
                projectBasePath = basePath,
                projectName = project.name,
                configPath = configPath,
                // schemaValidator defaults to SchemaValidatorNetworkNt (asm-core)
                includeFactsInResult = false,
            )

        object : Task.Backgroundable(project, "Shamash ASM Export Reports", false) {
            private lateinit var result: ScanResult

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                result = runner.run(options)
            }

            override fun onSuccess() {
                // Single source-of-truth state update.
                ShamashAsmUiStateService.getInstance(project).update(configPath = configPath, scanResult = result)

                AsmActionUtil.openAsmToolWindow(project)
                ShamashAsmToolWindowController.getInstance(project).select(ShamashAsmToolWindowController.Tab.DASHBOARD)
                ShamashAsmToolWindowController.getInstance(project).refreshAll()

                if (result.configErrors.isNotEmpty()) {
                    AsmActionUtil.notify(
                        project,
                        "Shamash ASM",
                        "Export failed: config invalid.",
                        NotificationType.ERROR,
                    )
                    return
                }

                val engine = result.engine
                if (engine == null) {
                    AsmActionUtil.notify(project, "Shamash ASM", "Export failed.", NotificationType.ERROR)
                    return
                }

                // Export is config-driven inside the engine.
                val export = engine.export
                if (export == null) {
                    val cfg = result.config
                    if (cfg != null && !cfg.export.enabled) {
                        AsmActionUtil.notify(
                            project,
                            "Shamash ASM",
                            "Export is disabled in config (export.enabled=false).",
                            NotificationType.WARNING,
                        )
                        return
                    }

                    // If enabled but export is null, it's either skipped due to overwrite=false with existing files
                    // or failed (error already reported in engine.errors).
                    val exportFailed = engine.errors.any { it.code.name == "EXPORT_FAILED" }
                    if (exportFailed) {
                        AsmActionUtil.notify(
                            project,
                            "Shamash ASM",
                            "Export failed. See Dashboard for details.",
                            NotificationType.ERROR,
                        )
                        return
                    }

                    // Skipped due to overwrite=false + existing reports is the only other "expected" path.
                    val hint = exportSkipHint(basePath, cfg)
                    AsmActionUtil.notify(
                        project,
                        "Shamash ASM",
                        hint ?: "Export was skipped.",
                        NotificationType.WARNING,
                    )
                    return
                }

                AsmActionUtil.notify(
                    project,
                    "Shamash ASM",
                    "Exported reports to: ${export.outputDir}",
                    NotificationType.INFORMATION,
                )
            }

            override fun onThrowable(error: Throwable) {
                AsmActionUtil.notify(project, "Shamash ASM", error.message ?: "Export failed.", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun exportSkipHint(
        projectBasePath: Path,
        cfg: io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1?,
    ): String? {
        if (cfg == null) return null
        if (!cfg.export.enabled) return null
        if (cfg.export.overwrite) return null

        val outputDir = resolveExportDir(projectBasePath, cfg.export.outputDir)
        val hasExisting = anyRequestedReportExists(outputDir, cfg.export.formats)
        if (!hasExisting) return null

        return buildString {
            append("Export skipped: overwrite is disabled (export.overwrite=false) and reports already exist in:\n")
            append(outputDir.toString())
            append("\n\nDelete existing reports or set export.overwrite=true.")
        }
    }

    private fun resolveExportDir(
        projectBasePath: Path,
        rawDir: String,
    ): Path {
        val raw = rawDir.trim()
        val chosen =
            if (raw.isEmpty()) {
                projectBasePath.resolve(ExportOutputLayout.DEFAULT_DIR_NAME)
            } else {
                val p = Paths.get(raw)
                (if (p.isAbsolute) p else projectBasePath.resolve(p)).normalize()
            }
        return ExportOutputLayout.normalizeOutputDir(projectBasePath, chosen)
    }

    private fun anyRequestedReportExists(
        outputDir: Path,
        formats: List<ExportFormat>,
    ): Boolean {
        if (!outputDir.exists() || !outputDir.isDirectory()) return false

        fun exists(fileName: String): Boolean = Files.exists(outputDir.resolve(fileName))

        for (f in formats) {
            when (f) {
                ExportFormat.JSON -> if (exists(ExportOutputLayout.JSON_FILE_NAME)) return true
                ExportFormat.SARIF -> if (exists(ExportOutputLayout.SARIF_FILE_NAME)) return true
                ExportFormat.XML -> if (exists(ExportOutputLayout.XML_FILE_NAME)) return true
                ExportFormat.HTML -> if (exists(ExportOutputLayout.HTML_FILE_NAME)) return true
            }
        }
        return false
    }

    private fun resolveProjectBasePath(basePath: String?): Path? {
        val base = basePath?.trim().orEmpty()
        if (base.isEmpty()) return null
        val p = runCatching { Paths.get(FileUtil.toCanonicalPath(base)).normalize() }.getOrNull() ?: return null
        return if (p.exists() && p.isDirectory()) p else p
    }

    private fun pluginVersion(): String = PluginManagerCore.getPlugin(PluginId.getId("io.shamash"))?.version ?: "unknown"
}
