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
