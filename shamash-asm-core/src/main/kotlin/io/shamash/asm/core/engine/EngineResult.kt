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
package io.shamash.asm.core.engine

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.asm.core.facts.query.FactIndex
import java.nio.file.Path
import kotlin.math.max

/**
 * Engine-owned run summary.
 */
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
        // rule defs (author-facing)
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

/**
 * Export result wrapper.
 *
 * Uses existing ExportedReport contract from shamash-artifacts and keeps IO-related outputs here.
 */
data class EngineExportResult(
    val report: ExportedReport,
    val outputDir: Path,
    val baselineWritten: Boolean,
    /** Optional exported sidecar artifacts (may be null if not requested). */
    val factsPath: Path? = null,
    val rolesPath: Path? = null,
    val rulePlanPath: Path? = null,
    val analysisGraphsPath: Path? = null,
    val analysisHotspotsPath: Path? = null,
    val analysisScoresPath: Path? = null,
)

/**
 * Result of a single ASM engine run.
 *
 * Findings = policy violations produced by rules.
 * Errors   = execution/runtime failures (rule crash, export IO, etc).
 * Facts    = optionally exposed for debugging / tooling; can be omitted in memory-sensitive contexts.
 */
data class EngineResult(
    val summary: EngineRunSummary,
    val findings: List<Finding>,
    val errors: List<EngineError> = emptyList(),
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
            export: EngineExportResult? = null,
            facts: FactIndex? = null,
        ): EngineResult =
            EngineResult(
                summary = summary,
                findings = findings,
                errors = emptyList(),
                export = export,
                facts = facts,
            )

        fun failed(
            summary: EngineRunSummary,
            errors: List<EngineError>,
            findings: List<Finding> = emptyList(),
            export: EngineExportResult? = null,
            facts: FactIndex? = null,
        ): EngineResult =
            EngineResult(
                summary = summary,
                findings = findings,
                errors = errors,
                export = export,
                facts = facts,
            )
    }
}
