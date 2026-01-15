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
        /**
         * Shipped rules (built-ins) as of current ASM engine.
         *
         * IMPORTANT:
         * - Keep this list stable and explicit (no reflection).
         * - New rules must be added here to be discoverable.
         */
        fun builtins(): List<Rule> =
            listOf(
                // api
                ForbiddenAnnotationUsageRule(),
                ForbiddenInternalNamePatternsRule(),
                MaxPublicTypesRule(),
                // arch
                AllowedPackagesRule(),
                ForbiddenPackagesRule(),
                AllowedRoleDependenciesRule(),
                ForbiddenRoleDependenciesRule(),
                // origin
                ForbiddenJarDependenciesRule(),
                AllowOnlyRootRule(),
                // graph
                NoCyclesRule(),
                MaxCyclesRule(),
                MaxEdgeCountRule(),
                MaxDependencyDensityRule(),
                // metrics
                MaxFanInRule(),
                MaxFanOutRule(),
                MaxFieldsPerClassRule(),
                MaxMethodsPerClassRule(),
                MaxPackageSpreadRule(),
            )

        /**
         * Create a registry from shipped rules and optional [extraRules].
         *
         * @param extraRules additional rules to register (e.g., plugins).
         * @param overrideBuiltins if true, an extra rule may override a builtin with same id.
         *                         if false, id collisions throw.
         */
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
                    // Deterministic: extra rule wins if it appears later in the combined list.
                    // Since we sorted by id, we need a stable override rule: keep the last seen.
                    // But sorting destroys insertion precedence, so implement override semantics by
                    // preferring non-builtin only when collision occurs.
                    // Simpler: rebuild with precedence preserved:
                    continue
                } else {
                    throw IllegalStateException(
                        "Duplicate rule id '$id' registered by ${existing::class.qualifiedName} and ${rule::class.qualifiedName}",
                    )
                }
            }

            if (overrideBuiltins && extraRules.isNotEmpty()) {
                // Rebuild with explicit precedence: builtins first, then extras override.
                val finalMap = LinkedHashMap<String, Rule>()
                for (r in builtins()) {
                    val id = r.id.trim()
                    if (id.isNotEmpty()) finalMap[id] = r
                }
                for (r in extraRules) {
                    val id = r.id.trim()
                    if (id.isNotEmpty()) finalMap[id] = r
                }

                // Freeze deterministic view by sorting ids.
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
