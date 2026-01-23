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
package io.shamash.asm.core.analysis

import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoreThresholds

/**
 * Engine-side analysis outputs.
 */
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
    /** Representative cycle paths (bounded, best-effort). */
    val representativeCycles: List<List<String>>,
)

data class HotspotsResult(
    val topN: Int,
    val includeExternal: Boolean,
    /** Hotspots aggregated across metrics. Stable order: by maxMetricValue desc, then id asc. */
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
    /** Raw metrics used by the model. */
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
