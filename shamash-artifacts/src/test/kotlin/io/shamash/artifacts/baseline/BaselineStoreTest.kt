/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.artifacts.baseline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BaselineStoreTest {
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
