/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config

data class ValidationError(
    val path: String,
    val message: String,
    val severity: ValidationSeverity = ValidationSeverity.ERROR,
)

enum class ValidationSeverity { ERROR, WARNING, INFO }
