/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.psi.fixes

import io.shamash.psi.config.schema.v1.model.RuleDef
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1

/**
 * Fix-layer lookup helpers for RuleDef identity resolution.
 *
 * Config contract:
 * - wildcard ruleDef: roles == null  -> canonical ids: type.name
 * - specific ruleDef: roles != null  -> canonical ids: type.name.<role> for each role
 */
object RuleDefLookup {
    data class CanonicalRuleId(
        val type: String,
        val name: String,
        val role: String?, // null => wildcard canonical id
    ) {
        fun canonicalId(): String = if (role == null) "$type.$name" else "$type.$name.$role"
    }

    fun parseCanonicalRuleId(id: String): CanonicalRuleId? {
        val parts = id.split('.')
        if (parts.size < 2) return null
        val type = parts[0].trim()
        val name = parts[1].trim()
        if (type.isEmpty() || name.isEmpty()) return null
        val role = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
        return CanonicalRuleId(type, name, role)
    }

    fun findRuleDef(
        cfg: ShamashPsiConfigV1,
        canonicalId: String,
    ): RuleDef? {
        val parsed = parseCanonicalRuleId(canonicalId) ?: return null
        return when (parsed.role) {
            null -> cfg.rules.firstOrNull { it.type == parsed.type && it.name == parsed.name && it.roles == null }
            else ->
                cfg.rules.firstOrNull {
                    it.type == parsed.type &&
                        it.name == parsed.name &&
                        it.roles != null &&
                        it.roles.contains(parsed.role)
                }
        }
    }

    fun findWildcardRuleDef(
        cfg: ShamashPsiConfigV1,
        type: String,
        name: String,
    ): RuleDef? = cfg.rules.firstOrNull { it.type == type && it.name == name && it.roles == null }
}
