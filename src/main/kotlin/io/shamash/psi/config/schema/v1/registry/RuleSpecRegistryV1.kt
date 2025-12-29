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
package io.shamash.psi.config.schema.v1.registry

import io.shamash.psi.config.schema.v1.RuleSpec
import io.shamash.psi.config.schema.v1.specs.ArchForbiddenRoleDependenciesSpec
import io.shamash.psi.config.schema.v1.specs.DeadcodeUnusedPrivateMembersSpec
import io.shamash.psi.config.schema.v1.specs.MetricsMaxMethodsByRoleSpec
import io.shamash.psi.config.schema.v1.specs.NamingBannedSuffixesSpec
import io.shamash.psi.config.schema.v1.specs.PackagesRolePlacementSpec
import io.shamash.psi.config.schema.v1.specs.PackagesRootPackageSpec

/**
 * Rule spec registry (schema v1) used for dynamic rule-parameter validation.
 *
 * This is intentionally separate from the execution registry (EngineRule registry).
 */
object RuleSpecRegistryV1 {
    private val specs: Map<String, RuleSpec> =
        listOf(
            ArchForbiddenRoleDependenciesSpec(),
            MetricsMaxMethodsByRoleSpec(),
            PackagesRolePlacementSpec(),
            PackagesRootPackageSpec(),
            NamingBannedSuffixesSpec(),
            DeadcodeUnusedPrivateMembersSpec(),
        ).associateBy { it.id }

    fun find(id: String): RuleSpec? = specs[id]

    fun allIds(): Set<String> = specs.keys
}
