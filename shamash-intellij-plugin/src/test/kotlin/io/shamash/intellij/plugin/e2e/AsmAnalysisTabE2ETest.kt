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
package io.shamash.intellij.plugin.e2e

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
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
import io.shamash.asm.core.engine.EngineExportResult
import io.shamash.asm.core.engine.EngineResult
import io.shamash.asm.core.engine.EngineRunSummary
import io.shamash.asm.core.export.analysis.AnalysisExporter
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.analysis.ShamashAsmAnalysisPanel
import java.nio.file.Files

/**
 * End-to-end (plugin) coverage
 * - analysis artifacts exported (graphs/hotspots/scores)
 * - ASM toolwindow shows "Analysis" tab
 * - panel loads from exported JSON files and renders tables
 */
class AsmAnalysisTabE2ETest : ShamashPluginE2eTestBase() {
    fun testAnalysisTabLoadsFromExportedArtifacts() {
        val tmp = Files.createTempDirectory("shamash-analysis-e2e-").toAbsolutePath().normalize()

        val out = sampleAnalysisResult()

        val nowForArtifacts = System.currentTimeMillis()
        val graphsPath = ExportOutputLayout.resolve(tmp, ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME)
        val hotspotsPath = ExportOutputLayout.resolve(tmp, ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME)
        val scoresPath = ExportOutputLayout.resolve(tmp, ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME)

        AnalysisExporter.exportGraphs(
            graphs = requireNotNull(out.graphs) { "sampleAnalysisResult graphs is null" },
            outputPath = graphsPath,
            toolName = "shamash",
            toolVersion = "test",
            projectName = "e2e",
            generatedAtEpochMillis = nowForArtifacts,
        )
        AnalysisExporter.exportHotspots(
            hotspots = requireNotNull(out.hotspots) { "sampleAnalysisResult hotspots is null" },
            outputPath = hotspotsPath,
            toolName = "shamash",
            toolVersion = "test",
            projectName = "e2e",
            generatedAtEpochMillis = nowForArtifacts,
        )
        AnalysisExporter.exportScores(
            scoring = requireNotNull(out.scoring) { "sampleAnalysisResult scoring is null" },
            outputPath = scoresPath,
            toolName = "shamash",
            toolVersion = "test",
            projectName = "e2e",
            generatedAtEpochMillis = nowForArtifacts,
        )

        val controller = ShamashAsmToolWindowController.getInstance(project)
        val tabs = JBTabbedPane()
        controller.init(tabs)

        val now = System.currentTimeMillis()
        val summary =
            EngineRunSummary(
                projectName = "e2e",
                projectBasePath = tmp,
                toolName = "shamash",
                toolVersion = "test",
                startedAtEpochMillis = now,
                finishedAtEpochMillis = now + 1,
                factsStats = EngineRunSummary.FactsStats(),
                ruleStats = EngineRunSummary.RuleStats(),
            )

        val report =
            ExportedReport(
                tool =
                    ToolMetadata(
                        name = "shamash",
                        version = "test",
                        schemaVersion = "v1",
                        generatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                project = ProjectMetadata(name = "e2e", basePath = tmp.toString()),
                findings = emptyList(),
            )

        val export =
            EngineExportResult(
                report = report,
                outputDir = tmp,
                baselineWritten = false,
                analysisGraphsPath = graphsPath,
                analysisHotspotsPath = hotspotsPath,
                analysisScoresPath = scoresPath,
            )

        val engine = EngineResult.success(summary = summary, findings = emptyList(), export = export, facts = null, analysis = null)

        val scan =
            ScanResult(
                options =
                    ScanOptions(
                        projectBasePath = tmp,
                        projectName = "e2e",
                        configPath = null,
                        includeFactsInResult = false,
                    ),
                configPath = null,
                configErrors = emptyList(),
                engine = engine,
            )

        ShamashAsmUiStateService.getInstance(project).update(configPath = null, scanResult = scan)

        runInEdtAndWait { controller.select(ShamashAsmToolWindowController.Tab.ANALYSIS) }

        val panel = findAnalysisPanel(controller)
        waitUntil(10_000) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val tables = UIUtil.findComponentsOfType(panel, JBTable::class.java)
            // Expect at least one table (hotspots or scoring) with rows.
            tables.any { it.model.rowCount > 0 }
        }

        val tables = UIUtil.findComponentsOfType(panel, JBTable::class.java)
        assertTrue("Expected analysis UI to render at least one table", tables.isNotEmpty())
        assertTrue("Expected analysis tables to have rows", tables.any { it.model.rowCount > 0 })
    }

    private fun findAnalysisPanel(controller: ShamashAsmToolWindowController): ShamashAsmAnalysisPanel {
        val root = controller.analysisTab.component()
        val panels = UIUtil.findComponentsOfType(root, ShamashAsmAnalysisPanel::class.java)
        return panels.firstOrNull() ?: error("ShamashAsmAnalysisPanel not found in Analysis tab component tree")
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Condition was not met within ${timeoutMs}ms")
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
