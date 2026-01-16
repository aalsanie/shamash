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

        val fps = linkedSetOf("b", "a", "c")
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
        // Keep assertion flexible but meaningful.
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
