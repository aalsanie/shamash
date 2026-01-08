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
package io.shamash.psi.export

import io.shamash.psi.baseline.BaselineConfig
import io.shamash.psi.baseline.BaselineCoordinator
import io.shamash.psi.baseline.BaselineMode
import io.shamash.psi.engine.Finding
import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exports Shamash reports to `<projectRoot>/.shamash` and optionally applies / generates a baseline.
 *
 * This class wires:
 * - output directory resolution + normalization,
 * - baseline load/suppress,
 * - finding preprocessing,
 * - export orchestration (ALL formats by default),
 * - baseline generation/merge.
 */
class ShamashPsiReportExportService(
    private val baselineCoordinator: BaselineCoordinator = BaselineCoordinator(),
) {
    data class ExportResult(
        val report: ExportedReport,
        val outputDir: Path,
        val baselineWritten: Boolean,
    )

    /**
     * Primary entrypoint.
     *
     * - Exports ALL formats by default (JSON/SARIF/XML/HTML).
     * - Applies preprocessors in order:
     *   1) exceptionsPreprocessor (if present)
     *   2) baselinePreprocessor (if baseline USE and baseline exists)
     *
     * Baseline GENERATE:
     * - Baseline is generated from the same "current findings" definition used for reporting,
     *   which includes applying the exceptionsPreprocessor (if provided).
     */
    fun export(
        projectBasePath: Path,
        projectName: String,
        toolName: String,
        toolVersion: String,
        findings: List<Finding>,
        baseline: BaselineConfig = BaselineConfig.OFF,
        exceptionsPreprocessor: FindingPreprocessor? = null,
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ExportResult {
        val outputDir = ExportOutputLayout.normalizeOutputDir(projectBasePath, null)
        Files.createDirectories(outputDir)

        val baselineFingerprints =
            if (baseline.mode == BaselineMode.USE) {
                baselineCoordinator.loadBaselineFingerprints(outputDir)
            } else {
                emptySet()
            }

        // Adapt baseline's preprocessor type into the export-layer preprocessor hook
        // to preserve layering (baseline stays independent of export).
        val baselinePreprocessor: FindingPreprocessor? =
            if (baseline.mode == BaselineMode.USE && baselineFingerprints.isNotEmpty()) {
                baselineCoordinator
                    .createSuppressionPreprocessor(baselineFingerprints)
                    ?.let { bp ->
                        FindingPreprocessor { base, fs -> bp.process(base, fs) }
                    }
            } else {
                null
            }

        val orchestrator =
            DefaultExportPipelineFactory.create(
                exceptionsPreprocessor = exceptionsPreprocessor,
                baselinePreprocessor = baselinePreprocessor,
            )

        val report =
            orchestrator.export(
                projectBasePath = projectBasePath,
                projectName = projectName,
                toolName = toolName,
                toolVersion = toolVersion,
                findings = findings,
                outputDir = outputDir,
                generatedAtEpochMillis = generatedAtEpochMillis,
            )

        val baselineWritten =
            if (baseline.mode == BaselineMode.GENERATE) {
                // Baseline generation must match the exported "current findings" definition.
                // We apply exceptions only (no baseline suppression while generating).
                val baseAbs = projectBasePath.toAbsolutePath().normalize()
                val currentFindings =
                    if (exceptionsPreprocessor != null) {
                        exceptionsPreprocessor.process(baseAbs, findings)
                    } else {
                        findings
                    }

                val fingerprints = baselineCoordinator.computeFingerprints(projectBasePath, currentFindings)
                baselineCoordinator.writeBaseline(
                    outputDir = outputDir,
                    fingerprints = fingerprints,
                    mergeWithExisting = baseline.writeMerged,
                )
                true
            } else {
                false
            }

        return ExportResult(
            report = report,
            outputDir = outputDir,
            baselineWritten = baselineWritten,
        )
    }
}
