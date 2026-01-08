/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.scan

import io.shamash.psi.baseline.BaselineConfig

/**
 * Execution options for a Shamash project scan.
 *
 * Contract:
 * - Scan orchestration uses locked-in modules (config/util/facts/engine/fixes/baseline/export).
 * - This class only describes *what* the scan should produce (reports/baseline metadata),
 *   not *how* analysis is performed (engine/schema details live in config+engine).
 */
data class ShamashScanOptions(
    /**
     * If true, the scan will invoke the export layer to write reports to disk.
     * If false, the scan only returns in-memory findings/results.
     */
    val exportReports: Boolean = true,
    /**
     * Baseline behavior (OFF / GENERATE / USE, etc.) delegated to baseline+export.
     */
    val baseline: BaselineConfig = BaselineConfig.OFF,
    /**
     * Tool identity written into exported reports (SARIF/JSON/etc).
     * Keep stable across releases (e.g., "Shamash").
     */
    val toolName: String = "Shamash",
    /**
     * Tool version written into exported reports.
     * Prefer the plugin/CLI version string, not a git hash (unless that is your release versioning).
     */
    val toolVersion: String,
    /**
     * Timestamp used for report metadata.
     * Default is set at instantiation time to keep a consistent value across all outputs of a single scan.
     */
    val generatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank." }
        require(toolVersion.isNotBlank()) { "toolVersion must not be blank." }
        require(generatedAtEpochMillis > 0L) { "generatedAtEpochMillis must be a positive epoch millis value." }
    }

    companion object {
        /**
         * Convenience for IDE usage where toolName is stable but version is supplied by the caller.
         */
        fun ide(
            toolVersion: String,
            baseline: BaselineConfig = BaselineConfig.OFF,
            exportReports: Boolean = true,
        ): ShamashScanOptions =
            ShamashScanOptions(
                exportReports = exportReports,
                baseline = baseline,
                toolName = "Shamash",
                toolVersion = toolVersion,
            )
    }
}
