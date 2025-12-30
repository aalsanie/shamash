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
package io.shamash.psi.export.sarif

import io.shamash.psi.engine.FindingSeverity
import io.shamash.psi.export.ExportOutputLayout
import io.shamash.psi.export.Exporter
import io.shamash.psi.export.json.JsonEscaper
import io.shamash.psi.export.schema.v1.model.ExportedFinding
import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * SARIF 2.1.0 exporter.
 */
class SarifExporter : Exporter {
    override fun export(
        report: ExportedReport,
        outputDir: Path,
    ) {
        Files.createDirectories(outputDir)

        val outputFile = outputDir.resolve(ExportOutputLayout.SARIF_FILE_NAME)
        Files.newOutputStream(outputFile).use { os ->
            BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8)).use { writer ->
                writeSarif(writer, report)
            }
        }
    }

    private fun writeSarif(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        val ruleIds =
            report.findings
                .asSequence()
                .map { it.ruleId }
                .distinct()
                .sorted()
                .toList()

        writer.append('{').append('\n')
        writer.append("  \"version\": \"2.1.0\",").append('\n')
        writer.append("  \"\$schema\": \"https://json.schemastore.org/sarif-2.1.0.json\",").append('\n')
        writer.append("  \"runs\": [").append('\n')
        writer.append("    {").append('\n')

        writeTool(writer, report, ruleIds)
        writer.append(',').append('\n')
        writeInvocations(writer, report)
        writer.append(',').append('\n')
        writeResults(writer, report.findings)

        writer.append('\n')
        writer.append("    }").append('\n')
        writer.append("  ]").append('\n')
        writer.append('}')
    }

    private fun writeTool(
        writer: BufferedWriter,
        report: ExportedReport,
        ruleIds: List<String>,
    ) {
        val tool = report.tool

        writer.append("      \"tool\": {").append('\n')
        writer.append("        \"driver\": {").append('\n')
        writer
            .append("          \"name\": \"")
            .append(JsonEscaper.escapeString(tool.name))
            .append("\",")
            .append('\n')
        writer
            .append("          \"version\": \"")
            .append(JsonEscaper.escapeString(tool.version))
            .append("\",")
            .append('\n')
        writer.append("          \"informationUri\": \"https://github.com/aalsanie/shamash\",").append('\n')
        writer.append("          \"rules\": [").append('\n')

        ruleIds.forEachIndexed { index, ruleId ->
            writer.append("            {").append('\n')
            writer
                .append("              \"id\": \"")
                .append(JsonEscaper.escapeString(ruleId))
                .append("\",")
                .append('\n')
            writer
                .append("              \"name\": \"")
                .append(JsonEscaper.escapeString(ruleId))
                .append("\",")
                .append('\n')
            writer
                .append(
                    "              \"shortDescription\": { \"text\": \"",
                ).append(JsonEscaper.escapeString(ruleId))
                .append("\" }")
                .append('\n')
            writer.append("            }")
            if (index < ruleIds.size - 1) writer.append(',')
            writer.append('\n')
        }

        writer.append("          ]").append('\n')
        writer.append("        }").append('\n')
        writer.append("      }")
    }

    private fun writeInvocations(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        writer.append("      \"invocations\": [").append('\n')
        writer.append("        {").append('\n')
        writer.append("          \"executionSuccessful\": true,").append('\n')
        writer
            .append("          \"startTimeUtc\": \"")
            .append(toIsoUtc(report.tool.generatedAtEpochMillis))
            .append("\"")
            .append('\n')
        writer.append("        }").append('\n')
        writer.append("      ]")
    }

    private fun writeResults(
        writer: BufferedWriter,
        findings: List<ExportedFinding>,
    ) {
        writer.append("      \"results\": [").append('\n')

        findings.forEachIndexed { index, finding ->
            writeResult(writer, finding)
            if (index < findings.size - 1) writer.append(',')
            writer.append('\n')
        }

        writer.append("      ]")
    }

    private fun writeResult(
        writer: BufferedWriter,
        finding: ExportedFinding,
    ) {
        writer.append("        {").append('\n')
        writer
            .append("          \"ruleId\": \"")
            .append(JsonEscaper.escapeString(finding.ruleId))
            .append("\",")
            .append('\n')
        writer
            .append("          \"level\": \"")
            .append(mapLevel(finding.severity))
            .append("\",")
            .append('\n')
        writer
            .append(
                "          \"message\": { \"text\": \"",
            ).append(JsonEscaper.escapeString(finding.message))
            .append("\" },")
            .append('\n')

        writer
            .append("          \"partialFingerprints\": { \"primaryLocationLineHash\": \"")
            .append(JsonEscaper.escapeString(finding.fingerprint))
            .append("\" },")
            .append('\n')

        writer.append("          \"locations\": [").append('\n')
        writer.append("            {").append('\n')
        writer.append("              \"physicalLocation\": {").append('\n')
        writer
            .append("                \"artifactLocation\": { \"uri\": \"")
            .append(JsonEscaper.escapeString(finding.filePath))
            .append("\" }")
            .append('\n')
        writer.append("              }").append('\n')
        writer.append("            }").append('\n')
        writer.append("          ]").append('\n')

        writer.append("        }")
    }

    private fun mapLevel(severity: FindingSeverity): String =
        when (severity) {
            FindingSeverity.ERROR -> "error"
            FindingSeverity.WARNING -> "warning"
            FindingSeverity.INFO -> "note"
        }

    private fun toIsoUtc(epochMillis: Long): String {
        val instant = Instant.ofEpochMilli(epochMillis)
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
