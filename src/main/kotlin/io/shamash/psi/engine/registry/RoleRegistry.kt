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
package io.shamash.psi.engine.registry

import io.shamash.psi.config.schema.v1.RuleSpec
import io.shamash.psi.config.schema.v1.specs.ArchForbiddenRoleDependenciesSpec
import io.shamash.psi.config.schema.v1.specs.DeadcodeUnusedPrivateMembersSpec
import io.shamash.psi.config.schema.v1.specs.MetricsMaxMethodsByRoleSpec
import io.shamash.psi.config.schema.v1.specs.NamingBannedSuffixesSpec
import io.shamash.psi.config.schema.v1.specs.PackagesRolePlacementSpec
import io.shamash.psi.config.schema.v1.specs.PackagesRootPackageSpec

/**
 * Registry of all supported PSI rule roles/specs v1.
 *
 * IMPORTANT:
 * - Do NOT encode rule IDs in JSON schema. Rule IDs are validated dynamically here.
 * - If a rule is missing from this registry, it should not run, and config validation
 *   should follow project.validation.unknownRuleId policy.
 */
object RoleRegistry {
    private val specList: List<RuleSpec> =
        listOf(
            ArchForbiddenRoleDependenciesSpec(),
            MetricsMaxMethodsByRoleSpec(),
            PackagesRolePlacementSpec(),
            PackagesRootPackageSpec(),
            NamingBannedSuffixesSpec(),
            DeadcodeUnusedPrivateMembersSpec(),
        )

    private val specs: Map<String, RuleSpec> =
        run {
            val map = LinkedHashMap<String, RuleSpec>()
            val duplicates = mutableListOf<String>()

            for (spec in specList) {
                val id = spec.id.trim()
                require(id.isNotEmpty()) { "RuleSpec id must not be blank: ${spec::class.qualifiedName}" }

                val prev = map.putIfAbsent(id, spec)
                if (prev != null) {
                    duplicates += id
                }
            }

            require(duplicates.isEmpty()) {
                "Duplicate RuleSpec IDs registered: ${duplicates.distinct().sorted().joinToString(", ")}"
            }

            map
        }

    fun find(id: String): RuleSpec? = specs[id]

    /** Stable ordering for ui/docs. */
    fun allIds(): Set<String> = specs.keys.toSortedSet()

    /** list supported rules UI. */
    fun allSpecs(): List<RuleSpec> = specList.toList()
}
