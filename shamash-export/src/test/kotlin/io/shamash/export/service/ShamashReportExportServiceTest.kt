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
package io.shamash.export.service

import io.shamash.artifacts.baseline.BaselineConfig
import io.shamash.artifacts.baseline.BaselineFingerprint
import io.shamash.artifacts.baseline.BaselineMode
import io.shamash.artifacts.baseline.BaselineStore
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.util.PathNormalizer
import io.shamash.export.pipeline.FindingPreprocessor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShamashReportExportServiceTest {
    @Test
    fun export_baselineOff_writesAllReportFormats_toDefaultOutputDir() {
        val base = Files.createTempDirectory("shamash-export-service-")

        val findings =
            listOf(
                Finding(
                    ruleId = "R",
                    message = "m",
                    filePath = base.resolve("src").resolve("A.kt").toString(),
                    severity = FindingSeverity.ERROR,
                ),
            )

        val service = ShamashReportExportService()
        val result =
            service.export(
                projectBasePath = base,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = findings,
                baseline = BaselineConfig.OFF,
                exceptionsPreprocessor = FindingPreprocessor { _, fs -> fs },
                generatedAtEpochMillis = 1L,
            )

        assertEquals(1, result.report.findings.size)

        val out = result.outputDir
        assertEquals(base.resolve(ExportOutputLayout.DEFAULT_DIR_NAME).normalize(), out)

        assertTrue(Files.exists(out.resolve(ExportOutputLayout.JSON_FILE_NAME)))
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.XML_FILE_NAME)))
        assertTrue(Files.exists(out.resolve(ExportOutputLayout.HTML_FILE_NAME)))

        deleteRecursively(base)
    }

    @Test
    fun export_generateBaseline_respectsExceptions_and_writesBaselineFile() {
        val base = Files.createTempDirectory("shamash-export-service-")

        val drop =
            Finding(
                ruleId = "DROP",
                message = "drop",
                filePath = base.resolve("src").resolve("Drop.kt").toString(),
                severity = FindingSeverity.ERROR,
            )
        val keep =
            Finding(
                ruleId = "KEEP",
                message = "keep",
                filePath = base.resolve("src").resolve("Keep.kt").toString(),
                severity = FindingSeverity.WARNING,
            )

        val exceptions = FindingPreprocessor { _, fs -> fs.filterNot { it.ruleId == "DROP" } }

        val service = ShamashReportExportService()
        val result =
            service.export(
                projectBasePath = base,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = listOf(drop, keep),
                baseline = BaselineConfig(mode = BaselineMode.GENERATE, writeMerged = true),
                exceptionsPreprocessor = exceptions,
                generatedAtEpochMillis = 1L,
            )

        assertTrue(result.baselineWritten)

        val out = result.outputDir
        val store = BaselineStore()
        val baselinePath = store.baselinePath(out)
        assertTrue(Files.exists(baselinePath))

        val loaded = store.load(out)!!.fingerprints

        val normalized = PathNormalizer.relativizeOrNormalize(base, Paths.get(keep.filePath))
        val expectedFingerprint = BaselineFingerprint.sha256Hex(keep, normalized)

        assertEquals(setOf(expectedFingerprint), loaded)

        deleteRecursively(base)
    }

    @Test
    fun export_useBaseline_suppressesBaselineFindings() {
        val base = Files.createTempDirectory("shamash-export-service-")

        val finding =
            Finding(
                ruleId = "R",
                message = "m",
                filePath = base.resolve("src").resolve("A.kt").toString(),
                severity = FindingSeverity.ERROR,
            )

        val service = ShamashReportExportService()

        // First run: generate baseline
        val gen =
            service.export(
                projectBasePath = base,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = listOf(finding),
                baseline = BaselineConfig(mode = BaselineMode.GENERATE, writeMerged = true),
                exceptionsPreprocessor = null,
                generatedAtEpochMillis = 1L,
            )
        assertTrue(gen.baselineWritten)

        // Second run: use baseline -> suppressed
        val used =
            service.export(
                projectBasePath = base,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = listOf(finding),
                baseline = BaselineConfig(mode = BaselineMode.USE, writeMerged = true),
                exceptionsPreprocessor = null,
                generatedAtEpochMillis = 2L,
            )

        assertEquals(0, used.report.findings.size)

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
