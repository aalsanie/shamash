/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
