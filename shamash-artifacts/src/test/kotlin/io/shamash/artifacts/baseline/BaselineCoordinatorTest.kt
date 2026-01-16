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

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.util.PathNormalizer
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BaselineCoordinatorTest {
    @Test
    fun computeFingerprints_returnsEmpty_whenNoFindings() {
        val coordinator = BaselineCoordinator()
        val base = Paths.get("/tmp/project")
        assertEquals(emptySet(), coordinator.computeFingerprints(base, emptyList()))
    }

    @Test
    fun computeFingerprints_usesProjectRelativeNormalizedPath() {
        val coordinator = BaselineCoordinator()
        val projectBase = Files.createTempDirectory("shamash-coord")

        val finding =
            Finding(
                ruleId = "R1",
                message = "m",
                filePath = projectBase.resolve("src/Main.kt").toString(),
                severity = FindingSeverity.ERROR,
                data = mapOf("a" to "1"),
            )

        val fps = coordinator.computeFingerprints(projectBase, listOf(finding))
        val normalized = PathNormalizer.relativizeOrNormalize(projectBase, Paths.get(finding.filePath))
        val expected = BaselineFingerprint.sha256Hex(finding, normalized)

        assertEquals(setOf(expected), fps)
    }

    @Test
    fun writeBaseline_overwritesOrMergesDependingOnFlag() {
        val store = BaselineStore()
        val coordinator = BaselineCoordinator(store)
        val outDir = Files.createTempDirectory("shamash-baseline-out")

        coordinator.writeBaseline(outDir, setOf("a"), mergeWithExisting = false)
        assertEquals(setOf("a"), store.load(outDir)!!.fingerprints)

        coordinator.writeBaseline(outDir, setOf("b"), mergeWithExisting = true)
        assertEquals(setOf("a", "b"), store.load(outDir)!!.fingerprints)

        coordinator.writeBaseline(outDir, setOf("c"), mergeWithExisting = false)
        assertEquals(setOf("c"), store.load(outDir)!!.fingerprints)
    }

    @Test
    fun createSuppressionPreprocessor_returnsNullWhenEmpty() {
        val coordinator = BaselineCoordinator()
        assertNull(coordinator.createSuppressionPreprocessor(emptySet()))
        assertNotNull(coordinator.createSuppressionPreprocessor(setOf("x")))
    }

    @Test
    fun loadBaselineFingerprints_returnsEmptyWhenMissing() {
        val coordinator = BaselineCoordinator()
        val outDir = Files.createTempDirectory("shamash-baseline-missing")
        assertEquals(emptySet(), coordinator.loadBaselineFingerprints(outDir))
    }
}
