/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.service

import io.shamash.artifacts.baseline.BaselineConfig
import io.shamash.artifacts.baseline.BaselineCoordinator
import io.shamash.artifacts.baseline.BaselineMode
import io.shamash.artifacts.baseline.BaselineFindingPreprocessor
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.export.pipeline.CoherentReportPublisher
import io.shamash.export.pipeline.DefaultExportPipelineFactory
import io.shamash.export.pipeline.FindingPreprocessor
import java.nio.file.Path

class ShamashReportExportService(
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
        baseline: BaselineConfig = BaselineConfig.OFF,
        exceptionsPreprocessor: FindingPreprocessor? = null,
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ExportResult {
        val outputDir = ExportOutputLayout.normalizeOutputDir(projectBasePath, null)

        val baselineFingerprints =
            if (baseline.mode == BaselineMode.USE) {
                baselineCoordinator.loadBaselineFingerprints(outputDir)
            } else {
                emptySet()
            }

        val baselinePreprocessor: FindingPreprocessor? =
            if (baseline.mode == BaselineMode.USE && baselineFingerprints.isNotEmpty()) {
                val suppression = BaselineFindingPreprocessor(baselineFingerprints)
                FindingPreprocessor { base, fs -> suppression.process(base, fs) }
            } else {
                null
            }

        val baseAbs = projectBasePath.toAbsolutePath().normalize()
        val effectiveFindings =
            if (exceptionsPreprocessor == null) {
                findings
            } else {
                exceptionsPreprocessor.process(baseAbs, findings)
            }

        val preparedBaseline =
            if (baseline.mode == BaselineMode.GENERATE) {
                val current = baselineCoordinator.computeFingerprints(projectBasePath, effectiveFindings)
                if (baseline.writeMerged) {
                    baselineCoordinator.loadBaselineFingerprints(outputDir) + current
                } else {
                    current
                }
            } else {
                null
            }

        val orchestrator =
            DefaultExportPipelineFactory.create(
                baselinePreprocessor = baselinePreprocessor,
            )

        val report =
            CoherentReportPublisher().publish(
                outputDir = outputDir,
                onActivated = {
                    if (preparedBaseline != null) {
                        baselineCoordinator.writeBaseline(
                            outputDir = outputDir,
                            fingerprints = preparedBaseline,
                            mergeWithExisting = false,
                        )
                    }
                },
            ) { staging ->
                orchestrator.export(
                    projectBasePath = projectBasePath,
                    projectName = projectName,
                    toolName = toolName,
                    toolVersion = toolVersion,
                    findings = effectiveFindings,
                    outputDir = staging,
                    generatedAtEpochMillis = generatedAtEpochMillis,
                )
            }

        return ExportResult(
            report = report,
            outputDir = outputDir,
            baselineWritten = preparedBaseline != null,
        )
    }
}
