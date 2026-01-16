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

        // Baseline empty -> should return exact same list reference.
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
