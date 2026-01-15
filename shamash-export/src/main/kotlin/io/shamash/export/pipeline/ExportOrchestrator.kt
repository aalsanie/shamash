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
package io.shamash.export.pipeline

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.export.api.Exporter
import java.nio.file.Path

/**
 * Orchestrates building an [io.shamash.artifacts.report.schema.v1.ExportedReport]
 * and running a set of [io.shamash.export.api.Exporter].
 *
 * This is the core export pipeline component. Most callers should use
 * [io.shamash.psi.export.ShamashPsiReportExportService],
 * which wires output directory layout and baseline behavior.
 *
 * Responsibilities:
 * - Build the report once (normalization/ordering happens in [ReportBuilder]).
 * - Execute exporters in the given order.
 */
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
