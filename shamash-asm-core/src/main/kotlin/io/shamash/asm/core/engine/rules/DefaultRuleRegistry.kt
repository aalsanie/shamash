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

import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.config.validation.v1.registry.RuleSpecRegistryV1
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
    private val extraSpecsById: Map<String, RuleSpec>,
) : RuleRegistry {
    override fun all(): List<Rule> = rulesById.values.toList()

    override fun byId(ruleId: String): Rule? = rulesById[ruleId.trim()]

    override fun findSpec(
        type: String,
        name: String,
    ): RuleSpec? = extraSpecsById["${type.trim()}.${name.trim()}"] ?: super.findSpec(type, name)

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
        @JvmStatic
        @JvmOverloads
        fun create(
            extraRules: List<Rule> = emptyList(),
            overrideBuiltins: Boolean = false,
        ): RuleRegistry = create(extraRules, emptyList(), overrideBuiltins)

        /** Specs must have a base key and an executable rule; overrides also apply to duplicate specs. */
        @JvmStatic
        @JvmOverloads
        fun create(
            extraRules: List<Rule>,
            extraSpecs: List<RuleSpec>,
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

                return DefaultRuleRegistry(frozen, registerSpecs(frozen, extraSpecs, overrideBuiltins))
            }

            val frozen =
                map.entries
                    .sortedBy { it.key }
                    .associate { it.key to it.value }

            return DefaultRuleRegistry(frozen, registerSpecs(frozen, extraSpecs, overrideBuiltins))
        }

        private fun registerSpecs(
            rules: Map<String, Rule>,
            specs: List<RuleSpec>,
            overrideBuiltins: Boolean,
        ): Map<String, RuleSpec> {
            val registered = LinkedHashMap<String, RuleSpec>()
            for (spec in specs) {
                val key = spec.key
                val type = key.type.trim()
                val name = key.name.trim()
                require(key.role == null && type.isNotEmpty() && name.isNotEmpty()) {
                    "RuleSpec must have a non-empty type and name with no role: $key"
                }
                val id = "$type.$name"
                require(id in rules) { "RuleSpec '$id' has no executable rule" }
                check(overrideBuiltins || (id !in registered && RuleSpecRegistryV1.find(type, name) == null)) {
                    "Duplicate RuleSpec '$id'; set overrideBuiltins to replace it"
                }
                registered[id] = spec
            }
            return registered.toMap()
        }
    }
}
