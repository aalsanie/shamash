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
import java.nio.file.Path

/**
 * Exports Shamash reports to `<projectRoot>/shamash` and optionally applies / generates a baseline.
 *
 * This class wires:
 * - output directory resolution,
 * - baseline load/suppress,
 * - export orchestration,
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

    fun export(
        projectBasePath: Path,
        projectName: String,
        toolName: String,
        toolVersion: String,
        findings: List<Finding>,
        baseline: BaselineConfig = BaselineConfig.Off,
        exceptionsPreprocessor: FindingPreprocessor? = null,
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ExportResult {
        val outputDir = ExportPathResolver.resolveAndEnsureOutputDir(projectBasePath)

        val baselineFingerprints =
            when (baseline.mode) {
                BaselineMode.USE -> baselineCoordinator.loadBaselineFingerprints(outputDir)
                BaselineMode.GENERATE -> emptySet()
                BaselineMode.OFF -> emptySet()
            }

        val baselinePreprocessor = baselineCoordinator.createSuppressionPreprocessor(baselineFingerprints)

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
                val fingerprints = baselineCoordinator.computeFingerprints(projectBasePath, findings)
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
