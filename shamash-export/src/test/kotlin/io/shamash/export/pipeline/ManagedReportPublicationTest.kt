/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.artifacts.report.layout.ExportOutputLayout
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedReportPublicationTest {
    @Test
    fun unrelatedEditsDuringRenderingSurvivePublication() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val notes = output.resolve("user-notes.txt")
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(notes, "before")
            Files.writeString(report, "previous")
            val identity = Files.readAttributes(output, BasicFileAttributes::class.java).fileKey()

            CoherentReportPublisher().publish(output) { stage ->
                Files.writeString(notes, "new user edit")
                Files.writeString(output.resolve("new-user-file.txt"), "new user data")
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "complete report")
            }

            assertEquals("new user edit", Files.readString(notes))
            assertEquals("new user data", Files.readString(output.resolve("new-user-file.txt")))
            assertEquals("complete report", Files.readString(report))
            if (identity != null) {
                assertEquals(identity, Files.readAttributes(output, BasicFileAttributes::class.java).fileKey())
            }
            assertNoTransactionDirectories(root)
        }

    @Test
    fun unrelatedEditsDuringActivationSurvivePublication() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val notes = output.resolve("notes.txt")
            Files.writeString(notes, "before")
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            val publisher =
                CoherentReportPublisher { from, to ->
                    if (from.parent.fileName.toString().startsWith(".shamash-stage-")) {
                        Files.writeString(notes, "edited during activation")
                        Files.writeString(output.resolve("new.txt"), "new data")
                    }
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }

            publisher.publish(output) { stage ->
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
            }

            assertEquals("new", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("edited during activation", Files.readString(notes))
            assertEquals("new data", Files.readString(output.resolve("new.txt")))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun unrelatedEditsDuringFailedRollbackArePreserved() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val notes = output.resolve("notes.txt")
            Files.writeString(notes, "before")
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            val publisher =
                CoherentReportPublisher { from, to ->
                    if (from.parent.fileName.toString().startsWith(".shamash-previous-")) {
                        Files.writeString(notes, "updated during rollback")
                        Files.writeString(output.resolve("new.txt"), "new data")
                    }
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }

            assertFailsWith<IOException> {
                publisher.publish(output, onActivated = { throw IOException("completion failed") }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }

            assertEquals("old", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("updated during rollback", Files.readString(notes))
            assertEquals("new data", Files.readString(output.resolve("new.txt")))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun failureAfterCreatingDestinationKeepsNewUnrelatedFiles() =
        withProject { root ->
            val output = root.resolve("reports")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output, onActivated = {
                    Files.writeString(output.resolve("created-during-completion.txt"), "keep")
                    throw IOException("completion failed")
                }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }
            assertEquals("keep", Files.readString(output.resolve("created-during-completion.txt")))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun managedDirectoryCollisionIsRejectedWithoutDamage() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val collision = Files.createDirectories(output.resolve(ExportOutputLayout.JSON_FILE_NAME))
            Files.writeString(collision.resolve("sentinel"), "user content")
            Files.writeString(output.resolve(ExportOutputLayout.XML_FILE_NAME), "previous")
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.XML_FILE_NAME), "replacement")
                }
            }
            assertEquals("user content", Files.readString(collision.resolve("sentinel")))
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.XML_FILE_NAME)))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun unmanagedStagedFileOrDirectoryIsRejected() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                    Files.writeString(stage.resolve("user-notes.txt"), "must not publish")
                }
            }
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.createDirectories(stage.resolve("nested"))
                }
            }
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun managedSymlinkCollisionAndStagedSymlinkAreRejected() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val external = root.resolve("external")
            Files.writeString(external, "outside")
            val link = Files.createSymbolicLink(output.resolve(ExportOutputLayout.JSON_FILE_NAME), external)
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                }
            }
            assertTrue(Files.isSymbolicLink(link))
            Files.delete(link)
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.createSymbolicLink(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), external)
                }
            }
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("outside", Files.readString(external))
            assertNoTransactionDirectories(root)
        }
    }

    @Test
    fun unrelatedTreeAndSymlinkRetainTheirOriginalObjects() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val nested = Files.createDirectories(output.resolve("user-data"))
            Files.writeString(nested.resolve("note.txt"), "note")
            val external = root.resolve("external")
            Files.writeString(external, "outside")
            val link = Files.createSymbolicLink(output.resolve("external-link"), external)
            CoherentReportPublisher().publish(output) { stage ->
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
            }
            assertEquals("note", Files.readString(nested.resolve("note.txt")))
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(external, Files.readSymbolicLink(link))
            assertEquals("outside", Files.readString(external))
            assertNoTransactionDirectories(root)
        }
    }

    @Test
    fun failedRollbackRetainsPreviousManagedFilesAsRecoveryData() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old-json")
            Files.writeString(output.resolve(ExportOutputLayout.XML_FILE_NAME), "old-xml")
            Files.writeString(output.resolve("sentinel.txt"), "unrelated")
            val publisher =
                CoherentReportPublisher { from, to ->
                    if (from.parent.fileName.toString().startsWith(".shamash-previous-") &&
                        from.fileName.toString() == ExportOutputLayout.JSON_FILE_NAME
                    ) {
                        throw IOException("restore locked")
                    }
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            val failure =
                assertFailsWith<IOException> {
                    publisher.publish(output, onActivated = { throw IOException("completion failed") }) { stage ->
                        Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new-json")
                    }
                }
            assertTrue(failure.suppressed.any { it.message?.contains("rollback failed") == true })
            val recovery =
                Files.list(root).use { files ->
                    files.filter { it.fileName.toString().startsWith(".shamash-previous-") }.findFirst().orElseThrow()
                }
            assertEquals("old-json", Files.readString(recovery.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("old-xml", Files.readString(output.resolve(ExportOutputLayout.XML_FILE_NAME)))
            assertEquals("unrelated", Files.readString(output.resolve("sentinel.txt")))
        }

    @Test
    fun unmanagedCaseVariantIsNeverPrunedOnCaseSensitiveFilesystems() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val upper = output.resolve(ExportOutputLayout.JSON_FILE_NAME.uppercase())
            val lower = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(upper, "upper content")
            CoherentReportPublisher().publish(output) { stage ->
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
            }
            val expected = if (Files.isSameFile(upper, lower)) "new" else "upper content"
            assertEquals(expected, Files.readString(upper))
            assertEquals("new", Files.readString(lower))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun accessDeniedMovingExistingManagedFileDoesNotChangeGeneration() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous")
            val publisher =
                CoherentReportPublisher { from, to ->
                    if (from.parent == output) throw AccessDeniedException(from.toString())
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            assertFailsWith<AccessDeniedException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                }
            }
            assertEquals("previous", Files.readString(report))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun failureHalfwayThroughBackingUpManagedFilesRestoresAll() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old-json")
            Files.writeString(output.resolve(ExportOutputLayout.XML_FILE_NAME), "old-xml")
            var moves = 0
            val publisher =
                CoherentReportPublisher { from, to ->
                    moves++
                    if (moves == 2) throw IOException("second backup move failed")
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new-json")
                }
            }
            assertEquals("old-json", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("old-xml", Files.readString(output.resolve(ExportOutputLayout.XML_FILE_NAME)))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun missingActivatedFileDuringRollbackStillRestoresOldGeneration() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "old")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output, onActivated = {
                    Files.delete(report)
                    throw IOException("completion removed the activated output")
                }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }
            assertEquals("old", Files.readString(report))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun destinationChangedToUnrelatedFileDuringRenderingIsLeftUntouched() =
        withProject { root ->
            val output = root.resolve("reports")
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                    Files.writeString(output, "an unrelated writer now owns this path")
                }
            }
            assertEquals("an unrelated writer now owns this path", Files.readString(output))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun discardMoveThatThrowsAfterCompletingStillRestoresPreviousReports() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous")
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    if (to.parent.fileName.toString().startsWith(".shamash-discarded-")) {
                        throw IOException("discard moved before throwing")
                    }
                }
            val failure =
                assertFailsWith<IOException> {
                    publisher.publish(output, onActivated = { throw IOException("completion failed") }) { stage ->
                        Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                    }
                }
            assertEquals("previous", Files.readString(report))
            assertTrue(failure.suppressed.any { it.message?.contains("Rollback move completed") == true })
            assertNoTransactionDirectories(root)
        }

    @Test
    fun restoreMoveThatThrowsAfterCompletingStillRestoresPreviousReports() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous")
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    if (from.parent.fileName.toString().startsWith(".shamash-previous-")) {
                        throw IOException("restored before throwing")
                    }
                }
            val failure =
                assertFailsWith<IOException> {
                    publisher.publish(output, onActivated = { throw IOException("completion failed") }) { stage ->
                        Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                    }
                }
            assertEquals("previous", Files.readString(report))
            assertTrue(failure.suppressed.any { it.message?.contains("Rollback move completed") == true })
            assertNoTransactionDirectories(root)
        }

    @Test
    fun occupiedManagedPathDuringRollbackRetainsOriginalInRecovery() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            val report = output.resolve(ExportOutputLayout.JSON_FILE_NAME)
            Files.writeString(report, "previous")
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    if (to.parent.fileName.toString().startsWith(".shamash-discarded-")) {
                        Files.writeString(from, "concurrent managed writer")
                    }
                }
            val failure =
                assertFailsWith<IOException> {
                    publisher.publish(output, onActivated = { throw IOException("completion failed") }) { stage ->
                        Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                    }
                }
            assertTrue(failure.suppressed.any { it.message?.contains("rollback failed") == true })
            assertEquals("concurrent managed writer", Files.readString(report))
            val previous =
                Files.list(root).use { paths ->
                    paths.filter { it.fileName.toString().startsWith(".shamash-previous-") }.findFirst().orElseThrow()
                }
            assertEquals("previous", Files.readString(previous.resolve(ExportOutputLayout.JSON_FILE_NAME)))
        }

    @Test
    fun newlyCreatedManagedPathDuringRollbackIsReportedAsIncomplete() =
        withProject { root ->
            val output = root.resolve("reports")
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    if (to.parent.fileName.toString().startsWith(".shamash-discarded-")) {
                        Files.writeString(from, "concurrent managed writer")
                    }
                }
            val failure =
                assertFailsWith<IOException> {
                    publisher.publish(output, onActivated = { throw IOException("completion failed") }) { stage ->
                        Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                    }
                }
            assertTrue(failure.suppressed.any { it.message?.contains("rollback failed") == true })
            assertEquals("concurrent managed writer", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
        }

    @Test
    fun deletedStagingDirectoryIsRejectedWithoutTouchingPublishedReports() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.delete(stage)
                }
            }
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertNoTransactionDirectories(root)
        }

    @Test
    fun destinationReplacedWithFileDuringCompletionIsNotRemoved() =
        withProject { root ->
            val output = root.resolve("reports")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output, onActivated = {
                    Files.delete(output.resolve(ExportOutputLayout.JSON_FILE_NAME))
                    Files.delete(output)
                    Files.writeString(output, "other writer")
                    throw IOException("completion failed")
                }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                }
            }
            assertEquals("other writer", Files.readString(output))
            assertNoTransactionDirectories(root)
        }

    private fun withProject(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("shamash-managed-report-test-")
        try {
            action(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun assertNoTransactionDirectories(root: Path) {
        Files.list(root).use { entries ->
            assertTrue(
                entries.noneMatch { path ->
                    val name = path.fileName.toString()
                    name.startsWith(".shamash-stage-") ||
                        name.startsWith(".shamash-previous-") ||
                        name.startsWith(".shamash-discarded-")
                },
            )
        }
    }
}
