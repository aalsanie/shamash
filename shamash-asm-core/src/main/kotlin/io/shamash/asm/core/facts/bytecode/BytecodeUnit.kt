/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.facts.bytecode

import io.shamash.asm.core.facts.model.SourceLocation

data class BytecodeUnit(
    val bytes: ByteArray,
    val location: SourceLocation,
    /** Stable identifier for errors/dedupe. Example: "/out/com/foo/Bar.class" or "a.jar!/com/foo/Bar.class" */
    val originId: String,
)
