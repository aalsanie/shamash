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
package io.shamash.export.writers.html

import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.export.api.Exporter
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.text.iterator

/**
 * HTML exporter for schema v1.
 */
class HtmlExporter : Exporter {
    override fun export(
        report: ExportedReport,
        outputDir: Path,
    ) {
        val outputFile = outputDir.resolve(ExportOutputLayout.HTML_FILE_NAME)
        Files.newOutputStream(outputFile).use { os ->
            BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8)).use { writer ->
                writeHtml(writer, report)
            }
        }
    }

    private fun writeHtml(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        writer.append("<!doctype html>").append('\n')
        writer.append("<html lang=\"en\">").append('\n')
        writer.append("<head>").append('\n')
        writer.append("  <meta charset=\"utf-8\"/>").append('\n')
        writer.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>").append('\n')
        writer.append("  <title>Shamash Report</title>").append('\n')
        writer.append("  <style>").append('\n')
        writer.append(CSS).append('\n')
        writer.append("  </style>").append('\n')
        writer.append("</head>").append('\n')
        writer.append("<body>").append('\n')

        writeHeader(writer, report)
        writeSummary(writer, report)
        writeTable(writer, report.findings)

        writer.append("</body>").append('\n')
        writer.append("</html>").append('\n')
    }

    private fun writeHeader(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        writer.append("<header class=\"header\">").append('\n')
        writer.append("  <div class=\"title\">Shamash Report</div>").append('\n')
        writer
            .append("  <div class=\"meta\">")
            .append(escapeHtml(report.tool.name))
            .append(" v")
            .append(escapeHtml(report.tool.version))
            .append(" · schema ")
            .append(escapeHtml(report.tool.schemaVersion))
            .append(" · ")
            .append(report.tool.generatedAtEpochMillis.toString())
            .append("</div>")
            .append('\n')
        writer.append("</header>").append('\n')
    }

    private fun writeSummary(
        writer: BufferedWriter,
        report: ExportedReport,
    ) {
        writer.append("<section class=\"card\">").append('\n')
        writer
            .append("  <div class=\"row\"><span class=\"label\">Project</span><span>")
            .append(escapeHtml(report.project.name))
            .append("</span></div>")
            .append('\n')
        writer
            .append("  <div class=\"row\"><span class=\"label\">Base path</span><span class=\"mono\">")
            .append(escapeHtml(report.project.basePath))
            .append("</span></div>")
            .append('\n')
        writer
            .append("  <div class=\"row\"><span class=\"label\">Findings</span><span>")
            .append(report.findings.size.toString())
            .append("</span></div>")
            .append('\n')
        writer.append("</section>").append('\n')
    }

    private fun writeTable(
        writer: BufferedWriter,
        findings: List<ExportedFinding>,
    ) {
        writer.append("<section class=\"card\">").append('\n')
        writer.append("  <div class=\"tableWrap\">").append('\n')
        writer.append("    <table>").append('\n')
        writer.append("      <thead>").append('\n')
        writer
            .append("        <tr>")
            .append("<th>Severity</th>")
            .append("<th>Rule</th>")
            .append("<th>File</th>")
            .append("<th>Owner</th>")
            .append("<th>Fingerprint</th>")
            .append("<th>Message</th>")
            .append("</tr>")
            .append('\n')
        writer.append("      </thead>").append('\n')
        writer.append("      <tbody>").append('\n')

        for (finding in findings) {
            writeRow(writer, finding)
        }

        writer.append("      </tbody>").append('\n')
        writer.append("    </table>").append('\n')
        writer.append("  </div>").append('\n')
        writer.append("</section>").append('\n')
    }

    private fun writeRow(
        writer: BufferedWriter,
        finding: ExportedFinding,
    ) {
        val owner = buildOwner(finding)

        writer.append("        <tr>").append('\n')

        val sevName = finding.severity.name
        writer
            .append("          <td><span class=\"badge ")
            .append(severityBadgeClass(finding))
            .append("\">")
            .append(escapeHtml(sevName))
            .append("</span></td>")
            .append('\n')

        writer
            .append("          <td class=\"mono\">")
            .append(escapeHtml(finding.ruleId))
            .append("</td>")
            .append('\n')
        writer
            .append("          <td class=\"mono\">")
            .append(escapeHtml(finding.filePath))
            .append("</td>")
            .append('\n')
        writer
            .append("          <td class=\"mono\">")
            .append(escapeHtml(owner))
            .append("</td>")
            .append('\n')
        writer
            .append("          <td class=\"mono\">")
            .append(escapeHtml(finding.fingerprint))
            .append("</td>")
            .append('\n')
        writer
            .append("          <td>")
            .append(escapeHtml(finding.message))
            .append("</td>")
            .append('\n')

        writer.append("        </tr>").append('\n')
    }

    private fun buildOwner(finding: ExportedFinding): String {
        val classFqn = finding.classFqn?.trim().orEmpty()
        val member = finding.memberName?.trim().orEmpty()

        return when {
            classFqn.isNotEmpty() && member.isNotEmpty() -> "$classFqn#$member"
            classFqn.isNotEmpty() -> classFqn
            member.isNotEmpty() -> member
            else -> ""
        }
    }

    private fun severityBadgeClass(finding: ExportedFinding): String =
        when (finding.severity.name) {
            "ERROR" -> "badgeError"
            "WARNING" -> "badgeWarn"
            else -> "badgeInfo"
        }

    private fun escapeHtml(value: String): String {
        if (value.isEmpty()) return value

        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private companion object {
        private const val CSS = // unchanged
            "body{font-family:ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,Arial; margin:0; " +
                "padding:24px; background:#0b0f17; color:#e7eefc;}\n" +
                ".header{display:flex; flex-direction:column; gap:6px; margin-bottom:16px;}\n" +
                ".title{font-size:22px; font-weight:700;}\n" +
                ".meta{font-size:12px; color:#a9b7d0;}\n" +
                ".card{background:#101826; border:1px solid #1e2a40; border-radius:14px; " +
                "padding:14px; margin-bottom:14px; box-shadow:0 6px 18px rgba(0,0,0,.25);} \n" +
                ".row{display:flex; justify-content:space-between; gap:16px; padding:6px 0; " +
                "border-bottom:1px solid #1b263a;}\n" +
                ".row:last-child{border-bottom:none;}\n" +
                ".label{color:#a9b7d0; min-width:90px;}\n" +
                ".mono{font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace; " +
                "font-size:12px;}\n" +
                ".tableWrap{overflow:auto;}\n" +
                "table{width:100%; border-collapse:separate; border-spacing:0;}\n" +
                "th,td{padding:10px 10px; text-align:left; vertical-align:top; border-bottom:1px " +
                "solid #1b263a;}\n" +
                "th{font-size:12px; color:#a9b7d0; font-weight:600; position:sticky; top:0; " +
                "background:#101826;}\n" +
                "td{font-size:13px;}\n" +
                ".badge{display:inline-block; padding:2px 8px; border-radius:999px; font-size:12px; " +
                "font-weight:700;}\n" +
                ".badgeError{background:#3b0d14; color:#ffb3bd; border:1px solid #6b1321;}\n" +
                ".badgeWarn{background:#2b2106; color:#ffe0a3; border:1px solid #6a4a0a;}\n" +
                ".badgeInfo{background:#0b2235; color:#b8dcff; border:1px solid #123a5a;}\n"
    }
}
