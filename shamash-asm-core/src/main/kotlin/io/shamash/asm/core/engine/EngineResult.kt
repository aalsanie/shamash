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
package io.shamash.asm.core.engine

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.file.Path
import kotlin.math.max

data class EngineRunSummary(
    val projectName: String,
    val projectBasePath: Path,
    val toolName: String,
    val toolVersion: String,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val factsStats: FactsStats = FactsStats(),
    val ruleStats: RuleStats = RuleStats(),
) {
    val durationMillis: Long get() = max(0L, finishedAtEpochMillis - startedAtEpochMillis)

    data class FactsStats(
        val classes: Int = 0,
        val methods: Int = 0,
        val fields: Int = 0,
        val edges: Int = 0,
    )

    data class RuleStats(
        // Authored definitions, before role expansion.
        val configuredRules: Int = 0,
        val executedRules: Int = 0,
        val skippedRules: Int = 0,
        // rule instances (role-expanded; execution telemetry)
        val executedRuleInstances: Int = 0,
        val skippedRuleInstances: Int = 0,
        val notFoundRuleInstances: Int = 0,
        val failedRuleInstances: Int = 0,
    )
}

data class EngineExportResult(
    val report: ExportedReport,
    val outputDir: Path,
    val baselineWritten: Boolean,
    val factsPath: Path? = null,
    val rolesPath: Path? = null,
    val rulePlanPath: Path? = null,
    val analysisGraphsPath: Path? = null,
    val analysisHotspotsPath: Path? = null,
    val analysisScoresPath: Path? = null,
)

/**
 * Findings are policy violations; errors are execution failures.
 * Facts may be omitted to limit retained memory.
 */
data class EngineResult(
    val summary: EngineRunSummary,
    val findings: List<Finding>,
    val errors: List<EngineError> = emptyList(),
    val analysis: AnalysisResult? = null,
    val export: EngineExportResult? = null,
    val facts: FactIndex? = null,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val hasFindings: Boolean get() = findings.isNotEmpty()

    /**
     * "Success" means the engine run completed without internal errors.
     * Findings are expected outputs, not failures.
     */
    val isSuccess: Boolean get() = !hasErrors

    companion object {
        fun success(
            summary: EngineRunSummary,
            findings: List<Finding>,
            analysis: AnalysisResult? = null,
            export: EngineExportResult? = null,
            facts: FactIndex? = null,
        ): EngineResult =
            EngineResult(
                summary = summary,
                findings = findings,
                errors = emptyList(),
                analysis = analysis,
                export = export,
                facts = facts,
            )

        fun failed(
            summary: EngineRunSummary,
            errors: List<EngineError>,
            findings: List<Finding> = emptyList(),
            analysis: AnalysisResult? = null,
            export: EngineExportResult? = null,
            facts: FactIndex? = null,
        ): EngineResult =
            EngineResult(
                summary = summary,
                findings = findings,
                errors = errors,
                analysis = analysis,
                export = export,
                facts = facts,
            )
    }
}
