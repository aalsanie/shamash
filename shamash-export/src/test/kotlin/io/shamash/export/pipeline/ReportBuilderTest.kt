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

import io.shamash.artifacts.baseline.BaselineFingerprint
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportBuilderTest {
    private val tempDir: Path = Files.createTempDirectory("shamash-export-reportbuilder-")

    @AfterTest
    fun tearDown() {
        deleteRecursively(tempDir)
    }

    @Test
    fun build_appliesPreprocessorsInOrder() {
        val callOrder = ArrayList<String>()

        val p1 =
            FindingPreprocessor { _, fs ->
                callOrder += "p1"
                fs +
                    Finding(
                        ruleId = "R2",
                        message = "added by p1",
                        filePath = tempDir.resolve("A.kt").toString(),
                        severity = FindingSeverity.INFO,
                    )
            }

        val p2 =
            FindingPreprocessor { _, fs ->
                callOrder += "p2"
                // keep only the finding added by p1
                fs.filter { it.ruleId == "R2" }
            }

        val builder = ReportBuilder(listOf(p1, p2))

        val initial =
            listOf(
                Finding(
                    ruleId = "R1",
                    message = "orig",
                    filePath = tempDir.resolve("A.kt").toString(),
                    severity = FindingSeverity.ERROR,
                ),
            )

        val report =
            builder.build(
                projectBasePath = tempDir,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = initial,
                generatedAtEpochMillis = 123L,
            )

        assertEquals(listOf("p1", "p2"), callOrder)
        assertEquals(1, report.findings.size)
        assertEquals("R2", report.findings.single().ruleId)
    }

    @Test
    fun build_computesBaselineReadyFingerprint_and_normalizesOptionals() {
        val file = tempDir.resolve("src").resolve("Main.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "// noop")

        val f1 =
            Finding(
                ruleId = "R",
                message = "message-one",
                filePath = file.toString(),
                severity = FindingSeverity.WARNING,
                classFqn = "  com.example.A  ",
                memberName = "   ",
                data = linkedMapOf("b" to "2", "a" to "1"),
                startOffset = 10,
                endOffset = 20,
            )

        val f2 =
            Finding(
                ruleId = "R",
                message = "message-two",
                filePath = file.toString(),
                severity = FindingSeverity.WARNING,
                classFqn = "com.example.A",
                memberName = null,
                data = linkedMapOf("a" to "1", "b" to "2"),
                startOffset = 10,
                endOffset = 20,
            )

        val report =
            ReportBuilder().build(
                projectBasePath = tempDir,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings = listOf(f2, f1),
                generatedAtEpochMillis = 123L,
            )

        assertEquals(2, report.findings.size)

        val e1 = report.findings[0]
        val e2 = report.findings[1]

        // Deterministic sorting: both findings are identical except message, so message is the tiebreaker.
        assertEquals("message-one", e1.message)
        assertEquals("message-two", e2.message)

        // filePath must be project-relative and normalized (forward slashes)
        assertEquals(
            io.shamash.artifacts.util.PathNormalizer
                .relativizeOrNormalize(tempDir.toAbsolutePath().normalize(), file),
            e1.filePath,
        )

        // Optional normalization
        assertEquals("com.example.A", e1.classFqn)
        assertNull(e1.memberName)

        // Fingerprint must ignore message and must be stable regardless of data map insertion order.
        assertEquals(e1.fingerprint, e2.fingerprint)

        val expectedFingerprint = BaselineFingerprint.sha256Hex(f1, e1.filePath)
        assertEquals(expectedFingerprint, e1.fingerprint)
    }

    @Test
    fun build_sortsFindingsDeterministically_byPathRuleSeverityOwnerFingerprint() {
        val fA = tempDir.resolve("a").resolve("File.kt")
        val fB = tempDir.resolve("b").resolve("File.kt")
        Files.createDirectories(fA.parent)
        Files.createDirectories(fB.parent)
        Files.writeString(fA, "// a")
        Files.writeString(fB, "// b")

        val sameRulePathError =
            Finding(
                ruleId = "R1",
                message = "m",
                filePath = fA.toString(),
                severity = FindingSeverity.ERROR,
                classFqn = "com.A",
                memberName = "x",
            )
        val sameRulePathWarn = sameRulePathError.copy(severity = FindingSeverity.WARNING)
        val sameRulePathInfo = sameRulePathError.copy(severity = FindingSeverity.INFO)

        val differentRuleSamePath = sameRulePathError.copy(ruleId = "R0")
        val differentPath = sameRulePathError.copy(filePath = fB.toString(), ruleId = "R0")

        val report =
            ReportBuilder().build(
                projectBasePath = tempDir,
                projectName = "proj",
                toolName = "tool",
                toolVersion = "1.0",
                findings =
                    listOf(
                        sameRulePathInfo,
                        sameRulePathWarn,
                        differentPath,
                        sameRulePathError,
                        differentRuleSamePath,
                    ),
                generatedAtEpochMillis = 1L,
            )

        // Sort order:
        // 1) filePath (a/File.kt before b/File.kt)
        // 2) ruleId (R0 before R1)
        // 3) severity rank (ERROR, WARNING, INFO)
        val exported = report.findings
        assertEquals("a/File.kt", exported[0].filePath)
        assertEquals("R0", exported[0].ruleId)

        assertEquals("a/File.kt", exported[1].filePath)
        assertEquals("R1", exported[1].ruleId)
        assertEquals(FindingSeverity.ERROR, exported[1].severity)

        assertEquals("a/File.kt", exported[2].filePath)
        assertEquals("R1", exported[2].ruleId)
        assertEquals(FindingSeverity.WARNING, exported[2].severity)

        assertEquals("a/File.kt", exported[3].filePath)
        assertEquals("R1", exported[3].ruleId)
        assertEquals(FindingSeverity.INFO, exported[3].severity)

        assertEquals("b/File.kt", exported[4].filePath)
        assertEquals("R0", exported[4].ruleId)
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
