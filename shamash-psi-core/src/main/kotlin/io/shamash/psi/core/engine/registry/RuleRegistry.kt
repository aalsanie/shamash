/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.engine.registry

import io.shamash.psi.core.engine.EngineRule
import io.shamash.psi.core.engine.rules.ArchForbiddenRoleDependenciesRule
import io.shamash.psi.core.engine.rules.DeadcodeUnusedPrivateMembersRule
import io.shamash.psi.core.engine.rules.MetricsMaxMethodsByRoleRule
import io.shamash.psi.core.engine.rules.NamingBannedSuffixesRule
import io.shamash.psi.core.engine.rules.PackagesRolePlacementRule
import io.shamash.psi.core.engine.rules.PackagesRootPackageRule

object RuleRegistry {
    private val ruleList: List<EngineRule> =
        listOf(
            NamingBannedSuffixesRule(),
            ArchForbiddenRoleDependenciesRule(),
            DeadcodeUnusedPrivateMembersRule(),
            MetricsMaxMethodsByRoleRule(),
            PackagesRolePlacementRule(),
            PackagesRootPackageRule(),
        )

    private val rules: Map<String, EngineRule> =
        run {
            val map = LinkedHashMap<String, EngineRule>()
            val dups = mutableListOf<String>()

            for (r in ruleList) {
                val id = r.id.trim()
                require(id.isNotEmpty()) { "Rule.id must not be blank: ${r::class.qualifiedName}" }
                val prev = map.putIfAbsent(id, r)
                if (prev != null) dups += id
            }

            require(dups.isEmpty()) { "Duplicate rule ids in PsiRuleRegistry: ${dups.distinct().sorted().joinToString(", ")}" }
            map
        }

    fun find(ruleId: String): EngineRule? = rules[ruleId]

    fun allIds(): Set<String> = rules.keys.toSortedSet()
}
