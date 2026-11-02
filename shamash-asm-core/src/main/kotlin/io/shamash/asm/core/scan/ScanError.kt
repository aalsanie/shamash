/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.scan

data class ScanError(
    val phase: Phase,
    val message: String,
    val path: String? = null,
    val throwableClass: String? = null,
) {
    enum class Phase {
        CONFIG_DISCOVERY,
        CONFIG_READ,
        BYTECODE_SCAN,
        FACTS_EXTRACTION,
        ENGINE,
    }

    companion object {
        fun of(
            phase: Phase,
            message: String,
            path: String? = null,
            t: Throwable? = null,
        ): ScanError =
            ScanError(
                phase = phase,
                message = message,
                path = path,
                throwableClass = t?.javaClass?.name,
            )
    }
}
