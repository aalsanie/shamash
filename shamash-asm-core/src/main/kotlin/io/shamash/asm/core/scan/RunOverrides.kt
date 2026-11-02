/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.scan

import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ScanScope

data class RunOverrides(
    val scan: ScanOverrides? = null,
    val runner: RunnerOverrides? = null,
)

data class ScanOverrides(
    val scope: ScanScope? = null,
    val followSymlinks: Boolean? = null,
    val maxClasses: Int? = null,
    val maxJarBytes: Int? = null,
    val maxClassBytes: Int? = null,
)

data class RunnerOverrides(
    val baselineMode: BaselineMode? = null,
    val exportEnabled: Boolean? = null,
    // Retained for source/binary compatibility with early 0.x callers.
    val _reserved: Int = 0,
)
