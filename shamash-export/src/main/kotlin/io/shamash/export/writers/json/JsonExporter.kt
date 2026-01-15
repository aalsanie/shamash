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
package io.shamash.export.writers.json

import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.util.json.JsonEscaper
import io.shamash.export.api.Exporter
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON exporter for schema v1.
 *
 * Characteristics:
 * - No external JSON library (full control + stability).
 * - Stable field ordering.
 * - UTF-8 encoded.
 */
class JsonExporter : Exporter {
    override fun export(
        report: ExportedReport,
        outputDir: Path,
    ) {
        Files.createDirectories(outputDir)

        val outputFile = outputDir.resolve(ExportOutputLayout.JSON_FILE_NAME)
        Files.newOutputStream(outputFile).use { os ->
            BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8)).use { writer ->
                writeReport(writer, report)
            }
        }
    }

    private fun writeReport(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        writer.append('{').append('\n')

        writeTool(writer, report)
        writer.append(',').append('\n')
        writeProject(writer, report)
        writer.append(',').append('\n')
        writeFindings(writer, report.findings)
        writer.append('\n')

        writer.append('}')
    }

    private fun writeTool(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        val tool = report.tool
        writer.append("  \"tool\": {").append('\n')
        writer
            .append("    \"name\": \"")
            .append(JsonEscaper.escape(tool.name))
            .append("\",")
            .append('\n')
        writer
            .append("    \"version\": \"")
            .append(JsonEscaper.escape(tool.version))
            .append("\",")
            .append('\n')
        writer
            .append("    \"schemaVersion\": \"")
            .append(JsonEscaper.escape(tool.schemaVersion))
            .append("\",")
            .append('\n')
        writer.append("    \"generatedAtEpochMillis\": ").append(tool.generatedAtEpochMillis.toString()).append('\n')
        writer.append("  }")
    }

    private fun writeProject(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        val project = report.project
        writer.append("  \"project\": {").append('\n')
        writer
            .append("    \"name\": \"")
            .append(JsonEscaper.escape(project.name))
            .append("\",")
            .append('\n')
        writer
            .append("    \"basePath\": \"")
            .append(JsonEscaper.escape(project.basePath))
            .append("\"")
            .append('\n')
        writer.append("  }")
    }

    private fun writeFindings(
        writer: BufferedWriter,
        findings: List<ExportedFinding>,
    ) {
        writer.append("  \"findings\": [").append('\n')

        findings.forEachIndexed { index, finding ->
            writeFinding(writer, finding)
            if (index < findings.size - 1) {
                writer.append(',')
            }
            writer.append('\n')
        }

        writer.append("  ]")
    }

    private fun writeFinding(
        writer: BufferedWriter,
        finding: ExportedFinding,
    ) {
        writer.append("    {").append('\n')
        writer
            .append("      \"ruleId\": \"")
            .append(JsonEscaper.escape(finding.ruleId))
            .append("\",")
            .append('\n')
        writer
            .append("      \"message\": \"")
            .append(JsonEscaper.escape(finding.message))
            .append("\",")
            .append('\n')
        writer
            .append("      \"severity\": \"")
            .append(finding.severity.name)
            .append("\",")
            .append('\n')
        writer
            .append("      \"filePath\": \"")
            .append(JsonEscaper.escape(finding.filePath))
            .append("\",")
            .append('\n')

        // Baseline-ready and deterministic:
        writer.append("      \"fingerprint\": \"").append(JsonEscaper.escape(finding.fingerprint)).append('"')

        if (finding.classFqn != null) {
            writer.append(',').append('\n')
            writer
                .append("      \"classFqn\": \"")
                .append(JsonEscaper.escape(finding.classFqn!!))
                .append("\"")
        }

        if (finding.memberName != null) {
            writer.append(',').append('\n')
            writer
                .append("      \"memberName\": \"")
                .append(JsonEscaper.escape(finding.memberName!!))
                .append("\"")
        }

        writer.append('\n')
        writer.append("    }")
    }
}
