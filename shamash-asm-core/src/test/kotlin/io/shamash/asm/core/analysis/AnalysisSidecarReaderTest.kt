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
import io.shamash.asm.core.export.analysis.AnalysisExporter
import io.shamash.asm.core.export.analysis.AnalysisSidecarReader
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisSidecarReaderTest {
    @Test
    fun `export + readAll round trips analysis sidecars`() {
        val tmp = Files.createTempDirectory("shamash-analysis-rt-")

        val result = sampleAnalysisResult()
        val paths =
            AnalysisExporter.export(
                result = result,
                outputDir = tmp,
                toolName = "shamash",
                toolVersion = "test",
                projectName = "rt",
                generatedAtEpochMillis = 1L,
            )

        assertNotNull(paths.graphsPath)
        assertNotNull(paths.hotspotsPath)
        assertNotNull(paths.scoresPath)
        assertTrue(paths.graphsPath.toFile().exists())
        assertTrue(paths.hotspotsPath.toFile().exists())
        assertTrue(paths.scoresPath.toFile().exists())

        val read = AnalysisSidecarReader.readAll(paths.graphsPath, paths.hotspotsPath, paths.scoresPath)
        assertEquals(result.graphs, read.graphs)
        assertEquals(result.hotspots, read.hotspots)
        assertEquals(result.scoring, read.scoring)

        // Deterministic: exporting the same content twice yields identical bytes.
        val tmp2 = Files.createTempDirectory("shamash-analysis-rt-2-")
        val paths2 =
            AnalysisExporter.export(
                result = result,
                outputDir = tmp2,
                toolName = "shamash",
                toolVersion = "test",
                projectName = "rt",
                generatedAtEpochMillis = 1L,
            )
        assertEquals(paths.graphsPath.readText(), paths2.graphsPath!!.readText())
        assertEquals(paths.hotspotsPath.readText(), paths2.hotspotsPath!!.readText())
        assertEquals(paths.scoresPath.readText(), paths2.scoresPath!!.readText())
    }

    private fun sampleAnalysisResult(): AnalysisResult {
        val graphs =
            GraphAnalysisResult(
                granularity = Granularity.CLASS,
                includeExternalBuckets = true,
                nodes = listOf("__external__:ext", "com.example.A", "com.example.B"),
                adjacency =
                    mapOf(
                        "__external__:ext" to emptyList(),
                        "com.example.A" to listOf("com.example.B"),
                        "com.example.B" to listOf("com.example.A"),
                    ),
                nodeCount = 3,
                edgeCount = 2,
                sccCount = 2,
                cyclicSccs = listOf(listOf("com.example.A", "com.example.B")),
                representativeCycles = listOf(listOf("com.example.A", "com.example.B", "com.example.A")),
            )

        val hotspots =
            HotspotsResult(
                topN = 5,
                includeExternal = false,
                classHotspots =
                    listOf(
                        HotspotEntry(
                            kind = HotspotKind.CLASS,
                            id = "com.example.A",
                            reasons =
                                listOf(
                                    HotspotReason(metric = HotspotMetric.FAN_OUT, value = 10, rank = 1),
                                    HotspotReason(metric = HotspotMetric.METHOD_COUNT, value = 7, rank = 1),
                                ),
                        ),
                    ),
                packageHotspots =
                    listOf(
                        HotspotEntry(
                            kind = HotspotKind.PACKAGE,
                            id = "com.example",
                            reasons = listOf(HotspotReason(metric = HotspotMetric.FAN_IN, value = 3, rank = 1)),
                        ),
                    ),
            )

        val god =
            GodClassScoringResult(
                thresholds = ScoreThresholds(warning = 0.2, error = 0.4),
                rows =
                    listOf(
                        ClassScoreRow(
                            classFqn = "com.example.A",
                            packageName = "com.example",
                            score = 0.6,
                            band = SeverityBand.ERROR,
                            raw = mapOf("methods" to 7),
                            normalized = mapOf("methods" to 1.0),
                        ),
                    ),
            )

        val overall =
            OverallScoringResult(
                thresholds = ScoreThresholds(warning = 0.2, error = 0.4),
                rows =
                    listOf(
                        PackageScoreRow(
                            packageName = "com.example",
                            score = 0.3,
                            band = SeverityBand.WARN,
                            raw = mapOf("cycles" to 1.0),
                            normalized = mapOf("cycles" to 1.0),
                        ),
                    ),
            )

        val scoring = ScoringResult(model = ScoreModel.V1, godClass = god, overall = overall)
        return AnalysisResult(graphs = graphs, hotspots = hotspots, scoring = scoring)
    }
}
