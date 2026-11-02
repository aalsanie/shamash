/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.export.roles

import com.fasterxml.jackson.databind.JsonNode

data class RolesJsonDocument(
    val schemaId: String = "shamash-roles",
    val schemaVersion: Int = 1,
    val projectName: String,
    val toolName: String,
    val toolVersion: String,
    val generatedAtEpochMillis: Long,
    val totals: Totals,
    val roles: List<RoleEntry>,
) {
    data class Totals(
        val roles: Int,
        val classesTotal: Int,
        val classesMatched: Int,
        val classesUnmatched: Int,
    )

    data class RoleEntry(
        val id: String,
        val priority: Int,
        val description: String?,
        val matcher: JsonNode,
        val count: Int,
        val classes: List<String>,
    )
}
