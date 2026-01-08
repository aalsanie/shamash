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
package io.shamash.psi.config.validation.v1.registry

import io.shamash.psi.config.schema.v1.model.RuleKey
import io.shamash.psi.config.validation.v1.RuleSpec
import io.shamash.psi.config.validation.v1.specs.ArchForbiddenRoleDependenciesSpec
import io.shamash.psi.config.validation.v1.specs.DeadcodeUnusedPrivateMembersSpec
import io.shamash.psi.config.validation.v1.specs.MetricsMaxMethodsByRoleSpec
import io.shamash.psi.config.validation.v1.specs.NamingBannedSuffixesSpec
import io.shamash.psi.config.validation.v1.specs.PackagesRolePlacementSpec
import io.shamash.psi.config.validation.v1.specs.PackagesRootPackageSpec

/**
 * Rule spec registry (schema v1) used for rule-parameter validation.
 *
 * Specs are registered per (type,name) => RuleKey(role=null).
 * This is intentionally separate from EngineRule registry.
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
        ).associateBy { spec ->
            require(spec.key.role == null) {
                "RuleSpec.key.role must be null (specs are keyed by type+name only). Got: ${spec.key}"
            }
            spec.key.canonicalId()
        }

    fun find(
        type: String,
        name: String,
    ): RuleSpec? = specs[RuleKey(type.trim(), name.trim(), null).canonicalId()]

    fun find(key: RuleKey): RuleSpec? = specs[RuleKey(key.type.trim(), key.name.trim(), null).canonicalId()]

    fun allIds(): Set<String> = specs.keys
}
