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
package io.shamash.psi.scan

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.shamash.psi.baseline.BaselineConfig
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.ValidationError
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.Finding
import io.shamash.psi.engine.ShamashPsiEngine
import io.shamash.psi.export.ShamashPsiReportExportService
import io.shamash.psi.export.schema.v1.model.ExportedReport
import io.shamash.psi.util.GlobMatcher
import java.io.Reader
import java.nio.file.Path

/**
 * Project-wide scan runner for Shamash PSI engine with optional report export.
 *
 * Threading:
 * - VFS traversal is done on the calling thread.
 * - Any PSI access (PsiManager.findFile, resolve, types) is executed inside:
 *     DumbService.runReadActionInSmartMode { ... }
 *
 * This prevents "Read access is allowed from inside read-action only" crashes and
 * avoids running heavy resolve while indexing.
 */
class ShamashProjectScanRunner(
    private val engine: ShamashPsiEngine = ShamashPsiEngine(),
    private val exportService: ShamashPsiReportExportService = ShamashPsiReportExportService(),
) {
    data class ScanResult(
        val findings: List<Finding>,
        val exportedReport: ExportedReport?,
        val outputDir: Path?,
        val baselineWritten: Boolean,
        val configErrors: List<ValidationError> = emptyList(),
    )

    /**
     * Backward-compatible entrypoint: validates first from [configReader].
     */
    fun scanProject(
        project: Project,
        configReader: Reader,
        options: ShamashScanOptions,
    ): ScanResult {
        ProgressManager.checkCanceled()

        val res = ConfigValidation.loadAndValidateV1(configReader)
        if (!res.ok || res.config == null) {
            return ScanResult(
                findings = emptyList(),
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
                configErrors = res.errors,
            )
        }
        return scanProject(project, res.config, options)
    }

    /**
     * Production entrypoint: scans project files, aggregates findings, and optionally exports reports.
     */
    fun scanProject(
        project: Project,
        config: ShamashPsiConfigV1,
        options: ShamashScanOptions,
    ): ScanResult {
        ProgressManager.checkCanceled()

        val psiManager = PsiManager.getInstance(project)
        val dumb = DumbService.getInstance(project)

        val findings = ArrayList<Finding>(1024)

        val roots = collectContentRoots(project)
        for (root in roots) {
            ProgressManager.checkCanceled()
            collectFindingsUnderRoot(root, dumb, psiManager, config, findings)
        }

        if (!options.exportReports) {
            return ScanResult(
                findings = findings,
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
            )
        }

        val projectBasePath = resolveProjectBasePath(project)
        val exportResult =
            exportService.export(
                projectBasePath = projectBasePath,
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

    private fun collectContentRoots(project: Project): List<VirtualFile> {
        val out = ArrayList<VirtualFile>(8)

        // Project content roots (works for most projects)
        out.addAll(ProjectRootManager.getInstance(project).contentRoots)

        // Module roots (helps in some multi-module layouts)
        val modules =
            com.intellij.openapi.module.ModuleManager
                .getInstance(project)
                .modules
        for (m in modules) {
            out.addAll(ModuleRootManager.getInstance(m).contentRoots)
        }

        // De-dup by path, keep order
        val seen = LinkedHashSet<String>(out.size)
        val deduped = ArrayList<VirtualFile>(out.size)
        for (vf in out) {
            if (seen.add(vf.path)) deduped.add(vf)
        }

        return deduped
    }

    private fun collectFindingsUnderRoot(
        root: VirtualFile,
        dumb: DumbService,
        psiManager: PsiManager,
        config: ShamashPsiConfigV1,
        outFindings: MutableList<Finding>,
    ) {
        val include = config.project.sourceGlobs.include
        val exclude = config.project.sourceGlobs.exclude

        VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
            ProgressManager.checkCanceled()

            if (vf.isDirectory) return@iterateChildrenRecursively true
            if (vf.fileType.isBinary) return@iterateChildrenRecursively true

            val path = GlobMatcher.normalizePath(vf.path)

            // Apply include/exclude globs early (cheap)
            if (include.isNotEmpty() && include.none { GlobMatcher.matches(it, path) }) {
                return@iterateChildrenRecursively true
            }
            if (exclude.isNotEmpty() && exclude.any { GlobMatcher.matches(it, path) }) {
                return@iterateChildrenRecursively true
            }

            // PSI work must be in read-action + smart mode
            dumb.runReadActionInSmartMode {
                ProgressManager.checkCanceled()

                val psiFile = psiManager.findFile(vf) ?: return@runReadActionInSmartMode
                val fileFindings = engine.analyzeFile(psiFile, config)
                if (fileFindings.isNotEmpty()) {
                    outFindings.addAll(fileFindings)
                }
            }

            true
        }
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
