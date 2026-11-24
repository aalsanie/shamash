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
package io.shamash.intellij.plugin.analysis

import io.shamash.psi.core.source.SourceCoverage
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectSourceCoverageTest {
    private fun sourceCoverage(
        truncated: Boolean = false,
        uncertain: Int = 0,
    ): SourceCoverage =
        SourceCoverage(
            eligibleFiles = 3,
            analyzedFiles = 2,
            failedFiles = 1,
            resolvedOccurrences = 5,
            unresolvedOccurrences = 2,
            excludedFiles = 4,
            truncated = truncated,
            excludedReasons = mapOf("NON_JVM" to 2, "TEST" to 1, "UNKNOWN_PLATFORM" to uncertain),
            uncertainFiles = uncertain,
        )

    @Test
    fun retainsCountsAndExclusionReasonsForCompleteScope() {
        val coverage = sourceCoverage().toAnalysisCoverage()
        assertNull(coverage.reason)
        assertEquals(3, coverage.eligibleFiles)
        assertEquals(2, coverage.analyzedFiles)
        assertEquals(1, coverage.failedFiles)
        assertEquals(5L, coverage.resolvedOccurrences)
        assertEquals(2L, coverage.unresolvedOccurrences)
        assertEquals(4, coverage.excludedFiles)
        assertEquals(2, coverage.excludedReasons["NON_JVM"])
    }

    @Test
    fun explainsUnknownKotlinPlatform() {
        val coverage = sourceCoverage(uncertain = 1).toAnalysisCoverage()
        assertEquals("Kotlin target platform is unknown for some source files", coverage.reason)
        assertEquals(1, coverage.excludedReasons["UNKNOWN_PLATFORM"])
    }

    @Test
    fun truncationTakesPrecedenceOverUncertainPlatform() {
        val coverage = sourceCoverage(truncated = true, uncertain = 2).toAnalysisCoverage()
        assertEquals("Internal limit or revision change", coverage.reason)
        assertEquals(2, coverage.excludedReasons["UNKNOWN_PLATFORM"])
    }

    @Test
    fun truncatedWithKnownPlatformsStillReportsLimit() {
        assertEquals("Internal limit or revision change", sourceCoverage(truncated = true).toAnalysisCoverage().reason)
    }
}
