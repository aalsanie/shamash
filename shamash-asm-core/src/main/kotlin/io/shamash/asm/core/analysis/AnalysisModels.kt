/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.analysis

import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoreThresholds

data class AnalysisResult(
    val graphs: GraphAnalysisResult? = null,
    val hotspots: HotspotsResult? = null,
    val scoring: ScoringResult? = null,
) {
    val isEmpty: Boolean get() = graphs == null && hotspots == null && scoring == null
}

data class GraphAnalysisResult(
    val granularity: Granularity,
    val includeExternalBuckets: Boolean,
    /** All nodes sorted ascending. */
    val nodes: List<String>,
    /** Adjacency list: key order and targets order are sorted. */
    val adjacency: Map<String, List<String>>,
    val nodeCount: Int,
    val edgeCount: Int,
    /** Total SCC count (includes singletons). */
    val sccCount: Int,
    /** SCCs that represent cycles (size>1 or self-loop). Each SCC list is sorted. */
    val cyclicSccs: List<List<String>>,
    /** Bounded samples; may be truncated and do not enumerate every cycle. */
    val representativeCycles: List<List<String>>,
)

data class HotspotsResult(
    val topN: Int,
    val includeExternal: Boolean,
    /** Sorted by maxMetricValue descending, then id ascending. */
    val classHotspots: List<HotspotEntry>,
    val packageHotspots: List<HotspotEntry>,
)

data class HotspotEntry(
    val kind: HotspotKind,
    val id: String,
    /** Stable order: by metric name asc. */
    val reasons: List<HotspotReason>,
) {
    val maxMetricValue: Int = reasons.maxOfOrNull { it.value } ?: 0
}

enum class HotspotKind { CLASS, PACKAGE }

data class HotspotReason(
    val metric: HotspotMetric,
    val value: Int,
    /** Rank within the metric's top list (1..topN). */
    val rank: Int,
)

enum class HotspotMetric {
    FAN_IN,
    FAN_OUT,
    PACKAGE_SPREAD,
    METHOD_COUNT,
}

data class ScoringResult(
    val model: ScoreModel,
    val godClass: GodClassScoringResult? = null,
    val overall: OverallScoringResult? = null,
) {
    val isEmpty: Boolean get() = godClass == null && overall == null
}

data class GodClassScoringResult(
    val thresholds: ScoreThresholds,
    val rows: List<ClassScoreRow>,
)

data class OverallScoringResult(
    val thresholds: ScoreThresholds,
    val rows: List<PackageScoreRow>,
)

data class ClassScoreRow(
    val classFqn: String,
    val packageName: String,
    /** 0..1 */
    val score: Double,
    val band: SeverityBand,
    val raw: Map<String, Int>,
    /** Normalized components (0..1) used in the score. */
    val normalized: Map<String, Double>,
)

data class PackageScoreRow(
    val packageName: String,
    /** 0..1 */
    val score: Double,
    val band: SeverityBand,
    val raw: Map<String, Double>,
    val normalized: Map<String, Double>,
)

enum class SeverityBand { OK, WARN, ERROR }
