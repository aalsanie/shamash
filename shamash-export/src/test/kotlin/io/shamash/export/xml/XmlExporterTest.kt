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

        // Escaping in attributes
        assertTrue(xml.contains("name=\"tool&amp;\""))
        assertTrue(xml.contains("project name=\"proj&lt;&gt;\""))
        assertTrue(xml.contains("ruleId=\"R&amp;&lt;&gt;\""))
        assertTrue(xml.contains("filePath=\"src/A&amp;.kt\""))
        assertTrue(xml.contains("fingerprint=\"fp&amp;&lt;&gt;\""))

        // Escaping in message element
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
