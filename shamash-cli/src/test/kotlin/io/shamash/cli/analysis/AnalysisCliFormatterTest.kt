/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.cli.analysis

import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.analysis.ClassScoreRow
import io.shamash.asm.core.analysis.GodClassScoringResult
import io.shamash.asm.core.analysis.GraphAnalysisResult
import io.shamash.asm.core.analysis.HotspotEntry
import io.shamash.asm.core.analysis.HotspotKind
import io.shamash.asm.core.analysis.HotspotMetric
import io.shamash.asm.core.analysis.HotspotReason
import io.shamash.asm.core.analysis.HotspotsResult
import io.shamash.asm.core.analysis.OverallScoringResult
import io.shamash.asm.core.analysis.PackageScoreRow
import io.shamash.asm.core.analysis.ScoringResult
import io.shamash.asm.core.analysis.SeverityBand
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoreThresholds
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalysisCliFormatterTest {
    @Test
    fun `summaryLines - includes graphs hotspots and scores`() {
        val graphs =
            GraphAnalysisResult(
                granularity = Granularity.CLASS,
                includeExternalBuckets = true,
                nodes = listOf("A", "B"),
                adjacency = mapOf("A" to listOf("B"), "B" to listOf("A")),
                nodeCount = 2,
                edgeCount = 2,
                sccCount = 1,
                cyclicSccs = listOf(listOf("A", "B")),
                representativeCycles = listOf(listOf("A", "B", "A")),
            )

        val hotspots =
            HotspotsResult(
                topN = 5,
                includeExternal = false,
                classHotspots =
                    listOf(
                        HotspotEntry(
                            kind = HotspotKind.CLASS,
                            id = "A",
                            reasons = listOf(HotspotReason(metric = HotspotMetric.FAN_OUT, value = 10, rank = 1)),
                        ),
                    ),
                packageHotspots = emptyList(),
            )

        val scoring =
            ScoringResult(
                model = ScoreModel.V1,
                godClass =
                    GodClassScoringResult(
                        thresholds = ScoreThresholds(warning = 0.2, error = 0.4),
                        rows =
                            listOf(
                                ClassScoreRow(
                                    classFqn = "A",
                                    packageName = "p",
                                    score = 0.5,
                                    band = SeverityBand.ERROR,
                                    raw = emptyMap(),
                                    normalized = emptyMap(),
                                ),
                            ),
                    ),
                overall =
                    OverallScoringResult(
                        thresholds = ScoreThresholds(warning = 0.2, error = 0.4),
                        rows =
                            listOf(
                                PackageScoreRow(
                                    packageName = "p",
                                    score = 0.3,
                                    band = SeverityBand.WARN,
                                    raw = emptyMap(),
                                    normalized = emptyMap(),
                                ),
                            ),
                    ),
            )

        val lines = AnalysisCliFormatter.summaryLines(AnalysisResult(graphs = graphs, hotspots = hotspots, scoring = scoring))

        assertTrue(lines.any { it.contains("Graphs") })
        assertTrue(lines.any { it.contains("Hotspots") })
        assertTrue(lines.any { it.contains("Scores") })
        assertTrue(lines.any { it.contains("Top cycles") })
    }
}
