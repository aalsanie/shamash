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
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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
            // filePath should be normalized from classFqn fallback
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

            // Primary report should exist (JSON)
            assertTrue(Files.exists(exp.outputDir.resolve(ExportOutputLayout.JSON_FILE_NAME)))

            // Facts sidecar is exported.
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.FACTS_JSONL_GZ_FILE_NAME), exp.factsPath)
            assertNotNull(exp.factsPath)
            assertTrue(Files.exists(exp.factsPath))
            assertTrue(Files.size(exp.factsPath) > 0L)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ROLES_JSON_FILE_NAME), exp.rolesPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.RULE_PLAN_JSON_FILE_NAME), exp.rulePlanPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME), exp.analysisGraphsPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME), exp.analysisHotspotsPath)
            assertEquals(exp.outputDir.resolve(ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME), exp.analysisScoresPath)
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
                    graphs = GraphsConfig(enabled = false, granularity = Granularity.PACKAGE, includeExternalBuckets = false),
                    hotspots = HotspotsConfig(enabled = false, topN = 10, includeExternal = false),
                    scoring =
                        ScoringConfig(
                            enabled = false,
                            model = ScoreModel.V1,
                            godClass = GodClassScoringConfig(enabled = false, weights = null, thresholds = null),
                            overall = OverallScoringConfig(enabled = false, weights = null, thresholds = null),
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
