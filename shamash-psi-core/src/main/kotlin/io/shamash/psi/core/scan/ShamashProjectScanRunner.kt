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
package io.shamash.psi.core.scan

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.export.service.ShamashReportExportService
import io.shamash.psi.core.config.ConfigValidation
import io.shamash.psi.core.config.ValidationError
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.EngineError
import io.shamash.psi.core.engine.ShamashPsiEngine
import java.io.Reader
import java.nio.file.Path

/**
 * Project-wide scan runner for Shamash PSI.
 *
 * One entry point:
 * - Loads + validates config (config module).
 * - Scans project PSI files (engine module).
 * - Optionally exports reports + baseline behavior (export + baseline modules).
 *
 * Threading:
 * - All PSI access is performed inside DumbService.runReadActionInSmartMode { ... } to avoid
 *   read-action and indexing crashes.
 */
class ShamashProjectScanRunner(
    private val engine: ShamashPsiEngine = ShamashPsiEngine(),
    private val exportService: ShamashReportExportService = ShamashReportExportService(),
) {
    data class ScanResult(
        val findings: List<Finding>,
        val exportedReport: ExportedReport?,
        val outputDir: Path?,
        val baselineWritten: Boolean,
        val configErrors: List<ValidationError> = emptyList(),
    )

    /**
     * Single production entrypoint: validates config, scans project, and optionally exports reports.
     */
    fun scanProject(
        project: Project,
        configReader: Reader,
        options: ShamashScanOptions,
        indicator: ProgressIndicator,
    ): ScanResult {
        ProgressManager.checkCanceled()

        val validation = ConfigValidation.loadAndValidateV1(configReader)
        val config = validation.config
        if (!validation.ok || config == null) {
            return ScanResult(
                findings = emptyList(),
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
                configErrors = validation.errors,
            )
        }

        val findings = scanFindings(project, config, indicator)

        if (!options.exportReports) {
            return ScanResult(
                findings = findings,
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
            )
        }

        val exportResult =
            exportService.export(
                projectBasePath = resolveProjectBasePath(project),
                projectName = project.name,
                toolName = options.toolName,
                toolVersion = options.toolVersion,
                findings = findings,
                baseline = options.baseline,
                exceptionsPreprocessor = null,
                generatedAtEpochMillis = options.generatedAtEpochMillis,
            )

        return ScanResult(
            findings = findings,
            exportedReport = exportResult.report,
            outputDir = exportResult.outputDir,
            baselineWritten = exportResult.baselineWritten,
        )
    }

    private fun scanFindings(
        project: Project,
        config: ShamashPsiConfigV1,
        indicator: ProgressIndicator,
    ): List<Finding> {
        ProgressManager.checkCanceled()

        val psiManager = PsiManager.getInstance(project)
        val dumb = DumbService.getInstance(project)

        val includeGlobs = config.project.sourceGlobs.include
        val excludeGlobs = config.project.sourceGlobs.exclude

        val roots = collectContentRoots(project)
        if (roots.isEmpty()) return emptyList()

        val out = ArrayList<Finding>(1024)
        val engineErrors = ArrayList<EngineError>(256)

        // One smart+read block for the whole scan: PSI work stays safe, avoids per-file read-action overhead.
        dumb.runReadActionInSmartMode {
            ProgressManager.checkCanceled()

            for (root in roots) {
                ProgressManager.checkCanceled()

                VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                    ProgressManager.checkCanceled()

                    if (vf.isDirectory) return@iterateChildrenRecursively true
                    if (vf.fileType.isBinary) return@iterateChildrenRecursively true

                    val path = GlobMatcher.normalizePath(vf.path)

                    // Apply include/exclude globs early (cheap).
                    if (includeGlobs.isNotEmpty() && includeGlobs.none { GlobMatcher.matches(it, path) }) {
                        return@iterateChildrenRecursively true
                    }
                    if (excludeGlobs.isNotEmpty() && excludeGlobs.any { GlobMatcher.matches(it, path) }) {
                        return@iterateChildrenRecursively true
                    }

                    indicator.text = "Scanning: ${vf.name}"
                    indicator.text2 = path

                    val psiFile = psiManager.findFile(vf) ?: return@iterateChildrenRecursively true
                    val res = engine.analyzeFileResult(psiFile, config)

                    if (res.errors.isNotEmpty()) engineErrors.addAll(res.errors)
                    if (res.findings.isNotEmpty()) out.addAll(res.findings)

                    true
                }
            }
        }

        // Notify once (no spam), after scan completes.
        if (engineErrors.isNotEmpty()) {
            Notifications.Bus.notify(
                Notification(
                    "Shamash PSI",
                    "Engine encountered ${engineErrors.size} errors",
                    "${engineErrors.first().fileId}: ${engineErrors.first().phase} - ${engineErrors.first().message}\n" +
                        "(see idea.log for full list)",
                    NotificationType.WARNING,
                ),
                project,
            )
        }

        return out
    }

    private fun collectContentRoots(project: Project): List<VirtualFile> {
        val out = ArrayList<VirtualFile>(8)

        // Project content roots.
        out.addAll(ProjectRootManager.getInstance(project).contentRoots)

        // Module roots (helps in multi-module layouts).
        val modules =
            com.intellij.openapi.module.ModuleManager
                .getInstance(project)
                .modules
        for (m in modules) {
            out.addAll(ModuleRootManager.getInstance(m).contentRoots)
        }

        // De-dup by path, keep order.
        val seen = LinkedHashSet<String>(out.size)
        val deduped = ArrayList<VirtualFile>(out.size)
        for (vf in out) {
            if (seen.add(vf.path)) deduped.add(vf)
        }
        return deduped
    }

    private fun resolveProjectBasePath(project: Project): Path {
        val basePath =
            project.basePath
                ?: project.projectFile?.parent?.path
                ?: ProjectRootManager
                    .getInstance(project)
                    .contentRoots
                    .firstOrNull()
                    ?.path
                ?: throw IllegalStateException("Project basePath is null; cannot export Shamash reports.")
        return Path.of(basePath)
    }
}
