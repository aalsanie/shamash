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
