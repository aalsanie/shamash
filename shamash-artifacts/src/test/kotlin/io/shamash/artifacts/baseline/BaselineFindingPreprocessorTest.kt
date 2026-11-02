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
import kotlin.test.assertSame

class BaselineFindingPreprocessorTest {
    @Test
    fun process_returnsInput_whenBaselineEmpty_orFindingsEmpty() {
        val pre = BaselineFindingPreprocessor(emptySet())
        val base = Files.createTempDirectory("shamash-pre")

        val findings =
            listOf(
                Finding(
                    ruleId = "R1",
                    message = "m",
                    filePath = base.resolve("a.kt").toString(),
                    severity = FindingSeverity.ERROR,
                ),
            )

        assertSame(findings, pre.process(base, findings))

        val pre2 = BaselineFindingPreprocessor(setOf("x"))
        val empty = emptyList<Finding>()
        assertSame(empty, pre2.process(base, empty))
    }

    @Test
    fun process_suppressesOnlyFindingsWhoseFingerprintIsInBaseline() {
        val projectBase = Files.createTempDirectory("shamash-pre")

        val suppressed =
            Finding(
                ruleId = "R1",
                message = "m",
                filePath = projectBase.resolve("src/A.kt").toString(),
                severity = FindingSeverity.ERROR,
                data = mapOf("k" to "v"),
            )

        val kept =
            Finding(
                ruleId = "R2",
                message = "m2",
                filePath = projectBase.resolve("src/B.kt").toString(),
                severity = FindingSeverity.WARNING,
            )

        val normalized = PathNormalizer.relativizeOrNormalize(projectBase, Paths.get(suppressed.filePath))
        val fp = BaselineFingerprint.sha256Hex(suppressed, normalized)

        val pre = BaselineFindingPreprocessor(setOf(fp))
        val out = pre.process(projectBase, listOf(suppressed, kept))

        assertEquals(listOf(kept), out)
    }
}
