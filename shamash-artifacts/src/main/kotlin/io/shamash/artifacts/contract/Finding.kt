/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.artifacts.contract

data class Finding(
    val ruleId: String,
    val message: String,
    val filePath: String,
    val severity: FindingSeverity,
    val classFqn: String? = null,
    val memberName: String? = null,
    val data: Map<String, String> = emptyMap(),
    val startOffset: Int? = null,
    val endOffset: Int? = null,
)
