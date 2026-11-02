/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.writers.xml

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class XmlExporterTest {
    @Test
    fun export_writesXml_withEscapedAttributes_andMessage() {
        val out = Files.createTempDirectory("shamash-xml-export-")

        val report =
            ExportedReport(
                tool = ToolMetadata("tool&", "1.0", "v1", 1000L),
                project = ProjectMetadata("proj<>", "/base"),
                findings =
                    listOf(
                        ExportedFinding(
                            ruleId = "R&<>",
                            message = "a & b < c > d \" q '",
                            severity = FindingSeverity.WARNING,
                            filePath = "src/A&.kt",
                            classFqn = null,
                            memberName = null,
                            fingerprint = "fp&<>",
                        ),
                    ),
            )

        XmlExporter().export(report, out)

        val xml = Files.readString(out.resolve(ExportOutputLayout.XML_FILE_NAME))

        assertTrue(xml.contains("<shamashReport"))
        assertTrue(xml.contains("schemaVersion=\"v1\""))

        assertTrue(xml.contains("name=\"tool&amp;\""))
        assertTrue(xml.contains("project name=\"proj&lt;&gt;\""))
        assertTrue(xml.contains("ruleId=\"R&amp;&lt;&gt;\""))
        assertTrue(xml.contains("filePath=\"src/A&amp;.kt\""))
        assertTrue(xml.contains("fingerprint=\"fp&amp;&lt;&gt;\""))

        assertTrue(xml.contains("<message>a &amp; b &lt; c &gt; d &quot; q &apos;</message>"))

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
