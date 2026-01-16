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
package io.shamash.export.pipeline

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultExportPipelineFactoryTest {
    @Test
    fun create_appliesPreprocessorsInDeclaredOrder_exceptionsThenBaseline() {
        val base = Files.createTempDirectory("shamash-export-factory-")
        val out = base.resolve("out")

        val calls = ArrayList<String>()
        val exceptions =
            FindingPreprocessor { _, fs ->
                calls += "exceptions"
                fs.filterNot { it.ruleId == "DROP" }
            }
        val baseline =
            FindingPreprocessor { _, fs ->
                calls += "baseline"
                // baseline must see already filtered results
                if (fs.any { it.ruleId == "DROP" }) {
                    error("baseline preprocessor should run after exceptions preprocessor")
                }
                fs
            }

        val orchestrator = DefaultExportPipelineFactory.create(exceptions, baseline)

        val findings =
            listOf(
                Finding(
                    ruleId = "DROP",
                    message = "to be removed",
                    filePath = base.resolve("A.kt").toString(),
                    severity = FindingSeverity.ERROR,
                ),
                Finding(
                    ruleId = "KEEP",
                    message = "keep",
                    filePath = base.resolve("B.kt").toString(),
                    severity = FindingSeverity.WARNING,
                ),
            )

        val report =
            orchestrator.export(
                projectBasePath = base,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = findings,
                outputDir = out,
                generatedAtEpochMillis = 7L,
            )

        assertEquals(listOf("exceptions", "baseline"), calls)
        assertEquals(1, report.findings.size)
        assertEquals("KEEP", report.findings.single().ruleId)

        // Exporters are wired by default factory (ALL formats).
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.JSON_FILE_NAME)))
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.XML_FILE_NAME)))
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.HTML_FILE_NAME)))

        deleteRecursively(base)
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files
            .walk(root)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
