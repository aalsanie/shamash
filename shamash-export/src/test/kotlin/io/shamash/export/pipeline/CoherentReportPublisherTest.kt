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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoherentReportPublisherTest {
    @Test
    fun `complete generation replaces managed files and preserves unrelated content`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            Files.writeString(output.resolve(ExportOutputLayout.SARIF_FILE_NAME), "old-sarif")
            Files.writeString(output.resolve("baseline.json"), "baseline")
            Files.writeString(output.resolve("user-notes.txt"), "untouched")
            Files.createDirectories(output.resolve("user-data"))
            Files.writeString(output.resolve("user-data/keep.txt"), "nested")
            val result =
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                    Files.writeString(stage.resolve(ExportOutputLayout.XML_FILE_NAME), "xml")
                    "complete"
                }

            assertEquals("complete", result)
            assertEquals("new", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("xml", Files.readString(output.resolve(ExportOutputLayout.XML_FILE_NAME)))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
            assertEquals("baseline", Files.readString(output.resolve("baseline.json")))
            assertEquals("untouched", Files.readString(output.resolve("user-notes.txt")))
            assertEquals("nested", Files.readString(output.resolve("user-data/keep.txt")))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed report writer cannot replace an existing generation`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            Files.writeString(output.resolve(ExportOutputLayout.SARIF_FILE_NAME), "old-sarif")
            Files.writeString(output.resolve("keep.txt"), "sentinel")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "partial")
                    Files.writeString(stage.resolve(ExportOutputLayout.HTML_FILE_NAME), "partial-html")
                    throw IOException("injected second exporter failure")
                }
            }
            assertEquals("old", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("old-sarif", Files.readString(output.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.HTML_FILE_NAME)))
            assertEquals("sentinel", Files.readString(output.resolve("keep.txt")))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed activation rolls back the previous coherent generation`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            Files.writeString(output.resolve("keep.txt"), "sentinel")
            var moves = 0
            val publisher =
                CoherentReportPublisher { from, to ->
                    moves++
                    if (moves == 2) throw IOException("injected activation failure")
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                }
            }
            assertEquals(3, moves)
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("sentinel", Files.readString(output.resolve("keep.txt")))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `first successful publication creates a coherent directory`() =
        withProject { root ->
            val output = root.resolve("reports")
            val published =
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "first")
                    Files.writeString(stage.resolve(ExportOutputLayout.HTML_FILE_NAME), "html")
                    2
                }
            assertEquals(2, published)
            assertEquals("first", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("html", Files.readString(output.resolve(ExportOutputLayout.HTML_FILE_NAME)))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed initial directory move leaves the old generation unchanged`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.XML_FILE_NAME), "old")
            val publisher =
                CoherentReportPublisher { _, _ -> throw IOException("failed to move existing directory") }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.XML_FILE_NAME), "new")
                }
            }
            assertEquals("old", Files.readString(output.resolve(ExportOutputLayout.XML_FILE_NAME)))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `symbolic link destinations are never replaced`() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        withProject { root ->
            val actual = Files.createDirectories(root.resolve("actual"))
            Files.writeString(actual.resolve("notes.txt"), "user")
            val destination = Files.createSymbolicLink(root.resolve("reports"), actual)
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(destination) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }
            assertTrue(Files.isSymbolicLink(destination))
            assertEquals("user", Files.readString(actual.resolve("notes.txt")))
            assertNoTemporaryDirectories(root)
        }
    }

    @Test
    fun `invalid output destination never deletes existing content`() =
        withProject { root ->
            val output = root.resolve("reports")
            Files.writeString(output, "user file")
            assertFailsWith<IllegalArgumentException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }
            assertEquals("user file", Files.readString(output))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `completion happens after report activation and preserves unrelated files`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            Files.writeString(output.resolve("keep.txt"), "sentinel")
            var committed = false
            val result =
                CoherentReportPublisher().publish(output, onActivated = {
                    assertEquals("new", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
                    committed = true
                }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                    42
                }
            assertEquals(42, result)
            assertTrue(committed)
            assertEquals("sentinel", Files.readString(output.resolve("keep.txt")))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed completion restores the prior report generation`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old-json")
            Files.writeString(output.resolve(ExportOutputLayout.XML_FILE_NAME), "old-xml")
            Files.writeString(output.resolve("keep.txt"), "untouched")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output, onActivated = {
                    throw IOException("baseline write failed")
                }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new-json")
                    Files.writeString(stage.resolve(ExportOutputLayout.SARIF_FILE_NAME), "new-sarif")
                }
            }
            assertEquals("old-json", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("old-xml", Files.readString(output.resolve(ExportOutputLayout.XML_FILE_NAME)))
            assertEquals("untouched", Files.readString(output.resolve("keep.txt")))
            assertFalse(Files.exists(output.resolve(ExportOutputLayout.SARIF_FILE_NAME)))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed completion without previous reports leaves no published generation`() =
        withProject { root ->
            val output = root.resolve("reports")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output, onActivated = {
                    throw IOException("baseline is unwritable")
                }) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }
            assertFalse(Files.exists(output))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `a completed move that throws is still reconciled and rolled back`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            var moves = 0
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    moves++
                    if (moves == 2) throw IOException("failure reported after activation")
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                }
            }
            assertEquals("old", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals(4, moves)
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `rollback failure retains recoverable prior generation`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "old")
            var moves = 0
            val publisher =
                CoherentReportPublisher { from, to ->
                    moves++
                    if (moves == 4) throw IOException("could not restore previous generation")
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            val failure =
                assertFailsWith<IOException> {
                    publisher.publish(output, onActivated = {
                        throw IOException("baseline failed")
                    }) { stage ->
                        Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "new")
                    }
                }
            assertTrue(failure.suppressed.any { it.message?.contains("rollback failed") == true })
            Files.list(root).use { entries ->
                val backups = entries.filter { it.fileName.toString().startsWith(".shamash-previous-") }.toList()
                assertEquals(1, backups.size)
                assertEquals("old", Files.readString(backups.single().resolve(ExportOutputLayout.JSON_FILE_NAME)))
            }
        }

    @Test
    fun `concurrent publishers cannot overwrite an in-progress generation`() =
        withProject { root ->
            val output = root.resolve("reports")
            CoherentReportPublisher().publish(output) { stage ->
                assertFailsWith<IllegalStateException> {
                    CoherentReportPublisher().publish(output) { conflicting ->
                        Files.writeString(conflicting.resolve(ExportOutputLayout.JSON_FILE_NAME), "conflict")
                    }
                }
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "first")
            }
            assertEquals("first", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `symbolic link lock file is rejected`() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        withProject { root ->
            val output = root.resolve("reports")
            // A pre-existing lock is deliberately not followed, even if the file is writable.
            val first =
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "first")
                    "ok"
                }
            assertEquals("ok", first)
            val lock =
                Files.list(root).use { paths ->
                    paths.filter { it.fileName.toString().startsWith(".shamash-publish-") }.findFirst().orElseThrow()
                }
            Files.delete(lock)
            val sentinel = root.resolve("sentinel")
            Files.writeString(sentinel, "untouched")
            Files.createSymbolicLink(lock, sentinel)
            assertFailsWith<Exception> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "second")
                }
            }
            assertEquals("first", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("untouched", Files.readString(sentinel))
        }
    }

    @Test
    fun `filesystem root cannot be used as a report destination`() {
        val root = Path.of("").toAbsolutePath().root
        assertFailsWith<IllegalArgumentException> {
            CoherentReportPublisher().publish(root) { stage ->
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "never")
            }
        }
    }

    @Test
    fun `failed first activation does not publish reports or leave temporary directories`() =
        withProject { root ->
            val output = root.resolve("reports")
            val publisher =
                CoherentReportPublisher { _, _ ->
                    throw IOException("could not activate first generation")
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "not published")
                }
            }
            assertFalse(Files.exists(output))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed render with removed staging tree does not invent an activation`() =
        withProject { root ->
            val output = root.resolve("reports")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output) { stage ->
                    val report = stage.resolve(ExportOutputLayout.JSON_FILE_NAME)
                    Files.writeString(report, "partial")
                    Files.delete(report)
                    Files.delete(stage)
                    throw IOException("stage was discarded")
                }
            }
            assertFalse(Files.exists(output))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `report path aliases cannot publish concurrently`() =
        withProject { root ->
            val output = root.resolve("reports")
            val alias = root.resolve("REPORTS")
            CoherentReportPublisher().publish(output) { stage ->
                assertFailsWith<IllegalStateException> {
                    CoherentReportPublisher().publish(alias) { competing ->
                        Files.writeString(competing.resolve(ExportOutputLayout.JSON_FILE_NAME), "competing")
                    }
                }
                Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "first")
            }
            assertEquals("first", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `removed staging tree cannot cause deletion of previously published reports`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            Files.writeString(output.resolve("notes.txt"), "personal")
            assertFailsWith<IOException> {
                CoherentReportPublisher().publish(output) { stage ->
                    Files.delete(stage.resolve("notes.txt"))
                    Files.delete(stage)
                    throw IOException("renderer discarded its staging directory")
                }
            }
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("personal", Files.readString(output.resolve("notes.txt")))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `stage lost during activation restores the previous generation`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            val publisher =
                CoherentReportPublisher { from, to ->
                    if (from.fileName.toString().startsWith(".shamash-stage-")) {
                        Files.delete(from.resolve(ExportOutputLayout.JSON_FILE_NAME))
                        Files.delete(from)
                        throw IOException("staging disappeared before activation")
                    }
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "replacement")
                }
            }
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `stage lost during first activation leaves no published generation`() =
        withProject { root ->
            val output = root.resolve("reports")
            val publisher =
                CoherentReportPublisher { from, _ ->
                    Files.delete(from.resolve(ExportOutputLayout.JSON_FILE_NAME))
                    Files.delete(from)
                    throw IOException("staging disappeared")
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "incomplete")
                }
            }
            assertFalse(Files.exists(output))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failure after moving the old generation restores it before activation`() =
        withProject { root ->
            val output = Files.createDirectories(root.resolve("reports"))
            Files.writeString(output.resolve(ExportOutputLayout.JSON_FILE_NAME), "previous")
            Files.writeString(output.resolve("notes.txt"), "user")
            var moves = 0
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    moves++
                    if (moves == 1) throw IOException("move completed but reported failure")
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "incomplete")
                }
            }
            assertEquals(2, moves)
            assertEquals("previous", Files.readString(output.resolve(ExportOutputLayout.JSON_FILE_NAME)))
            assertEquals("user", Files.readString(output.resolve("notes.txt")))
            assertNoTemporaryDirectories(root)
        }

    @Test
    fun `failed first activation after move still removes the incomplete generation`() =
        withProject { root ->
            val output = root.resolve("reports")
            val publisher =
                CoherentReportPublisher { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                    if (from.fileName.toString().startsWith(".shamash-stage-")) {
                        throw IOException("activation completed before failure")
                    }
                }
            assertFailsWith<IOException> {
                publisher.publish(output) { stage ->
                    Files.writeString(stage.resolve(ExportOutputLayout.JSON_FILE_NAME), "incomplete")
                }
            }
            assertFalse(Files.exists(output))
            assertNoTemporaryDirectories(root)
        }

    private fun withProject(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("shamash-publisher-test")
        try {
            action(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun assertNoTemporaryDirectories(root: Path) {
        Files.list(root).use { entries ->
            assertTrue(
                entries.noneMatch {
                    it.fileName.toString().startsWith(".shamash-stage-") ||
                        it.fileName.toString().startsWith(".shamash-previous-")
                },
            )
        }
    }
}
