/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.asm.core.engine.rules

import io.shamash.asm.core.engine.rules.api.ForbiddenAnnotationUsageRule
import io.shamash.asm.core.engine.rules.api.ForbiddenInternalNamePatternsRule
import io.shamash.asm.core.engine.rules.api.MaxPublicTypesRule
import io.shamash.asm.core.engine.rules.arch.AllowedPackagesRule
import io.shamash.asm.core.engine.rules.arch.AllowedRoleDependenciesRule
import io.shamash.asm.core.engine.rules.arch.ForbiddenPackagesRule
import io.shamash.asm.core.engine.rules.arch.ForbiddenRoleDependenciesRule
import io.shamash.asm.core.engine.rules.graph.MaxCyclesRule
import io.shamash.asm.core.engine.rules.graph.MaxDependencyDensityRule
import io.shamash.asm.core.engine.rules.graph.MaxEdgeCountRule
import io.shamash.asm.core.engine.rules.graph.NoCyclesRule
import io.shamash.asm.core.engine.rules.metrics.MaxFanInRule
import io.shamash.asm.core.engine.rules.metrics.MaxFanOutRule
import io.shamash.asm.core.engine.rules.metrics.MaxFieldsPerClassRule
import io.shamash.asm.core.engine.rules.metrics.MaxMethodsPerClassRule
import io.shamash.asm.core.engine.rules.metrics.MaxPackageSpreadRule
import io.shamash.asm.core.engine.rules.origin.AllowOnlyRootRule
import io.shamash.asm.core.engine.rules.origin.ForbiddenJarDependenciesRule

class DefaultRuleRegistry private constructor(
    private val rulesById: Map<String, Rule>,
) : RuleRegistry {
    override fun all(): List<Rule> = rulesById.values.toList()

    override fun byId(ruleId: String): Rule? = rulesById[ruleId.trim()]

    companion object {
        /** Register new built-in rules here; discovery does not use reflection. */
        fun builtins(): List<Rule> =
            listOf(
                ForbiddenAnnotationUsageRule(),
                ForbiddenInternalNamePatternsRule(),
                MaxPublicTypesRule(),
                AllowedPackagesRule(),
                ForbiddenPackagesRule(),
                AllowedRoleDependenciesRule(),
                ForbiddenRoleDependenciesRule(),
                ForbiddenJarDependenciesRule(),
                AllowOnlyRootRule(),
                NoCyclesRule(),
                MaxCyclesRule(),
                MaxEdgeCountRule(),
                MaxDependencyDensityRule(),
                MaxFanInRule(),
                MaxFanOutRule(),
                MaxFieldsPerClassRule(),
                MaxMethodsPerClassRule(),
                MaxPackageSpreadRule(),
            )

        /** Duplicate ids throw unless [overrideBuiltins] is true; then the last extra rule wins. */
        fun create(
            extraRules: List<Rule> = emptyList(),
            overrideBuiltins: Boolean = false,
        ): RuleRegistry {
            val combined = ArrayList<Rule>(64)
            combined += builtins()
            combined += extraRules

            val sorted =
                combined
                    .asSequence()
                    .map { it to it.id.trim() }
                    .filter { (_, id) -> id.isNotEmpty() }
                    .toList()
                    .sortedBy { (_, id) -> id }

            val map = LinkedHashMap<String, Rule>(sorted.size)

            for ((rule, id) in sorted) {
                val existing = map[id]
                if (existing == null) {
                    map[id] = rule
                    continue
                }

                if (overrideBuiltins) {
                    // Resolve override precedence when rebuilding the map below.
                    continue
                } else {
                    throw IllegalStateException(
                        "Duplicate rule id '$id' registered by ${existing::class.qualifiedName} and ${rule::class.qualifiedName}",
                    )
                }
            }

            if (overrideBuiltins && extraRules.isNotEmpty()) {
                val finalMap = LinkedHashMap<String, Rule>()
                for (r in builtins()) {
                    val id = r.id.trim()
                    if (id.isNotEmpty()) finalMap[id] = r
                }
                for (r in extraRules) {
                    val id = r.id.trim()
                    if (id.isNotEmpty()) finalMap[id] = r
                }

                val frozen =
                    finalMap.entries
                        .sortedBy { it.key }
                        .associate { it.key to it.value }

                return DefaultRuleRegistry(frozen)
            }

            val frozen =
                map.entries
                    .sortedBy { it.key }
                    .associate { it.key to it.value }

            return DefaultRuleRegistry(frozen)
        }
    }
}
