/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.baseline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaselineStoreTest {
    @Test
    fun replaceFailureAfterMoveRestoresOriginalBytes() {
        val out = Files.createTempDirectory("shamash-baseline-replace-")
        try {
            val original = BaselineStore()
            original.write(out, setOf("a".repeat(64)))
            val path = original.baselinePath(out)
            val before = Files.readAllBytes(path)
            val failing =
                BaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    throw java.io.IOException("move completed before callback failed")
                }

            assertFailsWith<java.io.IOException> { failing.write(out, setOf("b".repeat(64))) }

            assertContentEquals(before, Files.readAllBytes(path))
            Files.list(out).use { files -> assertEquals(1, files.count()) }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun replaceFailureBeforeMovePreservesPreviousBytes() {
        val out = Files.createTempDirectory("shamash-baseline-pre-move-")
        try {
            val original = BaselineStore()
            original.write(out, setOf("a".repeat(64)))
            val before = Files.readAllBytes(original.baselinePath(out))
            val failing = BaselineStore { _, _ -> throw java.io.IOException("move denied") }

            assertFailsWith<java.io.IOException> { failing.write(out, setOf("b".repeat(64))) }

            assertContentEquals(before, Files.readAllBytes(original.baselinePath(out)))
            Files.list(out).use { files -> assertEquals(1, files.count()) }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedFirstMoveAfterActivationLeavesBaselineAbsent() {
        val out = Files.createTempDirectory("shamash-baseline-first-move-")
        try {
            val failing =
                BaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    throw java.io.IOException("first move committed")
                }

            assertFailsWith<java.io.IOException> { failing.write(out, setOf("a".repeat(64))) }

            assertFalse(Files.exists(failing.baselinePath(out)))
            Files.list(out).use { files -> assertEquals(0, files.count()) }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedRestoreRetainsOldBaselineAsRecoveryFile() {
        val out = Files.createTempDirectory("shamash-baseline-recovery-")
        try {
            val initial = BaselineStore()
            initial.write(out, setOf("a".repeat(64)))
            val failing =
                BaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    Files.delete(to)
                    Files.createDirectory(to)
                    Files.writeString(to.resolve("user-content.txt"), "do not remove")
                    throw java.io.IOException("replacement disrupted")
                }

            val error = assertFailsWith<java.io.IOException> { failing.write(out, setOf("b".repeat(64))) }

            assertTrue(error.suppressed.any { it.message?.contains("Baseline restoration failed") == true })
            assertEquals("do not remove", Files.readString(out.resolve("baseline.json/user-content.txt")))
            Files.list(out).use { files ->
                val retained =
                    files.filter { it.fileName.toString().startsWith(".baseline.json.previous-") }.findFirst().orElseThrow()
                assertEquals(setOf("a".repeat(64)), initial.loadFile(retained)?.fingerprints)
            }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun nonRegularBaselineDestinationIsRejectedWithoutMutation() {
        val out = Files.createTempDirectory("shamash-baseline-directory-")
        try {
            val directory = Files.createDirectory(out.resolve("baseline.json"))
            Files.writeString(directory.resolve("personal.txt"), "keep")
            assertFailsWith<IllegalArgumentException> {
                BaselineStore().write(out, setOf("a".repeat(64)))
            }
            assertEquals("keep", Files.readString(directory.resolve("personal.txt")))
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun symlinkBaselineDestinationIsRejectedWithoutFollowingIt() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val out = Files.createTempDirectory("shamash-baseline-link-")
        try {
            val outside = out.resolve("outside.txt")
            Files.writeString(outside, "unrelated")
            val link = Files.createSymbolicLink(out.resolve("baseline.json"), outside)
            assertFailsWith<IllegalArgumentException> {
                BaselineStore().write(out, setOf("a".repeat(64)))
            }
            assertTrue(Files.isSymbolicLink(link))
            assertEquals("unrelated", Files.readString(outside))
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun mutatedOldBaselineIsRestoredAfterReplacementCallbackFails() {
        val out = Files.createTempDirectory("shamash-baseline-mutated-")
        try {
            val original = BaselineStore()
            original.write(out, setOf("a".repeat(64)))
            val before = Files.readAllBytes(original.baselinePath(out))
            val failing =
                BaselineStore { _, to ->
                    Files.writeString(to, "partially replaced")
                    throw java.io.IOException("injected writer failure")
                }

            assertFailsWith<java.io.IOException> { failing.write(out, setOf("b".repeat(64))) }

            assertContentEquals(before, Files.readAllBytes(original.baselinePath(out)))
            Files.list(out).use { files -> assertEquals(1, files.count()) }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedFirstReplacementThatCreatedPartialFileRemovesIt() {
        val out = Files.createTempDirectory("shamash-baseline-partial-")
        try {
            val failing =
                BaselineStore { _, to ->
                    Files.writeString(to, "partial")
                    throw java.io.IOException("injected writer failure")
                }

            assertFailsWith<java.io.IOException> { failing.write(out, setOf("a".repeat(64))) }

            assertFalse(Files.exists(failing.baselinePath(out)))
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedFirstMoveBeforeMutationDoesNotCreateBaseline() {
        val out = Files.createTempDirectory("shamash-baseline-no-change-")
        try {
            val failing = BaselineStore { _, _ -> throw java.io.IOException("move not attempted") }

            assertFailsWith<java.io.IOException> { failing.write(out, setOf("a".repeat(64))) }

            assertFalse(Files.exists(failing.baselinePath(out)))
            Files.list(out).use { files -> assertEquals(0, files.count()) }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun cleanupFailureIsSuppressedWithoutMaskingReplacementFailure() {
        val out = Files.createTempDirectory("shamash-baseline-cleanup-")
        try {
            val original = BaselineStore()
            original.write(out, setOf("a".repeat(64)))
            val before = Files.readAllBytes(original.baselinePath(out))
            val failing =
                BaselineStore { from, _ ->
                    Files.delete(from)
                    Files.createDirectory(from)
                    Files.writeString(from.resolve("sentinel"), "keep")
                    throw java.io.IOException("injected replacement failure")
                }

            val failure =
                assertFailsWith<java.io.IOException> { failing.write(out, setOf("b".repeat(64))) }

            assertTrue(failure.suppressed.any { it is java.nio.file.DirectoryNotEmptyException })
            assertContentEquals(before, Files.readAllBytes(original.baselinePath(out)))
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun successfulReplacementWithCleanupFailureKeepsPublishedBaseline() {
        val out = Files.createTempDirectory("shamash-baseline-success-cleanup-")
        try {
            val store =
                BaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    Files.createDirectory(from)
                    Files.writeString(from.resolve("sentinel.txt"), "locked")
                }

            store.write(out, setOf("a".repeat(64)))

            assertEquals(setOf("a".repeat(64)), BaselineStore().load(out)?.fingerprints)
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun successfulReplacementWithBackupCleanupFailureDoesNotLoseBaseline() {
        val out = Files.createTempDirectory("shamash-baseline-backup-cleanup-")
        try {
            BaselineStore().write(out, setOf("a".repeat(64)))
            val store =
                BaselineStore { from, to ->
                    Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    Files.list(out).use { paths ->
                        val backup =
                            paths
                                .filter { it.fileName.toString().startsWith(".baseline.json.previous-") }
                                .findFirst()
                                .orElseThrow()
                        Files.delete(backup)
                        Files.createDirectory(backup)
                        Files.writeString(backup.resolve("sentinel.txt"), "locked")
                    }
                }

            store.write(out, setOf("b".repeat(64)))

            assertEquals(setOf("b".repeat(64)), BaselineStore().load(out)?.fingerprints)
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun load_returnsNullWhenMissing() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-missing")
        assertNull(store.load(dir))
    }

    @Test
    fun writeThenLoad_roundTripsFingerprints() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-roundtrip")

        val fps = linkedSetOf("b".repeat(64), "a".repeat(64), "c".repeat(64))
        store.write(dir, fps)

        val loaded = store.load(dir)
        requireNotNull(loaded)

        assertEquals(1, loaded.version)
        assertEquals(fps, loaded.fingerprints)
    }

    @Test
    fun write_preservesPreviousBaselineAndUnrelatedTemporaryFileOnFailure() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-atomic")
        try {
            val previous = "a".repeat(64)
            store.write(dir, setOf(previous))
            val path = store.baselinePath(dir)
            val old = Files.readAllBytes(path)
            val unrelated = dir.resolve("baseline.json.tmp")
            Files.writeString(unrelated, "keep this file")

            assertFailsWith<IllegalArgumentException> {
                store.write(dir, setOf("invalid"))
            }
            assertEquals(old.toList(), Files.readAllBytes(path).toList())
            assertEquals("keep this file", Files.readString(unrelated))

            store.write(dir, setOf("b".repeat(64)))
            assertEquals(setOf("b".repeat(64)), store.load(dir)?.fingerprints)
            assertEquals("keep this file", Files.readString(unrelated))
            Files.list(dir).use { paths ->
                assertEquals(2, paths.count())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun load_throwsOnUnsupportedVersion() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-ver")
        val path = store.baselinePath(dir)
        Files.createDirectories(path.parent)

        Files.writeString(
            path,
            "{\n  \"version\": 2,\n  \"fingerprints\": []\n}\n",
            StandardCharsets.UTF_8,
        )

        val ex = assertFailsWith<IllegalStateException> { store.load(dir) }
        assert(ex.message!!.contains("Unsupported baseline version"))
    }

    @Test
    fun load_throwsOnMissingFields() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-missing-fields")
        val path = store.baselinePath(dir)
        Files.createDirectories(path.parent)

        Files.writeString(path, "{\n  \"fingerprints\": []\n}\n", StandardCharsets.UTF_8)
        assertFailsWith<IllegalStateException> { store.load(dir) }

        Files.writeString(path, "{\n  \"version\": 1\n}\n", StandardCharsets.UTF_8)
        assertFailsWith<IllegalStateException> { store.load(dir) }
    }

    @Test
    fun load_rejectsMalformedJsonAndInvalidShapes() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-invalid")
        val fingerprint = "a".repeat(64)
        val valid = "{\"version\":1,\"fingerprints\":[\"$fingerprint\"]}"

        val invalid =
            listOf(
                "{\"version\":1,\"fingerprints\":[\"$fingerprint\" \"$fingerprint\"]}",
                "{\"version\":1,\"fingerprints\":[\"$fingerprint\",]}",
                "{\"version\":1,\"fingerprints\":[\"$fingerprint\"}",
                "{\"version\":1,\"fingerprints\":[\"$fingerprint\"]}garbage",
                "$valid $valid",
                "{\"version\":1,\"version\":1,\"fingerprints\":[]}",
                "{\"version\":1,\"fingerprints\":[],\"fingerprints\":[]}",
                "{\"version\":1,\"fingerprints\":[],\"extra\":0}",
                "{\"version\":1,\"fingerprints\":null}",
                "{\"version\":1,\"fingerprints\":\"$fingerprint\"}",
                "{\"version\":1,\"fingerprints\":[123]}",
                "{\"version\":1.0,\"fingerprints\":[]}",
                "{\"version\":true,\"fingerprints\":[]}",
                "{\"version\":2147483648,\"fingerprints\":[]}",
                "{\"version\":1,\"fingerprints\":[\"$fingerprint\",\"$fingerprint\"]}",
                "{\"version\":1,\"fingerprints\":[\"bad\"]}",
                "{\"version\":1,\"fingerprints\":[\"${"A".repeat(64)}\"]}",
                "{\"version\":1,\"fingerprints\":[\"\\uZZZZ\"]}",
                "{\"version\":1,\"fingerprints\":[\"\\q\"]}",
                "{\"version\":1,\"fingerprints\":[\"unterminated]}",
            )
        for (json in invalid) {
            Files.writeString(store.baselinePath(dir), json, StandardCharsets.UTF_8)
            assertFailsWith<IllegalStateException>("Expected invalid baseline: $json") { store.load(dir) }
        }
    }

    @Test
    fun serialize_isDeterministicAndRejectsInvalidFingerprints() {
        val store = BaselineStore()
        val first = "f".repeat(64)
        val second = "0".repeat(64)
        val expected = "{\n  \"version\": 1,\n  \"fingerprints\": [\n    \"$second\",\n    \"$first\"\n  ]\n}\n"
        assertEquals(expected, store.serialize(linkedSetOf(first, second)))
        assertEquals("{\n  \"version\": 1,\n  \"fingerprints\": []\n}\n", store.serialize(emptySet()))
        for (invalid in listOf("", "x", "A".repeat(64), "z".repeat(64), "a".repeat(63))) {
            assertFailsWith<IllegalArgumentException> { store.serialize(setOf(invalid)) }
        }
    }

    @Test
    fun loadFile_usesExactPathAndValidatesDecodedFingerprints() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-exact")
        val custom = dir.resolve("custom-baseline.json")
        assertNull(store.loadFile(custom))

        val fingerprint = "a".repeat(64)
        Files.writeString(custom, "{\"version\":1,\"fingerprints\":[\"\\u0061${"a".repeat(63)}\"]}")
        assertEquals(setOf(fingerprint), store.loadFile(custom)!!.fingerprints)
    }

    @Test
    fun load_throwsOnInvalidVersionNumber() {
        val store = BaselineStore()
        val dir = Files.createTempDirectory("shamash-baseline-bad-version")
        val path = store.baselinePath(dir)
        Files.createDirectories(path.parent)

        Files.writeString(
            path,
            "{\n  \"version\": \"x\",\n  \"fingerprints\": []\n}\n",
            StandardCharsets.UTF_8,
        )

        val ex = assertFailsWith<IllegalStateException> { store.load(dir) }
        assert(ex.message!!.contains("is not a number"))
    }
}
