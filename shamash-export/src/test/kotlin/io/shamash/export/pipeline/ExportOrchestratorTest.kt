/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
