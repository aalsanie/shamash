/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.service

import io.shamash.artifacts.baseline.BaselineConfig
import io.shamash.artifacts.baseline.BaselineCoordinator
import io.shamash.artifacts.baseline.BaselineMode
import io.shamash.artifacts.baseline.BaselineStore
import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.export.pipeline.FindingPreprocessor
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShamashReportBaselineTransactionTest {
    private val store = BaselineStore()

    @Test
    fun generatedBaselineIsWrittenWhileReportPublicationLockIsHeld() =
        withProject { project ->
            val lockFile = project.resolve(".shamash-publish-lock")
            var lockObserved = false
            val guardedStore =
                injectedBaselineStore { from, to ->
                    FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
                        assertFailsWith<OverlappingFileLockException> {
                            channel.tryLock()
                        }
                    }
                    lockObserved = true
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }

            val result =
                export(
                    project,
                    findings = listOf(finding(project, "RULE")),
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = true),
                    service = ShamashReportExportService(BaselineCoordinator(guardedStore)),
                )

            assertTrue(lockObserved)
            assertTrue(result.baselineWritten)
            assertEquals(
                setOf(result.report.findings.single().fingerprint),
                store.load(result.outputDir)?.fingerprints,
            )
        }

    @Test
    fun failedBaselineDirectoryCollisionRestoresPreviouslyPublishedReports() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            val baseline = Files.createDirectory(output.resolve("baseline.json"))
            Files.writeString(report, "previous generation")
            Files.writeString(baseline.resolve("user.txt"), "user data")
            Files.writeString(output.resolve("notes.txt"), "preserve")

            assertFailsWith<Exception> {
                export(project, baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false))
            }

            assertEquals("previous generation", Files.readString(report))
            assertEquals("user data", Files.readString(baseline.resolve("user.txt")))
            assertEquals("preserve", Files.readString(output.resolve("notes.txt")))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
        }

    @Test
    fun baselineWriteFailureBeforeMoveRestoresReportsAndKeepsExactPreviousBytes() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous generation")
            store.write(output, setOf("a".repeat(64)))
            val previous = Files.readAllBytes(store.baselinePath(output))
            val failing = injectedBaselineStore { _, _ -> throw IOException("replacement denied") }

            assertFailsWith<IOException> {
                export(
                    project,
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = true),
                    service = ShamashReportExportService(BaselineCoordinator(failing)),
                )
            }

            assertEquals("previous generation", Files.readString(report))
            assertContentEquals(previous, Files.readAllBytes(store.baselinePath(output)))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
        }

    @Test
    fun baselineMoveThatThrowsAfterSucceedingRestoresBothPreviousGenerations() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous generation")
            store.write(output, setOf("a".repeat(64)))
            val previous = Files.readAllBytes(store.baselinePath(output))
            val failing =
                injectedBaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    throw IOException("replacement completed before reporting failure")
                }

            assertFailsWith<IOException> {
                export(
                    project,
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false),
                    service = ShamashReportExportService(BaselineCoordinator(failing)),
                )
            }

            assertEquals("previous generation", Files.readString(report))
            assertContentEquals(previous, Files.readAllBytes(store.baselinePath(output)))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.HTML_FILE_NAME)))
        }

    @Test
    fun firstBaselineMoveThatThrowsAfterSucceedingLeavesNoBaselineOrNewReports() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous generation")
            val failing =
                injectedBaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    throw IOException("initial baseline completed before reporting failure")
                }

            assertFailsWith<IOException> {
                export(
                    project,
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false),
                    service = ShamashReportExportService(BaselineCoordinator(failing)),
                )
            }

            assertEquals("previous generation", Files.readString(report))
            assertFalse(Files.exists(store.baselinePath(output)))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
        }

    @Test
    fun successfulGenerateWithoutExistingBaselineUsesSameFilteredFindingsAsReport() =
        withProject { project ->
            val dropped = finding(project, "DROP")
            val kept = finding(project, "KEEP")
            var calls = 0
            val exceptions =
                FindingPreprocessor { _, findings ->
                    calls++
                    findings.filterNot { it.ruleId == "DROP" }
                }
            val result =
                export(
                    project,
                    findings = listOf(dropped, kept),
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false),
                    exceptions = exceptions,
                )

            assertEquals(1, calls)
            assertTrue(result.baselineWritten)
            assertEquals(listOf("KEEP"), result.report.findings.map { it.ruleId })
            assertEquals(
                result.report.findings
                    .map { it.fingerprint }
                    .toSet(),
                store.load(result.outputDir)?.fingerprints,
            )
        }

    @Test
    fun generateMergePreservesEarlierFingerprintsButReportsOnlyCurrentFilteredFindings() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val previous = "a".repeat(64)
            store.write(output, setOf(previous))
            val finding = finding(project, "KEEP")
            val result =
                export(
                    project,
                    findings = listOf(finding),
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = true),
                )

            assertTrue(result.baselineWritten)
            assertEquals(1, result.report.findings.size)
            assertEquals(
                setOf(
                    previous,
                    result.report.findings
                        .single()
                        .fingerprint,
                ),
                store.load(output)?.fingerprints,
            )
        }

    @Test
    fun generateWithoutMergeReplacesPreviouslyStoredFingerprints() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            store.write(output, setOf("a".repeat(64)))
            val result =
                export(
                    project,
                    findings = listOf(finding(project, "NEXT")),
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false),
                )

            assertTrue(result.baselineWritten)
            assertEquals(
                setOf(
                    result.report.findings
                        .single()
                        .fingerprint,
                ),
                store.load(output)?.fingerprints,
            )
        }

    @Test
    fun offAndUsePreserveBaselineBytesAndNeverReportBaselineWritten() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val matched = finding(project, "MATCHED")
            val fingerprint = BaselineCoordinator().computeFingerprints(project, listOf(matched)).single()
            store.write(output, setOf(fingerprint))
            val before = Files.readAllBytes(store.baselinePath(output))
            val off = export(project, findings = listOf(matched), baseline = BaselineConfig.OFF)
            assertFalse(off.baselineWritten)
            assertEquals(1, off.report.findings.size)
            assertContentEquals(before, Files.readAllBytes(store.baselinePath(output)))

            val used = export(project, findings = listOf(matched), baseline = BaselineConfig(BaselineMode.USE))
            assertFalse(used.baselineWritten)
            assertTrue(used.report.findings.isEmpty())
            assertContentEquals(before, Files.readAllBytes(store.baselinePath(output)))
        }

    @Test
    fun useWithMissingBaselineKeepsAllFindingsAndDoesNotCreateBaseline() =
        withProject { project ->
            val result =
                export(
                    project,
                    findings = listOf(finding(project, "NEW")),
                    baseline = BaselineConfig(BaselineMode.USE),
                )
            assertEquals(1, result.report.findings.size)
            assertFalse(result.baselineWritten)
            assertFalse(Files.exists(store.baselinePath(result.outputDir)))
        }

    @Test
    fun invalidBaselineInUseOrMergedGeneratePreservesPreviousReports() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous generation")
            Files.writeString(store.baselinePath(output), "{not JSON}")
            val previousBaseline = Files.readAllBytes(store.baselinePath(output))

            assertFailsWith<IllegalStateException> {
                export(project, baseline = BaselineConfig(BaselineMode.USE))
            }
            assertFailsWith<IllegalStateException> {
                export(project, baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = true))
            }

            assertEquals("previous generation", Files.readString(report))
            assertContentEquals(previousBaseline, Files.readAllBytes(store.baselinePath(output)))
        }

    @Test
    fun generateWithoutMergeCanReplaceInvalidExistingBaseline() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            Files.writeString(store.baselinePath(output), "{not JSON}")
            val result =
                export(
                    project,
                    findings = listOf(finding(project, "NEW")),
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false),
                )
            assertTrue(result.baselineWritten)
            assertEquals(
                setOf(
                    result.report.findings
                        .single()
                        .fingerprint,
                ),
                store.load(output)?.fingerprints,
            )
        }

    @Test
    fun offModeDoesNotReadOrAlterAnInvalidBaseline() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            Files.writeString(store.baselinePath(output), "{not JSON}")
            val result = export(project, baseline = BaselineConfig.OFF)

            assertFalse(result.baselineWritten)
            assertEquals("{not JSON}", Files.readString(store.baselinePath(output)))
        }

    @Test
    fun failingExceptionsProcessorCannotPublishReportOrBaseline() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous generation")
            val processor = FindingPreprocessor { _, _ -> throw IOException("exceptions unavailable") }

            assertFailsWith<IOException> {
                export(
                    project,
                    findings = listOf(finding(project, "R")),
                    baseline = BaselineConfig(BaselineMode.GENERATE),
                    exceptions = processor,
                )
            }
            assertEquals("previous generation", Files.readString(report))
            assertFalse(Files.exists(store.baselinePath(output)))
        }

    @Test
    fun managedReportCollisionPreventsBaselineReplacement() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            store.write(output, setOf("a".repeat(64)))
            val before = Files.readAllBytes(store.baselinePath(output))
            val reportCollision = Files.createDirectory(output.resolve(ExportOutputLayout.XML_FILE_NAME))
            Files.writeString(reportCollision.resolve("keep.txt"), "user")

            assertFailsWith<IllegalArgumentException> {
                export(project, baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false))
            }

            assertContentEquals(before, Files.readAllBytes(store.baselinePath(output)))
            assertEquals("user", Files.readString(reportCollision.resolve("keep.txt")))
        }

    @Test
    fun unrelatedEditsMadeWhileBaselineWriteFailsSurviveReportRollback() =
        withProject { project ->
            val output = Files.createDirectories(project.resolve(".shamash"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            val notes = output.resolve("notes.txt")
            Files.writeString(report, "previous generation")
            Files.writeString(notes, "before")
            val failing =
                injectedBaselineStore { _, _ ->
                    Files.writeString(notes, "changed during activation")
                    Files.writeString(output.resolve("new-user-data.txt"), "new data")
                    throw IOException("baseline write failed")
                }

            assertFailsWith<IOException> {
                export(
                    project,
                    baseline = BaselineConfig(BaselineMode.GENERATE, writeMerged = false),
                    service = ShamashReportExportService(BaselineCoordinator(failing)),
                )
            }

            assertEquals("previous generation", Files.readString(report))
            assertEquals("changed during activation", Files.readString(notes))
            assertEquals("new data", Files.readString(output.resolve("new-user-data.txt")))
        }

    private fun injectedBaselineStore(replacement: (Path, Path) -> Unit): BaselineStore {
        val constructor = BaselineStore::class.java.getDeclaredConstructor(Function2::class.java)
        return constructor.newInstance(replacement)
    }

    private fun finding(
        project: Path,
        rule: String,
    ): Finding =
        Finding(
            ruleId = rule,
            message = rule,
            filePath = project.resolve("src/$rule.kt").toString(),
            severity = FindingSeverity.ERROR,
        )

    private fun export(
        project: Path,
        findings: List<Finding> = emptyList(),
        baseline: BaselineConfig = BaselineConfig.OFF,
        exceptions: FindingPreprocessor? = null,
        service: ShamashReportExportService = ShamashReportExportService(),
    ): ShamashReportExportService.ExportResult =
        service.export(
            projectBasePath = project,
            projectName = "project",
            toolName = "test-tool",
            toolVersion = "1",
            findings = findings,
            baseline = baseline,
            exceptionsPreprocessor = exceptions,
            generatedAtEpochMillis = 1L,
        )

    private fun withProject(block: (Path) -> Unit) {
        val project = Files.createTempDirectory("shamash-baseline-publication-")
        try {
            block(project)
        } finally {
            project.toFile().deleteRecursively()
        }
    }
}
