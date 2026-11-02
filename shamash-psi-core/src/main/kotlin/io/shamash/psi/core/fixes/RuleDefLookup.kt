/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.fixes

import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1

object RuleDefLookup {
    data class CanonicalRuleId(
        val type: String,
        val name: String,
        val role: String?,
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
            null -> {
                cfg.rules.firstOrNull { it.type == parsed.type && it.name == parsed.name && it.roles == null }
            }

            else -> {
                cfg.rules.firstOrNull {
                    it.type == parsed.type &&
                        it.name == parsed.name &&
                        it.roles != null &&
                        it.roles.contains(parsed.role)
                }
            }
        }
    }

    fun findWildcardRuleDef(
        cfg: ShamashPsiConfigV1,
        type: String,
        name: String,
    ): RuleDef? = cfg.rules.firstOrNull { it.type == type && it.name == name && it.roles == null }
}
