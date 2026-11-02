/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.writers.html

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class HtmlExporterTest {
    @Test
    fun export_writesHtml_withEscapedText_andOwnerAndBadges() {
        val out = Files.createTempDirectory("shamash-html-export-")

        val report =
            ExportedReport(
                tool = ToolMetadata("tool", "1.0", "v1", 1000L),
                project = ProjectMetadata("proj", "/base"),
                findings =
                    listOf(
                        ExportedFinding(
                            ruleId = "R",
                            message = "<b>bad</b> & \"quoted\"",
                            severity = FindingSeverity.ERROR,
                            filePath = "src/A.kt",
                            classFqn = "com.A",
                            memberName = "m",
                            fingerprint = "fp",
                        ),
                    ),
            )

        HtmlExporter().export(report, out)

        val html = Files.readString(out.resolve(ExportOutputLayout.HTML_FILE_NAME))

        assertTrue(html.contains("<!doctype html>"))
        assertTrue(html.contains("Shamash Report"))

        assertTrue(html.contains("com.A#m"))

        assertTrue(html.contains("badgeError"))

        assertTrue(html.contains("&lt;b&gt;bad&lt;/b&gt; &amp; &quot;quoted&quot;"))

        deleteRecursively(out)
    }

    private fun deleteRecursively(root: java.nio.file.Path) {
        if (!Files.exists(root)) return
        Files
            .walk(root)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
