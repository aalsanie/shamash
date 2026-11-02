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

enum class AnalysisPhase {
    IDLE,
    WAITING_FOR_INDEX,
    EXTRACTING,
    BUILDING_GRAPH,
    COMPLETED,
    PARTIAL,
    UNAVAILABLE,
    FAILED,
    CANCELLED,
    STALE,
}
enum class ResultRelation { CURRENT, PREVIOUS, STALE }
enum class Completeness { COMPLETE, PARTIAL, UNAVAILABLE }

data class AnalysisCoverage(
    val eligibleFiles: Int? = null,
    val analyzedFiles: Int? = null,
    val failedFiles: Int? = null,
    val resolvedOccurrences: Long? = null,
    val unresolvedOccurrences: Long? = null,
    val reason: String? = null,
)

data class AnalysisRequest(
    val generation: Long,
    val sourceRevision: Long,
    val rootRevision: Long,
    val profileId: String = "ide-source-v1",
)

data class AnalysisSnapshot<T : Any>(
    val request: AnalysisRequest,
    val completeness: Completeness,
    val coverage: AnalysisCoverage,
    val payload: T?,
    val diagnostics: List<String>,
)

data class IdeAnalysisState<T : Any>(
    val requestGeneration: Long = 0,
    val phase: AnalysisPhase = AnalysisPhase.IDLE,
    val visibleResult: AnalysisSnapshot<T>? = null,
    val resultRelation: ResultRelation? = null,
    val diagnostics: List<String> = emptyList(),
    val progress: Double? = null,
    val attemptCoverage: AnalysisCoverage? = null,
    val attemptCompleteness: Completeness? = null,
    val sourceRevision: Long = 0,
    val rootRevision: Long = 0,
) {
    val active: Boolean get() =
        phase == AnalysisPhase.WAITING_FOR_INDEX ||
            phase == AnalysisPhase.EXTRACTING ||
            phase == AnalysisPhase.BUILDING_GRAPH
}

sealed interface AnalysisOutcome<out T : Any> {
    data class Complete<T : Any>(
        val data: T,
        val coverage: AnalysisCoverage,
        val diagnostics: List<String> = emptyList(),
    ) : AnalysisOutcome<T>

    data class Partial<T : Any>(
        val data: T?,
        val coverage: AnalysisCoverage,
        val diagnostics: List<String>,
    ) : AnalysisOutcome<T>

    data class Unavailable(
        val reason: String,
        val coverage: AnalysisCoverage = AnalysisCoverage(),
    ) : AnalysisOutcome<Nothing>
}
