/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.export.api.Exporter
import java.nio.file.Path

class ExportOrchestrator(
    private val reportBuilder: ReportBuilder,
    private val exporters: List<Exporter>,
) {
    fun export(
        projectBasePath: Path,
        projectName: String,
        toolName: String,
        toolVersion: String,
        findings: List<Finding>,
        outputDir: Path,
        generatedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ExportedReport {
        val report =
            reportBuilder.build(
                projectBasePath = projectBasePath,
                projectName = projectName,
                toolName = toolName,
                toolVersion = toolVersion,
                findings = findings,
                generatedAtEpochMillis = generatedAtEpochMillis,
            )

        for (exporter in exporters) {
            exporter.export(report, outputDir)
        }

        return report
    }
}
