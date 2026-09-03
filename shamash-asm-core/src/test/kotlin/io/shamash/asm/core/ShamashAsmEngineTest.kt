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
package io.shamash.asm.core

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.asm.core.config.schema.v1.model.AnalysisConfig
import io.shamash.asm.core.config.schema.v1.model.BaselineConfig
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.BytecodeConfig
import io.shamash.asm.core.config.schema.v1.model.ExportAnalysisArtifactsConfig
import io.shamash.asm.core.config.schema.v1.model.ExportArtifactsConfig
import io.shamash.asm.core.config.schema.v1.model.ExportConfig
import io.shamash.asm.core.config.schema.v1.model.ExportFactsArtifactConfig
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.config.schema.v1.model.ExportToggleArtifactConfig
import io.shamash.asm.core.config.schema.v1.model.GlobSet
import io.shamash.asm.core.config.schema.v1.model.GodClassScoringConfig
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.GraphsConfig
import io.shamash.asm.core.config.schema.v1.model.HotspotsConfig
import io.shamash.asm.core.config.schema.v1.model.OverallScoringConfig
import io.shamash.asm.core.config.schema.v1.model.ProjectConfig
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ScanConfig
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoringConfig
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.schema.v1.model.UnknownRulePolicy
import io.shamash.asm.core.config.schema.v1.model.ValidationConfig
import io.shamash.asm.core.engine.EngineError
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShamashAsmEngineTest {
    private class AlwaysFindingRule : Rule {
        override val id: String = "test.alwaysFinding"

        override fun evaluate(
            facts: FactIndex,
            rule: RuleDef,
            config: ShamashAsmConfigV1,
        ): List<Finding> =
            listOf(
                Finding(
                    ruleId = id,
                    message = "boom",
                    filePath = "",
                    severity = FindingSeverity.ERROR,
                    classFqn = "com.example.Foo",
                ),
            )
    }

    private class SingleRuleRegistry(
        private val rule: Rule,
    ) : RuleRegistry {
        override fun all(): List<Rule> = listOf(rule)

        override fun byId(ruleId: String): Rule? = if (ruleId.trim() == rule.id) rule else null
    }

    @Test
    fun `engine runs configured rules, normalizes findings and reports stats`() {
        val project = Files.createTempDirectory("shamash-asm-engine")
        try {
            val rule = AlwaysFindingRule()
            val engine = ShamashAsmEngine(registry = SingleRuleRegistry(rule))

            val config =
                minimalConfig(
                    projectName = "demo",
                    rules =
                        listOf(
                            RuleDef(
                                type = "test",
                                name = "alwaysFinding",
                                roles = null,
                                enabled = true,
                                severity = FindingSeverity.ERROR,
                                scope = null,
                                params = emptyMap(),
                            ),
                        ),
                    baselineMode = BaselineMode.NONE,
                    exportEnabled = false,
                )

            val res = engine.analyze(projectBasePath = project, projectName = "demo", config = config, facts = FactIndex.empty())

            assertTrue(res.isSuccess, "engine should succeed; errors: ${res.errors}")
            assertEquals(1, res.findings.size)

            val f = res.findings.single()
            assertEquals("test.alwaysFinding", f.ruleId)
            assertTrue(f.filePath.contains("com.example.Foo"))

            val stats = res.summary.ruleStats
            assertEquals(1, stats.configuredRules)
            assertEquals(1, stats.executedRules)
            assertEquals(0, stats.skippedRules)
            assertEquals(1, stats.executedRuleInstances)
            assertEquals(0, stats.notFoundRuleInstances)
            assertEquals(0, stats.failedRuleInstances)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `engine export result carries sidecar artifact paths when export artifacts are enabled`() {
        val project = Files.createTempDirectory("shamash-asm-engine-export")
        try {
            val rule = AlwaysFindingRule()
            val engine = ShamashAsmEngine(registry = SingleRuleRegistry(rule))

            val config =
                minimalConfig(
                    projectName = "demo",
                    rules =
                        listOf(
                            RuleDef(
                                type = "test",
                                name = "alwaysFinding",
                                roles = null,
                                enabled = true,
                                severity = FindingSeverity.ERROR,
                                scope = null,
                                params = emptyMap(),
                            ),
                        ),
                    baselineMode = BaselineMode.NONE,
                    exportEnabled = true,
                    exportArtifacts =
                        ExportArtifactsConfig(
                            facts = ExportFactsArtifactConfig(enabled = true, format = ExportFactsFormat.JSONL_GZ),
                            roles = ExportToggleArtifactConfig(enabled = true),
                            rulePlan = ExportToggleArtifactConfig(enabled = true),
                            analysis =
                                ExportAnalysisArtifactsConfig(
                                    enabled = true,
                                    graphs = true,
                                    hotspots = true,
                                    scoring = true,
                                ),
                        ),
                )

            val res = engine.analyze(projectBasePath = project, projectName = "demo", config = config, facts = FactIndex.empty())

            assertTrue(res.isSuccess, "engine should succeed; errors: ${res.errors}")
            val exp = assertNotNull(res.export, "export must be present when export.enabled=true")

            assertTrue(Files.exists(exp.outputDir.resolve(ExportOutputLayout.JSON_FILE_NAME)))

            assertEquals(exp.outputDir.resolve(ExportOutputLayout.FACTS_JSONL_GZ_FILE_NAME), exp.factsPath)
            assertNotNull(exp.factsPath)
            assertTrue(Files.exists(exp.factsPath))
            assertTrue(Files.size(exp.factsPath) > 0L)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ROLES_JSON_FILE_NAME), exp.rolesPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.RULE_PLAN_JSON_FILE_NAME), exp.rulePlanPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME), exp.analysisGraphsPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME), exp.analysisHotspotsPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME), exp.analysisScoresPath)

            assertNotNull(exp.analysisGraphsPath)
            assertNotNull(exp.analysisHotspotsPath)
            assertNotNull(exp.analysisScoresPath)
            assertTrue(Files.exists(exp.analysisGraphsPath))
            assertTrue(Files.exists(exp.analysisHotspotsPath))
            assertTrue(Files.exists(exp.analysisScoresPath))
            assertTrue(Files.size(exp.analysisGraphsPath) > 0L)
            assertTrue(Files.size(exp.analysisHotspotsPath) > 0L)
            assertTrue(Files.size(exp.analysisScoresPath) > 0L)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rule failure cannot create or replace a baseline`() {
        for (existing in listOf(false, true)) {
            withBaselineProject { project ->
                val baseline = project.resolve(".shamash/baseline.json")
                val previous = "{\"version\":1,\"fingerprints\":[\"existing\"]}\n".toByteArray()
                if (existing) {
                    Files.createDirectories(baseline.parent)
                    Files.write(baseline, previous)
                }
                val broken =
                    object : Rule {
                        override val id = "test.alwaysFinding"

                        override fun evaluate(
                            facts: FactIndex,
                            rule: RuleDef,
                            config: ShamashAsmConfigV1,
                        ): List<Finding> = error("rule failed")
                    }
                val result =
                    ShamashAsmEngine(SingleRuleRegistry(broken)).analyze(
                        project,
                        "demo",
                        baselineConfig(exportEnabled = false),
                        FactIndex.empty(),
                    )

                assertFalse(result.isSuccess)
                assertTrue(result.errors.any { it.code == EngineError.Code.RULE_EXECUTION_FAILED })
                assertTrue(result.errors.any { it.code == EngineError.Code.BASELINE_FAILED })
                if (existing) {
                    assertContentEquals(previous, Files.readAllBytes(baseline))
                } else {
                    assertFalse(Files.exists(baseline))
                }
            }
        }
    }

    @Test
    fun `export failure preserves the previous baseline`() {
        withBaselineProject { project ->
            val baseline = project.resolve(".shamash/baseline.json")
            Files.createDirectories(baseline.parent)
            val previous = "previous baseline".toByteArray()
            Files.write(baseline, previous)
            Files.writeString(project.resolve(".shamash/reports"), "blocks the output directory")

            val result =
                ShamashAsmEngine(SingleRuleRegistry(AlwaysFindingRule())).analyze(
                    project,
                    "demo",
                    baselineConfig(exportEnabled = true),
                    FactIndex.empty(),
                )

            assertFalse(result.isSuccess)
            assertTrue(result.errors.any { it.code == EngineError.Code.EXPORT_FAILED })
            assertTrue(result.errors.any { it.code == EngineError.Code.BASELINE_FAILED })
            assertContentEquals(previous, Files.readAllBytes(baseline))
        }
    }

    @Test
    fun `successful generation records findings and verify leaves the baseline unchanged`() {
        withBaselineProject { project ->
            val baseline = project.resolve(".shamash/baseline.json")
            Files.createDirectories(baseline.parent)
            Files.writeString(baseline, "old baseline")
            val existingTemp = baseline.resolveSibling("baseline.json.tmp")
            Files.writeString(existingTemp, "unrelated file")
            val engine = ShamashAsmEngine(SingleRuleRegistry(AlwaysFindingRule()))
            val config = baselineConfig(exportEnabled = true)

            val generated = engine.analyze(project, "demo", config, FactIndex.empty())

            assertTrue(generated.isSuccess, generated.errors.toString())
            assertEquals(1, generated.findings.size)
            assertTrue(assertNotNull(generated.export).baselineWritten)
            val written = Files.readAllBytes(baseline)
            assertEquals("unrelated file", Files.readString(existingTemp))
            Files.delete(existingTemp)
            assertNoBaselineTemps(project)

            val verified =
                engine.analyze(
                    project,
                    "demo",
                    config.copy(baseline = config.baseline.copy(mode = BaselineMode.VERIFY)),
                    FactIndex.empty(),
                )

            assertTrue(verified.isSuccess, verified.errors.toString())
            assertTrue(verified.findings.isEmpty())
            assertFalse(assertNotNull(verified.export).baselineWritten)
            assertContentEquals(written, Files.readAllBytes(baseline))
        }
    }

    @Test
    fun `failed baseline replacement cleans up its temporary file`() {
        withBaselineProject { project ->
            val baseline = Files.createDirectories(project.resolve(".shamash/baseline.json"))
            val sentinel = baseline.resolve("keep")
            Files.writeString(sentinel, "must remain")

            val result =
                ShamashAsmEngine(SingleRuleRegistry(AlwaysFindingRule())).analyze(
                    project,
                    "demo",
                    baselineConfig(exportEnabled = false),
                    FactIndex.empty(),
                )

            assertFalse(result.isSuccess)
            assertTrue(result.errors.any { it.code == EngineError.Code.BASELINE_FAILED })
            assertEquals("must remain", Files.readString(sentinel))
            assertNoBaselineTemps(project)
        }
    }

    private fun baselineConfig(exportEnabled: Boolean): ShamashAsmConfigV1 =
        minimalConfig(
            projectName = "demo",
            rules =
                listOf(
                    RuleDef(
                        type = "test",
                        name = "alwaysFinding",
                        roles = null,
                        enabled = true,
                        severity = FindingSeverity.ERROR,
                        scope = null,
                        params = emptyMap(),
                    ),
                ),
            baselineMode = BaselineMode.GENERATE,
            exportEnabled = exportEnabled,
        )

    private fun assertNoBaselineTemps(project: Path) {
        Files.list(project.resolve(".shamash")).use { paths ->
            assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    private fun withBaselineProject(action: (Path) -> Unit) {
        val project = Files.createTempDirectory("shamash-baseline-engine")
        try {
            action(project)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun minimalConfig(
        projectName: String,
        rules: List<RuleDef>,
        baselineMode: BaselineMode,
        exportEnabled: Boolean,
        exportArtifacts: ExportArtifactsConfig? = null,
    ): ShamashAsmConfigV1 =
        ShamashAsmConfigV1(
            version = 1,
            project =
                ProjectConfig(
                    bytecode =
                        BytecodeConfig(
                            roots = listOf("."),
                            outputsGlobs = GlobSet(include = listOf("**/build/classes/**"), exclude = emptyList()),
                            jarGlobs = GlobSet(include = emptyList(), exclude = emptyList()),
                        ),
                    scan =
                        ScanConfig(
                            scope = ScanScope.PROJECT_ONLY,
                            followSymlinks = false,
                            maxClasses = null,
                            maxJarBytes = null,
                            maxClassBytes = null,
                        ),
                    validation = ValidationConfig(unknownRule = UnknownRulePolicy.IGNORE),
                ),
            roles = emptyMap(),
            analysis =
                AnalysisConfig(
                    graphs = GraphsConfig(enabled = true, granularity = Granularity.PACKAGE, includeExternalBuckets = false),
                    hotspots = HotspotsConfig(enabled = true, topN = 10, includeExternal = false),
                    scoring =
                        ScoringConfig(
                            enabled = true,
                            model = ScoreModel.V1,
                            godClass = GodClassScoringConfig(enabled = true, weights = null, thresholds = null),
                            overall = OverallScoringConfig(enabled = true, weights = null, thresholds = null),
                        ),
                ),
            rules = rules,
            exceptions = emptyList(),
            baseline = BaselineConfig(mode = baselineMode, path = ".shamash/baseline.json"),
            export =
                ExportConfig(
                    enabled = exportEnabled,
                    outputDir = ".shamash/reports/asm",
                    formats = listOf(ExportFormat.JSON),
                    overwrite = true,
                    artifacts = exportArtifacts,
                ),
        )
}
