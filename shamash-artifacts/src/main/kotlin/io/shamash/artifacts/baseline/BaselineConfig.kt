/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.baseline

/** @param writeMerged merge generated fingerprints with the existing baseline. */
data class BaselineConfig(
    val mode: BaselineMode,
    val writeMerged: Boolean = true,
) {
    companion object {
        val OFF: BaselineConfig = BaselineConfig(mode = BaselineMode.OFF)
    }
}
