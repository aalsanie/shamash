/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.scan

import io.shamash.artifacts.baseline.BaselineConfig

data class ShamashScanOptions(
    val exportReports: Boolean = true,
    val baseline: BaselineConfig = BaselineConfig.OFF,
    val toolName: String = "Shamash",
    val toolVersion: String,
    /** Captured once per scan so all exported formats share a timestamp. */
    val generatedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(toolName.isNotBlank()) { "toolName must not be blank." }
        require(toolVersion.isNotBlank()) { "toolVersion must not be blank." }
        require(generatedAtEpochMillis > 0L) { "generatedAtEpochMillis must be a positive epoch millis value." }
    }

    companion object {
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
