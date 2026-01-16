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
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.export.api.Exporter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExportOrchestratorTest {
    @Test
    fun export_buildsReportOnce_and_invokesExportersInOrder_withSameReportInstance() {
        val base = Files.createTempDirectory("shamash-export-orch-")
        val output = base.resolve("out")
        Files.createDirectories(output)

        val findings =
            listOf(
                Finding(
                    ruleId = "R",
                    message = "m",
                    filePath = base.resolve("A.kt").toString(),
                    severity = FindingSeverity.ERROR,
                ),
            )

        val built = ArrayList<ExportedReport>()

        val reportBuilder =
            ReportBuilder(
                findingPreprocessors =
                    listOf(
                        FindingPreprocessor { _, fs ->
                            // Touch the pipeline to ensure it still works
                            fs
                        },
                    ),
            )

        val calls = ArrayList<String>()
        val exporter1 =
            object : Exporter {
                override fun export(
                    report: ExportedReport,
                    outputDir: Path,
                ) {
                    calls.add("e1")
                    built.add(report)
                }
            }
        val exporter2 =
            object : Exporter {
                override fun export(
                    report: ExportedReport,
                    outputDir: Path,
                ) {
                    calls.add("e2")
                    built.add(report)
                }
            }

        val orchestrator = ExportOrchestrator(reportBuilder, listOf(exporter1, exporter2))

        val returned =
            orchestrator.export(
                projectBasePath = base,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = findings,
                outputDir = output,
                generatedAtEpochMillis = 42L,
            )

        assertEquals(listOf("e1", "e2"), calls)
        assertEquals(2, built.size)
        assertNotNull(returned)

        // Both exporters see the exact same report instance that is returned to the caller.
        assertEquals(System.identityHashCode(returned), System.identityHashCode(built[0]))
        assertEquals(System.identityHashCode(returned), System.identityHashCode(built[1]))

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
