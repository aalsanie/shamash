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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisLifecycleTest {
    private fun complete(name: String) = AnalysisOutcome.Complete(name, AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1))
    private fun partial(name: String?) =
        AnalysisOutcome.Partial(name, AnalysisCoverage(eligibleFiles = 2, analyzedFiles = 1, failedFiles = 1), listOf("resolution incomplete"))

    @Test
    fun `late A cannot replace B even when A finishes after B`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.advance(a, AnalysisPhase.EXTRACTING))
        val b = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.publish(b, complete("new")))
        assertFalse(lifecycle.publish(a, complete("old")))
        assertEquals("new", lifecycle.snapshot().visibleResult?.payload)
        assertEquals(b.generation, lifecycle.snapshot().visibleResult?.request?.generation)
        assertEquals(ResultRelation.CURRENT, lifecycle.snapshot().resultRelation)
    }

    @Test
    fun `cancellation before indexing rejects late work`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.cancel())
        assertFalse(lifecycle.advance(a, AnalysisPhase.EXTRACTING))
        assertFalse(lifecycle.publish(a, complete("old")))
        assertEquals(AnalysisPhase.CANCELLED, lifecycle.snapshot().phase)
        assertFalse(lifecycle.cancel())
    }

    @Test
    fun `cancellation during extraction and graph invalidates publication`() {
        for (phase in listOf(AnalysisPhase.EXTRACTING, AnalysisPhase.BUILDING_GRAPH)) {
            val lifecycle = AnalysisLifecycle<String>()
            val request = requireNotNull(lifecycle.begin())
            assertTrue(lifecycle.advance(request, phase))
            assertTrue(lifecycle.cancel())
            assertFalse(lifecycle.publish(request, complete("stale")))
            assertNull(lifecycle.snapshot().visibleResult)
        }
    }

    @Test
    fun `analyze after prior success retains prior until new complete snapshot`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.publish(a, complete("previous")))
        val b = requireNotNull(lifecycle.begin())
        assertEquals(ResultRelation.PREVIOUS, lifecycle.snapshot().resultRelation)
        assertEquals("previous", lifecycle.snapshot().visibleResult?.payload)
        assertTrue(lifecycle.publish(b, complete("current")))
        assertEquals(ResultRelation.CURRENT, lifecycle.snapshot().resultRelation)
        assertEquals("current", lifecycle.snapshot().visibleResult?.payload)
    }

    @Test
    fun `failure preserves previous evidence and retains diagnostics`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        lifecycle.publish(a, complete("previous"))
        val b = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.fail(b, "source parser failed"))
        assertEquals(AnalysisPhase.FAILED, lifecycle.snapshot().phase)
        assertEquals(listOf("source parser failed"), lifecycle.snapshot().diagnostics)
        assertEquals(ResultRelation.PREVIOUS, lifecycle.snapshot().resultRelation)
        assertEquals("previous", lifecycle.snapshot().visibleResult?.payload)
        assertFalse(lifecycle.publish(b, complete("not allowed")))
    }

    @Test
    fun `partial scan retains coverage and cannot claim clean completion`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.publish(a, partial(null)))
        val state = lifecycle.snapshot()
        assertEquals(AnalysisPhase.PARTIAL, state.phase)
        assertEquals(Completeness.PARTIAL, state.visibleResult?.completeness)
        assertEquals(1, state.visibleResult?.coverage?.failedFiles)
        assertEquals(listOf("resolution incomplete"), state.diagnostics)
    }

    @Test
    fun `no supported source remains unavailable not clean`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.publish(a, AnalysisOutcome.Unavailable("NO_SOURCE")))
        assertEquals(AnalysisPhase.UNAVAILABLE, lifecycle.snapshot().phase)
        assertEquals(listOf("NO_SOURCE"), lifecycle.snapshot().diagnostics)
        assertNull(lifecycle.snapshot().visibleResult)
    }

    @Test
    fun `unavailable scan preserves previous snapshot with clear relation`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        lifecycle.publish(a, complete("previous"))
        val b = requireNotNull(lifecycle.begin())
        lifecycle.publish(b, AnalysisOutcome.Unavailable("not supported"))
        assertEquals(ResultRelation.PREVIOUS, lifecycle.snapshot().resultRelation)
        assertEquals("previous", lifecycle.snapshot().visibleResult?.payload)
        assertEquals(AnalysisPhase.UNAVAILABLE, lifecycle.snapshot().phase)
    }

    @Test
    fun `source modification invalidates active work and keeps stale result`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        lifecycle.publish(a, complete("previous"))
        val b = requireNotNull(lifecycle.begin())
        lifecycle.sourceChanged()
        assertFalse(lifecycle.publish(b, complete("mixed revisions")))
        val state = lifecycle.snapshot()
        assertEquals(AnalysisPhase.STALE, state.phase)
        assertEquals(ResultRelation.STALE, state.resultRelation)
        assertEquals("previous", state.visibleResult?.payload)
        assertEquals(1, state.sourceRevision)
    }

    @Test
    fun `source root change invalidates active work independently`() {
        val lifecycle = AnalysisLifecycle<String>()
        val request = requireNotNull(lifecycle.begin())
        lifecycle.rootsChanged()
        assertFalse(lifecycle.isCurrent(request))
        assertFalse(lifecycle.publish(request, complete("invalid")))
        assertEquals(1, lifecycle.snapshot().rootRevision)
        assertEquals(0, lifecycle.snapshot().sourceRevision)
    }

    @Test
    fun `closing a project blocks future work and callbacks`() {
        val lifecycle = AnalysisLifecycle<String>()
        val request = requireNotNull(lifecycle.begin())
        lifecycle.dispose()
        lifecycle.dispose()
        assertNull(lifecycle.begin())
        assertFalse(lifecycle.advance(request, AnalysisPhase.EXTRACTING))
        assertFalse(lifecycle.publish(request, complete("late")))
        assertFalse(lifecycle.fail(request, "late"))
        assertEquals(AnalysisPhase.CANCELLED, lifecycle.snapshot().phase)
    }

    @Test
    fun `terminal callbacks and malformed phase updates are rejected`() {
        val lifecycle = AnalysisLifecycle<String>()
        val request = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.advance(request, AnalysisPhase.EXTRACTING, 1.2))
        assertEquals(1.0, lifecycle.snapshot().progress)
        assertTrue(lifecycle.publish(request, complete("done")))
        assertFalse(lifecycle.fail(request, "late"))
        assertFalse(lifecycle.advance(request, AnalysisPhase.BUILDING_GRAPH))
        assertFalse(lifecycle.cancel())
    }

    @Test
    fun `zero eligible sources cannot be marked complete by a backend`() {
        val lifecycle = AnalysisLifecycle<String>()
        val request = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.publish(request, AnalysisOutcome.Complete("claimed clean", AnalysisCoverage(eligibleFiles = 0))))
        val state = lifecycle.snapshot()
        assertEquals(AnalysisPhase.UNAVAILABLE, state.phase)
        assertEquals(Completeness.UNAVAILABLE, state.attemptCompleteness)
        assertNull(state.visibleResult)
    }

    @Test
    fun `failed files unresolved edges and missing analyses force partial completeness`() {
        val coverages =
            listOf(
                AnalysisCoverage(eligibleFiles = 2, analyzedFiles = 2, failedFiles = 1),
                AnalysisCoverage(eligibleFiles = 2, analyzedFiles = 2, unresolvedOccurrences = 1),
                AnalysisCoverage(eligibleFiles = 2, analyzedFiles = 1, failedFiles = 0),
            )
        for (coverage in coverages) {
            val lifecycle = AnalysisLifecycle<String>()
            val request = requireNotNull(lifecycle.begin())
            assertTrue(lifecycle.publish(request, AnalysisOutcome.Complete("partial", coverage)))
            assertEquals(AnalysisPhase.PARTIAL, lifecycle.snapshot().phase)
            assertEquals(Completeness.PARTIAL, lifecycle.snapshot().attemptCompleteness)
            assertTrue(lifecycle.snapshot().diagnostics.any { it.contains("incomplete") })
        }
    }

    @Test
    fun `unavailable attempt retains its own zero-file coverage alongside previous evidence`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        lifecycle.publish(a, complete("previous"))
        val b = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.publish(b, AnalysisOutcome.Unavailable(
            "NO_SOURCE", AnalysisCoverage(eligibleFiles = 0, analyzedFiles = 0),
        )))
        assertEquals("previous", lifecycle.snapshot().visibleResult?.payload)
        assertEquals(ResultRelation.PREVIOUS, lifecycle.snapshot().resultRelation)
        assertEquals(0, lifecycle.snapshot().attemptCoverage?.eligibleFiles)
    }

    @Test
    fun `cancelled refresh preserves visibly previous evidence`() {
        val lifecycle = AnalysisLifecycle<String>()
        val a = requireNotNull(lifecycle.begin())
        lifecycle.publish(a, complete("old"))
        val b = requireNotNull(lifecycle.begin())
        assertTrue(lifecycle.cancel())
        assertFalse(lifecycle.publish(b, complete("late")))
        assertEquals("old", lifecycle.snapshot().visibleResult?.payload)
        assertEquals(ResultRelation.PREVIOUS, lifecycle.snapshot().resultRelation)
    }
}
