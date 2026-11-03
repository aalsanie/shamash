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

import io.shamash.intellij.plugin.analysis.AnalysisPhase
import io.shamash.intellij.plugin.analysis.Completeness
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.IdeAnalysisState

internal object SourceGuidance {
    const val REPOSITORY_URL = "https://github.com/aalsanie/shamash"
    const val LICENSE_URL = "https://github.com/aalsanie/shamash/blob/main/shamash-intellij-plugin/LICENSE"
    const val CORE_LICENSE_URL = "https://github.com/aalsanie/shamash/blob/main/LICENSE"
    const val SOURCE_URL = "https://github.com/aalsanie/shamash/tree/main/shamash-intellij-plugin"
    const val SCOPE_LINE = "Production source · Project rules not evaluated."
    const val SCOPE_DETAILS =
        "Analyzes Java and Kotlin JVM production source roots in the imported IntelliJ project. " +
            "Tests, generated and excluded sources, libraries, scripts, and non-JVM roots are excluded. " +
            "This is static source evidence, not runtime behavior, architecture enforcement, or a scan of project YAML."
    const val FIRST_RUN =
        "Understand your project's dependencies\n\n" +
            "Analyze project to inspect production Java/Kotlin source without configuration or a build. " +
            "Investigate package cycles in Findings and browse Uses / Used by in Explore."
    const val CYCLE_EXPLANATION =
        "Why it may matter\n" +
            "Changes in one package may require changes in another, which can make independent testing or reuse harder. " +
            "The dependency may be intentional.\n\n" +
            "What to try next\n" +
            "Inspect the references below. Check whether one dependency represents a shared concept that belongs in a " +
            "common API, or whether these packages represent one responsibility that should stay together. " +
            "Introducing an interface alone may not remove a package dependency.\n\n" +
            "Limits\n" +
            "These are resolved, static source relationships—not runtime calls or a policy violation. " +
            "A representative cycle is only one path within the whole cyclic group; removing a displayed edge " +
            "does not necessarily remove every cycle. Reanalyze after changes."
    const val OFFLINE_HELP =
        "About these checks\n\n" +
            "Shamash observes resolved Java/Kotlin source dependencies in imported production roots. " +
            "Findings show cyclic groups of packages; Explore shows declared dependencies and their witnesses. " +
            "No team rules are evaluated. Nothing is changed, exported, or uploaded.\n\n" +
            "How to investigate a cycle\n" +
            "Select a cycle, inspect its directed edges, then select a source reference. " +
            "Use Open source to navigate. If a reference changed, analyze again.\n\n" +
            "A large cyclic group can contain more paths than the representative shown. " +
            "Treat the finding as an observation and discuss whether the relationship is intentional.\n\n" +
            SCOPE_DETAILS + "\n\nFor rules, reports, baselines, and CI enforcement, visit the Shamash repository."

    fun status(state: IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation>): String =
        when (state.phase) {
            AnalysisPhase.IDLE -> "Understand your project's dependencies"
            AnalysisPhase.WAITING_FOR_INDEX -> "Waiting for IntelliJ to finish indexing"
            AnalysisPhase.EXTRACTING, AnalysisPhase.BUILDING_GRAPH -> "Analyzing source dependencies…"
            AnalysisPhase.COMPLETED ->
                if (state.visibleResult?.payload?.sourceGraph?.cycleAbsenceEstablished == true)
                    "No package cycles found in the analyzed source"
                else "Source analysis complete"
            AnalysisPhase.PARTIAL -> "Some source dependencies could not be analyzed"
            AnalysisPhase.UNAVAILABLE ->
                if (state.attemptCoverage?.reason == "NO_SOURCE") "No supported production source found"
                else "Source analysis is unavailable for this project"
            AnalysisPhase.FAILED -> "Source analysis couldn't finish"
            AnalysisPhase.CANCELLED -> "Analysis cancelled"
            AnalysisPhase.STALE ->
                if (state.visibleResult != null && state.rootRevision != state.visibleResult.request.rootRevision)
                    "Project structure changed; analyze again"
                else "Code changed since this analysis"
        }

    fun detailStatus(state: IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation>): String {
        val attempt = state.attemptCoverage
        val result = state.visibleResult
        val details = ArrayList<String>()
        if (state.phase == AnalysisPhase.IDLE) details += FIRST_RUN
        if (state.phase == AnalysisPhase.UNAVAILABLE && attempt?.reason == "NO_SOURCE") {
            details += "Import a Java/Kotlin JVM module and mark its production source roots in IntelliJ."
        }
        if (attempt?.eligibleFiles != null) {
            details += "Analyzed " + (attempt.analyzedFiles ?: 0) + " of " + attempt.eligibleFiles +
                " supported source files"
        }
        if (attempt?.unresolvedOccurrences != null && attempt.unresolvedOccurrences > 0L) {
            details += attempt.unresolvedOccurrences.toString() + " unresolved source references"
        }
        if (attempt?.failedFiles != null && attempt.failedFiles > 0) {
            details += attempt.failedFiles.toString() + " files could not be analyzed"
        }
        if (attempt?.reason != null && attempt.reason != "NO_SOURCE") details += attempt.reason
        if (state.diagnostics.isNotEmpty()) {
            details += state.diagnostics.take(10)
            if (state.diagnostics.size > 10) {
                details += "Showing 10 of " + state.diagnostics.size + " diagnostics"
            }
        }
        if (result != null && state.resultRelation?.name != "CURRENT") {
            details += "Previous analysis evidence remains visible; it is not current."
        }
        if (result?.completeness == Completeness.PARTIAL) {
            details += "Known dependencies may be inspected, but missing cycles cannot be ruled out."
        }
        return details.joinToString("\n")
    }
}
