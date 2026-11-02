/*
 * Copyright © 2025-2026 | Shamash
 * SPDX-License-Identifier: Apache-2.0
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
            )
        return request
    }

    @Synchronized
    fun advance(request: AnalysisRequest, phase: AnalysisPhase, progress: Double? = null): Boolean {
        require(phase == AnalysisPhase.EXTRACTING || phase == AnalysisPhase.BUILDING_GRAPH)
        if (!eligible(request) || !current.active) return false
        current = current.copy(phase = phase, progress = progress?.coerceIn(0.0, 1.0))
        return true
    }

    @Synchronized
    fun publish(request: AnalysisRequest, outcome: AnalysisOutcome<T>): Boolean {
        if (!eligible(request) || !current.active) return false
        val result =
            when (outcome) {
                is AnalysisOutcome.Complete ->
                    AnalysisSnapshot(request, Completeness.COMPLETE, outcome.coverage, outcome.data, outcome.diagnostics.toList())
                is AnalysisOutcome.Partial ->
                    AnalysisSnapshot(request, Completeness.PARTIAL, outcome.coverage, outcome.data, outcome.diagnostics.toList())
                is AnalysisOutcome.Unavailable ->
                    AnalysisSnapshot<T>(
                        request, Completeness.UNAVAILABLE, outcome.coverage,
                        null, listOf(outcome.reason),
                    )
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
                visibleResult = if (result.completeness == Completeness.UNAVAILABLE) current.visibleResult else result,
                resultRelation =
                    if (result.completeness == Completeness.UNAVAILABLE) {
                        current.visibleResult?.let { ResultRelation.PREVIOUS }
                    } else {
                        ResultRelation.CURRENT
                    },
                diagnostics = result.diagnostics,
                progress = null,
            )
        return true
    }

    @Synchronized
    fun fail(request: AnalysisRequest, message: String): Boolean {
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
                diagnostics = listOf(if (source) "Sources changed; analyze again" else "Source roots changed; analyze again"),
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
