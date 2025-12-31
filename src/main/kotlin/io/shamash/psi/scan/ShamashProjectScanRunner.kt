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
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import io.shamash.psi.config.ConfigValidation
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.Finding
import io.shamash.psi.engine.ShamashPsiEngine
import io.shamash.psi.export.ShamashPsiReportExportService
import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.io.Reader
import java.nio.file.Path

/**
 * Project-wide scan runner for Shamash PSI engine with optional report export.
 *
 * Intended for explicit "Run Scan" execution paths (actions/CLI-like usage),
 * not IntelliJ inspections (which should run incrementally per file).
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
        val configErrors: List<io.shamash.psi.config.ValidationError> = emptyList(),
    )

    /**
     * Backward-compatible entrypoint.
     *
     * Uses baseline mode OFF and exports under `<projectRoot>/shamash` when [exportReports] is true.
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
     * Production entrypoint: scans project files, aggregates findings, and optionally exports reports
     * under `<projectRoot>/shamash`, with baseline behavior controlled by [options.baseline].
     */
    fun scanProject(
        project: Project,
        config: ShamashPsiConfigV1,
        options: ShamashScanOptions,
    ): ScanResult {
        ProgressManager.checkCanceled()

        val psiManager = PsiManager.getInstance(project)
        val findings = ArrayList<Finding>(1024)

        val roots = ProjectRootManager.getInstance(project).contentRoots
        for (root in roots) {
            ProgressManager.checkCanceled()
            collectFindingsUnderRoot(root, psiManager, config, findings)
        }

        if (!options.exportReports) {
            return ScanResult(
                findings = findings,
                exportedReport = null,
                outputDir = null,
                baselineWritten = false,
            )
        }

        val basePath =
            project.basePath
                ?: throw IllegalStateException("Project basePath is null; cannot export Shamash reports.")
        val projectBasePath = Path.of(basePath)

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

    private fun collectFindingsUnderRoot(
        root: VirtualFile,
        psiManager: PsiManager,
        config: ShamashPsiConfigV1,
        outFindings: MutableList<Finding>,
    ) {
        VfsUtilCore.iterateChildrenRecursively(
            root,
            null,
        ) { vf ->
            ProgressManager.checkCanceled()

            if (vf.isDirectory) {
                return@iterateChildrenRecursively true
            }

            if (vf.fileType.isBinary) {
                return@iterateChildrenRecursively true
            }

            val psiFile = psiManager.findFile(vf) ?: return@iterateChildrenRecursively true

            ProgressManager.checkCanceled()
            val fileFindings = engine.analyzeFile(psiFile, config)
            if (fileFindings.isNotEmpty()) {
                outFindings.addAll(fileFindings)
            }

            true
        }
    }
}
