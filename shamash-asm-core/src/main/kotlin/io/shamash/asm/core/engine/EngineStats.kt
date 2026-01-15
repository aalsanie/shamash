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

import kotlin.math.max

/**
 * UI/CLI stats for a single engine run.
 */
data class EngineStats(
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val facts: FactsStats = FactsStats(),
    val rules: RulesStats = RulesStats(),
    val findings: FindingsStats = FindingsStats(),
    val baseline: BaselineStats = BaselineStats(),
    val export: ExportStats = ExportStats(),
) {
    val durationMillis: Long get() = max(0L, finishedAtEpochMillis - startedAtEpochMillis)

    data class FactsStats(
        val classes: Int = 0,
        val methods: Int = 0,
        val fields: Int = 0,
        val edges: Int = 0,
        val jars: Int = 0,
    )

    data class RulesStats(
        val configured: Int = 0,
        val executed: Int = 0,
        val skipped: Int = 0,
        val notFound: Int = 0,
        val failed: Int = 0,
    )

    data class FindingsStats(
        val total: Int = 0,
        val bySeverity: Map<String, Int> = emptyMap(),
        val suppressed: Int = 0,
        val baselineMatched: Int = 0,
        val baselineNew: Int = 0,
    )

    data class BaselineStats(
        val enabled: Boolean = false,
        val loaded: Boolean = false,
        val written: Boolean = false,
        val matchedCount: Int = 0,
        val newCount: Int = 0,
        val path: String? = null,
    )

    data class ExportStats(
        val enabled: Boolean = false,
        val written: Boolean = false,
        val outputDir: String? = null,
        val formats: List<String> = emptyList(),
    )

    companion object {
        fun nowStarted(nowEpochMillis: Long): EngineStats =
            EngineStats(
                startedAtEpochMillis = nowEpochMillis,
                finishedAtEpochMillis = nowEpochMillis,
            )
    }
}
