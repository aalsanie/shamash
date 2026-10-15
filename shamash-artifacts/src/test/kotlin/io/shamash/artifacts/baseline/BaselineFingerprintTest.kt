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

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BaselineFingerprintTest {
    @Test
    fun sha256Hex_isDeterministic_overDataOrdering_andIgnoresMessageText() {
        val path = "src/Main.kt"

        val f1 =
            Finding(
                ruleId = "R1",
                message = "human readable text A",
                filePath = "/tmp/project/$path",
                severity = FindingSeverity.ERROR,
                startOffset = 10,
                endOffset = 20,
                data = linkedMapOf("b" to "2", "a" to "1"),
            )

        val f2 =
            Finding(
                ruleId = "R1",
                message = "human readable text B (should not matter)",
                filePath = "/tmp/project/$path",
                severity = FindingSeverity.ERROR,
                startOffset = 10,
                endOffset = 20,
                data = linkedMapOf("a" to "1", "b" to "2"),
            )

        val fp1 = BaselineFingerprint.sha256Hex(f1, path)
        val fp2 = BaselineFingerprint.sha256Hex(f2, path)

        assertEquals(fp1, fp2)
    }

    @Test
    fun sha256Hex_graphCycleIgnoresPresentationMetadataAndAnchorPath() {
        val cycle =
            Finding(
                ruleId = "graph.noCycles",
                message = "One cycle",
                filePath = "classes/com/example/A.class",
                severity = FindingSeverity.ERROR,
                data =
                    mapOf(
                        "granularity" to "class",
                        "includeExternal" to "false",
                        "cycle" to "com.example.A -> com.example.B -> com.example.A",
                        "cycleIndex" to "1",
                        "cycleCount" to "1",
                    ),
            )
        val sameCycle =
            cycle.copy(
                message = "Two cycles",
                filePath = "classes/com/example/Unrelated.class",
                data =
                    cycle.data +
                        mapOf(
                            "cycleIndex" to "2",
                            "cycleCount" to "2",
                            "cyclesTruncated" to "true",
                        ),
            )

        val original = BaselineFingerprint.sha256Hex(cycle, cycle.filePath)

        assertEquals(original, BaselineFingerprint.sha256Hex(sameCycle, sameCycle.filePath))

        val scopedCycle = cycle.copy(ruleId = "graph.noCycles.controller")
        val scopedSameCycle = sameCycle.copy(ruleId = "graph.noCycles.controller")
        assertEquals(
            BaselineFingerprint.sha256Hex(scopedCycle, scopedCycle.filePath),
            BaselineFingerprint.sha256Hex(scopedSameCycle, scopedSameCycle.filePath),
        )
        assertNotEquals(original, BaselineFingerprint.sha256Hex(cycle.copy(data = cycle.data + ("cycle" to "another cycle")), cycle.filePath))
        assertNotEquals(original, BaselineFingerprint.sha256Hex(cycle.copy(data = cycle.data + ("granularity" to "package")), cycle.filePath))
        assertNotEquals(original, BaselineFingerprint.sha256Hex(cycle.copy(data = cycle.data + ("includeExternal" to "true")), cycle.filePath))
        assertNotEquals(original, BaselineFingerprint.sha256Hex(cycle.copy(severity = FindingSeverity.WARNING), cycle.filePath))
    }

    @Test
    fun sha256Hex_otherRulesStillHashPathAndEveryDataEntry() {
        val finding =
            Finding(
                ruleId = "arch.forbiddenRoleDependencies",
                message = "m",
                filePath = "classes/com/example/A.class",
                severity = FindingSeverity.ERROR,
                data = mapOf("cycleCount" to "1"),
            )
        val original = BaselineFingerprint.sha256Hex(finding, finding.filePath)

        assertNotEquals(original, BaselineFingerprint.sha256Hex(finding, "classes/com/example/B.class"))
        assertNotEquals(
            original,
            BaselineFingerprint.sha256Hex(finding.copy(data = mapOf("cycleCount" to "2")), finding.filePath),
        )
    }

    @Test
    fun sha256Hex_changesWhenIdentityFieldsChange() {
        val path = "src/Main.kt"

        val base =
            Finding(
                ruleId = "R1",
                message = "msg",
                filePath = "/tmp/project/$path",
                severity = FindingSeverity.ERROR,
                startOffset = 10,
                endOffset = 20,
                data = mapOf("k" to "v"),
            )

        val differentRule = base.copy(ruleId = "R2")
        val differentSeverity = base.copy(severity = FindingSeverity.WARNING)
        val differentOffsets = base.copy(startOffset = 11)
        val differentData = base.copy(data = mapOf("k" to "vv"))

        val fp = BaselineFingerprint.sha256Hex(base, path)

        assertNotEquals(fp, BaselineFingerprint.sha256Hex(differentRule, path))
        assertNotEquals(fp, BaselineFingerprint.sha256Hex(differentSeverity, path))
        assertNotEquals(fp, BaselineFingerprint.sha256Hex(differentOffsets, path))
        assertNotEquals(fp, BaselineFingerprint.sha256Hex(differentData, path))
    }
}
