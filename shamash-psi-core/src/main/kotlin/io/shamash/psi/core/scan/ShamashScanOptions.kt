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
