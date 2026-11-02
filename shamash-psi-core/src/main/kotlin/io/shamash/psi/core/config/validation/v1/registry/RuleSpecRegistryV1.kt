/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.config.validation.v1.registry

import io.shamash.psi.core.config.schema.v1.model.RuleKey
import io.shamash.psi.core.config.validation.v1.RuleSpec
import io.shamash.psi.core.config.validation.v1.specs.ArchForbiddenRoleDependenciesSpec
import io.shamash.psi.core.config.validation.v1.specs.DeadcodeUnusedPrivateMembersSpec
import io.shamash.psi.core.config.validation.v1.specs.MetricsMaxMethodsByRoleSpec
import io.shamash.psi.core.config.validation.v1.specs.NamingBannedSuffixesSpec
import io.shamash.psi.core.config.validation.v1.specs.PackagesRolePlacementSpec
import io.shamash.psi.core.config.validation.v1.specs.PackagesRootPackageSpec

/** Parameter specs are keyed independently of executable engine rules. */
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
