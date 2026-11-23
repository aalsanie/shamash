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
package io.shamash.intellij.plugin.ui.source

import io.shamash.intellij.plugin.analysis.AnalysisCoverage
import io.shamash.intellij.plugin.analysis.AnalysisPhase
import io.shamash.intellij.plugin.analysis.AnalysisRequest
import io.shamash.intellij.plugin.analysis.AnalysisSnapshot
import io.shamash.intellij.plugin.analysis.Completeness
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.IdeAnalysisState
import io.shamash.intellij.plugin.analysis.ResultRelation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceGuidanceTest {
    private fun state(phase: AnalysisPhase) = IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation>(phase = phase)

    @Test
    fun allLifecyclePhasesHaveTruthfulProductCopy() {
        assertEquals("Understand your project's dependencies", SourceGuidance.status(state(AnalysisPhase.IDLE)))
        assertEquals(
            "Waiting for IntelliJ to finish indexing",
            SourceGuidance.status(state(AnalysisPhase.WAITING_FOR_INDEX)),
        )
        assertEquals("Analyzing source dependencies…", SourceGuidance.status(state(AnalysisPhase.EXTRACTING)))
        assertEquals("Analyzing source dependencies…", SourceGuidance.status(state(AnalysisPhase.BUILDING_GRAPH)))
        assertEquals("Source analysis complete", SourceGuidance.status(state(AnalysisPhase.COMPLETED)))
        assertEquals(
            "Some source dependencies could not be analyzed",
            SourceGuidance.status(state(AnalysisPhase.PARTIAL)),
        )
        assertEquals("Source analysis couldn't finish", SourceGuidance.status(state(AnalysisPhase.FAILED)))
        assertEquals("Analysis cancelled", SourceGuidance.status(state(AnalysisPhase.CANCELLED)))
        assertEquals("Code changed since this analysis", SourceGuidance.status(state(AnalysisPhase.STALE)))
        assertEquals(
            "Source analysis is unavailable for this project",
            SourceGuidance.status(state(AnalysisPhase.UNAVAILABLE)),
        )
    }

    @Test
    fun unavailableNoSourceDoesNotClaimCleanArchitecture() {
        val s =
            state(AnalysisPhase.UNAVAILABLE).copy(
                attemptCoverage = AnalysisCoverage(eligibleFiles = 0, reason = "NO_SOURCE"),
                diagnostics = listOf("No supported production Java/Kotlin source files"),
            )
        assertEquals("No supported production source found", SourceGuidance.status(s))
        assertTrue(SourceGuidance.detailStatus(s).contains("Import a Java/Kotlin JVM module"))
        assertTrue(SourceGuidance.detailStatus(s).contains("No supported production"))
    }

    @Test
    fun staleStatusDistinguishesRootsFromSourceChanges() {
        val old =
            AnalysisSnapshot(
                AnalysisRequest(1, 0, 0),
                Completeness.COMPLETE,
                AnalysisCoverage(1, 1),
                IdeAnalysisCoordinator.SourcePresentation(),
                emptyList(),
            )
        val rootChange =
            state(AnalysisPhase.STALE).copy(
                rootRevision = 2,
                sourceRevision = 0,
                visibleResult = old,
                resultRelation = ResultRelation.STALE,
            )
        val edit = rootChange.copy(rootRevision = 0, sourceRevision = 1)
        assertEquals("Project structure changed; analyze again", SourceGuidance.status(rootChange))
        assertEquals("Code changed since this analysis", SourceGuidance.status(edit))
        assertTrue(SourceGuidance.detailStatus(rootChange).contains("Previous analysis evidence"))
    }

    @Test
    fun partialAndFailedStatesRetainDiagnosticsAndExplicitLimits() {
        val previous =
            AnalysisSnapshot(
                AnalysisRequest(1, 0, 0),
                Completeness.PARTIAL,
                AnalysisCoverage(4, 3, 1, 10, 2),
                IdeAnalysisCoordinator.SourcePresentation(),
                listOf("previous failure"),
            )
        val failed =
            state(AnalysisPhase.FAILED).copy(
                visibleResult = previous,
                resultRelation = ResultRelation.PREVIOUS,
                attemptCoverage = AnalysisCoverage(4, 3, 1, 10, 2, "Internal limit"),
                diagnostics = (1..12).map { "error " + it },
            )
        val message = SourceGuidance.detailStatus(failed)
        assertTrue(message.contains("3 of 4"))
        assertTrue(message.contains("2 unresolved"))
        assertTrue(message.contains("1 files could not"))
        assertTrue(message.contains("Showing 10 of 12 diagnostics"))
        assertTrue(message.contains("Previous analysis"))
        assertTrue(message.contains("missing cycles cannot be ruled out"))
    }

    @Test
    fun unknownKotlinPlatformIsUnavailableWithScopeExplanation() {
        val unavailable =
            state(AnalysisPhase.UNAVAILABLE).copy(
                attemptCoverage =
                    AnalysisCoverage(
                        eligibleFiles = 0,
                        reason = "UNKNOWN_PLATFORM",
                        excludedFiles = 3,
                        excludedReasons = mapOf("UNKNOWN_PLATFORM" to 1, "NON_JVM" to 2),
                    ),
            )
        assertEquals("Source analysis is unavailable for this project", SourceGuidance.status(unavailable))
        val details = SourceGuidance.detailStatus(unavailable)
        assertTrue(details.contains("target platform metadata is unavailable"))
        assertTrue(details.contains("1 unknown platform source files"))
        assertTrue(details.contains("2 non jvm source files"))
    }

    @Test
    fun completedJvmSubsetExplainsExplicitlyExcludedPlatforms() {
        val facts =
            io.shamash.psi.core.source.ProjectSourceSnapshot(
                io.shamash.psi.core.source
                    .SourceAnalysisProfile(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                io.shamash.psi.core.source.SourceCoverage(
                    1,
                    1,
                    0,
                    0,
                    0,
                    3,
                    false,
                    mapOf("NON_JVM" to 2, "TEST" to 1),
                ),
                true,
            )
        val current =
            AnalysisSnapshot(
                AnalysisRequest(1, 0, 0),
                Completeness.COMPLETE,
                AnalysisCoverage(1, 1),
                IdeAnalysisCoordinator.SourcePresentation(sourceFacts = facts),
                emptyList(),
            )
        val completed =
            state(AnalysisPhase.COMPLETED).copy(
                visibleResult = current,
                resultRelation = ResultRelation.CURRENT,
            )
        assertTrue(SourceGuidance.detailStatus(completed).contains("2 non jvm source files"))
        assertTrue(SourceGuidance.detailStatus(completed).contains("1 test source files"))
    }

    @Test
    fun exclusionSummaryPrefersCurrentAttemptButFallsBackToStoredEvidence() {
        val facts =
            io.shamash.psi.core.source.ProjectSourceSnapshot(
                io.shamash.psi.core.source
                    .SourceAnalysisProfile(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                io.shamash.psi.core.source.SourceCoverage(
                    1,
                    1,
                    0,
                    0,
                    0,
                    1,
                    false,
                    mapOf("NON_JVM" to 1),
                ),
                true,
            )
        val result =
            AnalysisSnapshot(
                AnalysisRequest(1, 0, 0),
                Completeness.COMPLETE,
                AnalysisCoverage(1, 1),
                IdeAnalysisCoordinator.SourcePresentation(sourceFacts = facts),
                emptyList(),
            )
        val fallback =
            state(AnalysisPhase.COMPLETED).copy(
                visibleResult = result,
                resultRelation = ResultRelation.CURRENT,
                attemptCoverage = AnalysisCoverage(excludedReasons = emptyMap()),
            )
        assertTrue(SourceGuidance.detailStatus(fallback).contains("1 non jvm source files"))

        val current =
            fallback.copy(
                attemptCoverage = AnalysisCoverage(excludedReasons = mapOf("GENERATED" to 2)),
            )
        val currentDetails = SourceGuidance.detailStatus(current)
        assertTrue(currentDetails.contains("2 generated source files"))
        assertTrue(!currentDetails.contains("1 non jvm source files"))

        val missingFacts = fallback.copy(visibleResult = result.copy(payload = null))
        assertTrue(!SourceGuidance.detailStatus(missingFacts).contains("Outside this JVM scope"))
        assertTrue(!SourceGuidance.detailStatus(state(AnalysisPhase.COMPLETED)).contains("Outside this JVM scope"))
    }

    @Test
    fun firstUseHelpIsOfflineAndExplainsLimitsAndAdvancedCapabilities() {
        val copy = SourceGuidance.detailStatus(state(AnalysisPhase.IDLE))
        assertTrue(copy.contains("Analyze project"))
        assertTrue(SourceGuidance.SCOPE_LINE.contains("Project rules not evaluated"))
        assertTrue(SourceGuidance.OFFLINE_HELP.contains("Java/Kotlin"))
        assertTrue(SourceGuidance.CYCLE_EXPLANATION.contains("may be intentional"))
        assertTrue(SourceGuidance.CYCLE_EXPLANATION.contains("removing a displayed edge"))
        assertTrue(SourceGuidance.REPOSITORY_URL.startsWith("https://github.com/aalsanie/shamash"))
        assertTrue(SourceGuidance.SOURCE_URL.startsWith(SourceGuidance.REPOSITORY_URL))
        assertTrue(SourceGuidance.LICENSE_URL.endsWith("/shamash-intellij-plugin/LICENSE"))
        assertTrue(SourceGuidance.CORE_LICENSE_URL.endsWith("/LICENSE"))
    }

    @Test
    fun installedSourceLinksArePinnedToTheirMatchingCommitAndCannotBeRedirected() {
        val commit = "0123456789abcdef0123456789abcdef01234567"
        val exact = SourceGuidance.sourceRoot(SourceGuidance.REPOSITORY_URL + "/tree/" + commit + "\n")
        assertEquals(SourceGuidance.REPOSITORY_URL + "/tree/" + commit, exact)
        assertEquals(
            SourceGuidance.REPOSITORY_URL + "/tree/v0.92.0",
            SourceGuidance.sourceRoot(SourceGuidance.REPOSITORY_URL + "/tree/v0.92.0"),
        )
        assertEquals(SourceGuidance.REPOSITORY_URL + "/tree/main", SourceGuidance.sourceRoot(null))
        assertEquals(
            SourceGuidance.REPOSITORY_URL + "/tree/main",
            SourceGuidance.sourceRoot("https://evil.invalid/tree/" + commit),
        )
        assertEquals(
            SourceGuidance.REPOSITORY_URL + "/tree/main",
            SourceGuidance.sourceRoot(SourceGuidance.REPOSITORY_URL + "/tree/" + commit + "/other"),
        )
    }
}
