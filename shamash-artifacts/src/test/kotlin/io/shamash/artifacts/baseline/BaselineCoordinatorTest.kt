/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
    fun computeFingerprints_matchesProjectRelativeAndAbsoluteFindingPaths() {
        val coordinator = BaselineCoordinator()
        val projectBase = Files.createTempDirectory("shamash-coord")
        val relativePath = "build/classes/java/main/com/example/App.class"
        val finding =
            Finding(
                ruleId = "R1",
                message = "m",
                filePath = relativePath,
                severity = FindingSeverity.ERROR,
            )
        val absoluteFinding = finding.copy(filePath = projectBase.resolve(relativePath).toString())

        val fingerprints = coordinator.computeFingerprints(projectBase, listOf(finding))

        assertEquals(setOf(BaselineFingerprint.sha256Hex(finding, relativePath)), fingerprints)
        assertEquals(fingerprints, coordinator.computeFingerprints(projectBase, listOf(absoluteFinding)))
    }

    @Test
    fun computeFingerprints_isStableAcrossProjectRoots() {
        val coordinator = BaselineCoordinator()
        val firstProject = Files.createTempDirectory("shamash-coord-first")
        val secondProject = Files.createTempDirectory("shamash-coord-second")
        val relativePath = "build/classes/java/main/com/example/App.class"
        val finding =
            Finding(
                ruleId = "R1",
                message = "m",
                filePath = relativePath,
                severity = FindingSeverity.ERROR,
            )

        val first = coordinator.computeFingerprints(firstProject, listOf(finding))
        val second = coordinator.computeFingerprints(secondProject, listOf(finding))

        assertEquals(first, second)
    }

    @Test
    fun createSuppressionPreprocessor_suppressesMatchingAbsoluteFinding() {
        val coordinator = BaselineCoordinator()
        val projectBase = Files.createTempDirectory("shamash-coord")
        val finding =
            Finding(
                ruleId = "R1",
                message = "m",
                filePath = "build/classes/java/main/com/example/App.class",
                severity = FindingSeverity.ERROR,
            )
        val fingerprints = coordinator.computeFingerprints(projectBase, listOf(finding))
        val absoluteFinding = finding.copy(filePath = projectBase.resolve(finding.filePath).toString())

        val preprocessor = assertNotNull(coordinator.createSuppressionPreprocessor(fingerprints))

        assertEquals(emptyList(), preprocessor.process(projectBase, listOf(absoluteFinding)))
    }

    @Test
    fun writeBaseline_overwritesOrMergesDependingOnFlag() {
        val store = BaselineStore()
        val coordinator = BaselineCoordinator(store)
        val outDir = Files.createTempDirectory("shamash-baseline-out")

        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        coordinator.writeBaseline(outDir, setOf(a), mergeWithExisting = false)
        assertEquals(setOf(a), store.load(outDir)!!.fingerprints)

        coordinator.writeBaseline(outDir, setOf(b), mergeWithExisting = true)
        assertEquals(setOf(a, b), store.load(outDir)!!.fingerprints)

        coordinator.writeBaseline(outDir, setOf(c), mergeWithExisting = false)
        assertEquals(setOf(c), store.load(outDir)!!.fingerprints)
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
