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

/**
 * A synchronized, generation-owned state machine. It never invokes listeners while holding its monitor.
 * Jobs may finish in any order; only the current, unmodified request can publish.
 */
class AnalysisLifecycle<T : Any> {
    private var current = IdeAnalysisState<T>()
    private var closed = false

    @Synchronized
    fun snapshot(): IdeAnalysisState<T> = current

    @Synchronized
    fun begin(): AnalysisRequest? {
        if (closed) return null
        val request =
            AnalysisRequest(
                generation = current.requestGeneration + 1,
                sourceRevision = current.sourceRevision,
                rootRevision = current.rootRevision,
            )
        current =
            current.copy(
                requestGeneration = request.generation,
                phase = AnalysisPhase.WAITING_FOR_INDEX,
                resultRelation = current.visibleResult?.let { ResultRelation.PREVIOUS },
                diagnostics = emptyList(),
                progress = null,
                attemptCoverage = null,
                attemptCompleteness = null,
            )
        return request
    }

    @Synchronized
    fun advance(
        request: AnalysisRequest,
        phase: AnalysisPhase,
        progress: Double? = null,
    ): Boolean {
        require(phase == AnalysisPhase.EXTRACTING || phase == AnalysisPhase.BUILDING_GRAPH)
        if (!eligible(request) || !current.active) return false
        if (phase == AnalysisPhase.EXTRACTING && current.phase == AnalysisPhase.BUILDING_GRAPH) return false
        if (progress != null && !progress.isFinite()) return false
        current = current.copy(phase = phase, progress = progress?.coerceIn(0.0, 1.0))
        return true
    }

    @Synchronized
    fun publish(
        request: AnalysisRequest,
        outcome: AnalysisOutcome<T>,
    ): Boolean {
        if (!eligible(request) || !current.active) return false
        val normalized =
            if (outcome is AnalysisOutcome.Complete) {
                val coverage = outcome.coverage
                when {
                    coverage.eligibleFiles == 0 -> {
                        AnalysisOutcome.Unavailable("No supported Java/Kotlin source files", coverage)
                    }

                    coverage.eligibleFiles == null ||
                        coverage.analyzedFiles == null ||
                        coverage.eligibleFiles < 0 ||
                        coverage.analyzedFiles < 0 ||
                        coverage.analyzedFiles > coverage.eligibleFiles ||
                        (coverage.failedFiles ?: 0) < 0 ||
                        (coverage.unresolvedOccurrences ?: 0) < 0 ||
                        (coverage.resolvedOccurrences ?: 0) < 0 ||
                        (coverage.failedFiles ?: 0) > 0 ||
                        (coverage.unresolvedOccurrences ?: 0) > 0 ||
                        (
                            coverage.eligibleFiles != null &&
                                coverage.analyzedFiles != null &&
                                coverage.analyzedFiles < coverage.eligibleFiles
                        ) -> {
                        AnalysisOutcome.Partial(
                            outcome.data,
                            coverage,
                            outcome.diagnostics + "Analysis incomplete; conclusions may omit relationships",
                        )
                    }

                    else -> {
                        outcome
                    }
                }
            } else {
                outcome
            }
        val result =
            when (normalized) {
                is AnalysisOutcome.Complete -> {
                    AnalysisSnapshot(
                        request,
                        Completeness.COMPLETE,
                        normalized.coverage,
                        normalized.data,
                        normalized.diagnostics.toList(),
                    )
                }

                is AnalysisOutcome.Partial -> {
                    AnalysisSnapshot(
                        request,
                        Completeness.PARTIAL,
                        normalized.coverage,
                        normalized.data,
                        normalized.diagnostics.toList(),
                    )
                }

                is AnalysisOutcome.Unavailable -> {
                    AnalysisSnapshot<T>(
                        request,
                        Completeness.UNAVAILABLE,
                        normalized.coverage,
                        null,
                        listOf(normalized.reason),
                    )
                }
            }
        val phase =
            when (result.completeness) {
                Completeness.COMPLETE -> AnalysisPhase.COMPLETED
                Completeness.PARTIAL -> AnalysisPhase.PARTIAL
                Completeness.UNAVAILABLE -> AnalysisPhase.UNAVAILABLE
            }
        current =
            current.copy(
                phase = phase,
                visibleResult =
                    if (result.completeness == Completeness.UNAVAILABLE ||
                        (
                            result.completeness == Completeness.PARTIAL &&
                                result.payload == null && current.visibleResult != null
                        )
                    ) {
                        current.visibleResult
                    } else {
                        result
                    },
                resultRelation =
                    if (result.completeness == Completeness.UNAVAILABLE ||
                        (result.completeness == Completeness.PARTIAL && result.payload == null)
                    ) {
                        current.visibleResult?.let { ResultRelation.PREVIOUS }
                    } else {
                        ResultRelation.CURRENT
                    },
                diagnostics = result.diagnostics,
                progress = null,
                attemptCoverage = result.coverage,
                attemptCompleteness = result.completeness,
            )
        return true
    }

    @Synchronized
    fun fail(
        request: AnalysisRequest,
        message: String,
    ): Boolean {
        if (!eligible(request) || !current.active) return false
        current =
            current.copy(
                phase = AnalysisPhase.FAILED,
                resultRelation = current.visibleResult?.let { ResultRelation.PREVIOUS },
                diagnostics = listOf(message),
                progress = null,
            )
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        if (closed || !current.active) return false
        current =
            current.copy(
                requestGeneration = current.requestGeneration + 1,
                phase = AnalysisPhase.CANCELLED,
                resultRelation = current.visibleResult?.let { ResultRelation.PREVIOUS },
                progress = null,
            )
        return true
    }

    @Synchronized
    fun sourceChanged() = invalidate(source = true)

    @Synchronized
    fun rootsChanged() = invalidate(source = false)

    private fun invalidate(source: Boolean) {
        if (closed) return
        current =
            current.copy(
                requestGeneration = current.requestGeneration + 1,
                sourceRevision = current.sourceRevision + if (source) 1 else 0,
                rootRevision = current.rootRevision + if (source) 0 else 1,
                phase = AnalysisPhase.STALE,
                resultRelation = current.visibleResult?.let { ResultRelation.STALE },
                progress = null,
                diagnostics =
                    listOf(
                        if (source) "Sources changed; analyze again" else "Source roots changed; analyze again",
                    ),
            )
    }

    @Synchronized
    fun dispose() {
        if (closed) return
        closed = true
        current =
            current.copy(
                requestGeneration = current.requestGeneration + 1,
                phase = AnalysisPhase.CANCELLED,
                resultRelation = current.visibleResult?.let { ResultRelation.PREVIOUS },
                progress = null,
            )
    }

    @Synchronized
    fun isCurrent(request: AnalysisRequest): Boolean = eligible(request) && current.active

    private fun eligible(request: AnalysisRequest): Boolean =
        !closed &&
            current.requestGeneration == request.generation &&
            current.sourceRevision == request.sourceRevision &&
            current.rootRevision == request.rootRevision
}
