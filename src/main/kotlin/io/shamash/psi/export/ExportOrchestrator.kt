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

import io.shamash.psi.engine.Finding
import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.nio.file.Path

/**
 * Orchestrates building an ExportedReport and running a set of exporters.
 *
 * This is the only entry-point other layers should call for exporters.
 *
 * - Report building: normalization and ordering are done once.
 * - Exporters must not mutate or reorder the report.
 * - Exporters are executed in order by class name.
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

        val sortedExporters = exporters.sortedBy { it::class.java.name }
        for (exporter in sortedExporters) {
            exporter.export(report, outputDir)
        }

        return report
    }
}
