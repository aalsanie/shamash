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
package io.shamash.psi.export.xml

import io.shamash.psi.export.ExportOutputLayout
import io.shamash.psi.export.Exporter
import io.shamash.psi.export.schema.v1.model.ExportedFinding
import io.shamash.psi.export.schema.v1.model.ExportedReport
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * XML exporter for schema v1.
 */
class XmlExporter : Exporter {
    override fun export(
        report: ExportedReport,
        outputDir: Path,
    ) {
        Files.createDirectories(outputDir)

        val outputFile = outputDir.resolve(ExportOutputLayout.XML_FILE_NAME)
        Files.newOutputStream(outputFile).use { os ->
            BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8)).use { writer ->
                writeXml(writer, report)
            }
        }
    }

    private fun writeXml(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        writer.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>").append('\n')
        writer
            .append("<shamashReport schemaVersion=\"")
            .append(escapeXml(report.tool.schemaVersion))
            .append("\" generatedAtEpochMillis=\"")
            .append(report.tool.generatedAtEpochMillis.toString())
            .append("\">")
            .append('\n')

        writer
            .append("  <tool name=\"")
            .append(escapeXml(report.tool.name))
            .append("\" version=\"")
            .append(escapeXml(report.tool.version))
            .append("\"/>")
            .append('\n')

        writer
            .append("  <project name=\"")
            .append(escapeXml(report.project.name))
            .append("\" basePath=\"")
            .append(escapeXml(report.project.basePath))
            .append("\"/>")
            .append('\n')

        writer
            .append("  <findings count=\"")
            .append(report.findings.size.toString())
            .append("\">")
            .append('\n')
        for (finding in report.findings) {
            writeFinding(writer, finding)
        }
        writer.append("  </findings>").append('\n')
        writer.append("</shamashReport>").append('\n')
    }

    private fun writeFinding(
        writer: BufferedWriter,
        finding: ExportedFinding,
    ) {
        writer
            .append("    <finding")
            .append(" ruleId=\"")
            .append(escapeXml(finding.ruleId))
            .append('"')
            .append(" severity=\"")
            .append(escapeXml(finding.severity.name))
            .append('"')
            .append(" filePath=\"")
            .append(escapeXml(finding.filePath))
            .append('"')
            .append(" fingerprint=\"")
            .append(escapeXml(finding.fingerprint))
            .append('"')

        if (finding.classFqn != null) {
            writer.append(" classFqn=\"").append(escapeXml(finding.classFqn)).append('"')
        }
        if (finding.memberName != null) {
            writer.append(" memberName=\"").append(escapeXml(finding.memberName)).append('"')
        }

        writer.append('>').append('\n')
        writer
            .append("      <message>")
            .append(escapeXml(finding.message))
            .append("</message>")
            .append('\n')
        writer.append("    </finding>").append('\n')
    }

    private fun escapeXml(value: String): String {
        if (value.isEmpty()) return value

        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
