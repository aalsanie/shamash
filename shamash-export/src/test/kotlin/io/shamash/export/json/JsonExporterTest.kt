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

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class JsonExporterTest {
    @Test
    fun export_writesStableJson_withEscapedStrings_andOptionalFields() {
        val out = Files.createTempDirectory("shamash-json-export-")

        val report =
            ExportedReport(
                tool = ToolMetadata("tool", "1.0", "v1", 1000L),
                project = ProjectMetadata("proj", "/base"),
                findings =
                    listOf(
                        ExportedFinding(
                            ruleId = "R\\\\1",
                            message = "Hello \"world\"\nline2",
                            severity = FindingSeverity.ERROR,
                            filePath = "src/Main.kt",
                            classFqn = "com.A",
                            memberName = "m",
                            fingerprint = "abc123",
                        ),
                    ),
            )

        JsonExporter().export(report, out)

        val json = Files.readString(out.resolve(ExportOutputLayout.JSON_FILE_NAME))

        // Key structure
        assertTrue(json.contains("\"tool\""))
        assertTrue(json.contains("\"project\""))
        assertTrue(json.contains("\"findings\""))

        // Escaping
        assertTrue(json.contains("\"ruleId\": \"R\\\\\\\\1\"")) // R\\1 in JSON
        assertTrue(json.contains("Hello \\\"world\\\"\\nline2"))

        // Optional fields present
        assertTrue(json.contains("\"classFqn\": \"com.A\""))
        assertTrue(json.contains("\"memberName\": \"m\""))

        // Fingerprint always present
        assertTrue(json.contains("\"fingerprint\": \"abc123\""))

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
