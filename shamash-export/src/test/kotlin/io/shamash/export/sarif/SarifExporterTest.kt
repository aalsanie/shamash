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
package io.shamash.export.writers.sarif

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedFinding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class SarifExporterTest {
    @Test
    fun export_writesSarif21_withRuleCatalog_levels_andStartTimeUtc() {
        val out = Files.createTempDirectory("shamash-sarif-export-")

        val report =
            ExportedReport(
                tool = ToolMetadata("tool", "1.0", "v1", 0L),
                project = ProjectMetadata("proj", "/base"),
                findings =
                    listOf(
                        ExportedFinding(
                            ruleId = "R1",
                            message = "m1",
                            severity = FindingSeverity.ERROR,
                            filePath = "a.kt",
                            classFqn = null,
                            memberName = null,
                            fingerprint = "fp1",
                        ),
                        ExportedFinding(
                            ruleId = "R2",
                            message = "m2",
                            severity = FindingSeverity.WARNING,
                            filePath = "b.kt",
                            classFqn = null,
                            memberName = null,
                            fingerprint = "fp2",
                        ),
                        ExportedFinding(
                            ruleId = "R3",
                            message = "m3",
                            severity = FindingSeverity.INFO,
                            filePath = "c.kt",
                            classFqn = null,
                            memberName = null,
                            fingerprint = "fp3",
                        ),
                    ),
            )

        SarifExporter().export(report, out)

        val sarif = Files.readString(out.resolve(ExportOutputLayout.SARIF_FILE_NAME))

        assertTrue(sarif.contains("\"version\": \"2.1.0\""))
        // Start time from epoch 0
        assertTrue(sarif.contains("\"startTimeUtc\": \"1970-01-01T00:00:00Z\""))

        // Rules are catalogued (ids appear in tool.rules)
        assertTrue(sarif.contains("\"id\": \"R1\""))
        assertTrue(sarif.contains("\"id\": \"R2\""))
        assertTrue(sarif.contains("\"id\": \"R3\""))

        // Level mapping
        assertTrue(sarif.contains("\"ruleId\": \"R1\""))
        assertTrue(sarif.contains("\"level\": \"error\""))
        assertTrue(sarif.contains("\"ruleId\": \"R2\""))
        assertTrue(sarif.contains("\"level\": \"warning\""))
        assertTrue(sarif.contains("\"ruleId\": \"R3\""))
        assertTrue(sarif.contains("\"level\": \"note\""))

        // Fingerprint is emitted as partial fingerprint
        assertTrue(sarif.contains("\"primaryLocationLineHash\": \"fp1\""))

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
