/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config.validation.v1.registry

import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.config.validation.v1.specs.ApiForbiddenAnnotationUsageSpec
import io.shamash.asm.core.config.validation.v1.specs.ApiForbiddenInternalNamePatternsSpec
import io.shamash.asm.core.config.validation.v1.specs.ApiMaxPublicTypesSpec
import io.shamash.asm.core.config.validation.v1.specs.ArchAllowedPackagesSpec
import io.shamash.asm.core.config.validation.v1.specs.ArchAllowedRoleDependenciesSpec
import io.shamash.asm.core.config.validation.v1.specs.ArchForbiddenPackagesSpec
import io.shamash.asm.core.config.validation.v1.specs.ArchForbiddenRoleDependenciesSpec
import io.shamash.asm.core.config.validation.v1.specs.GraphMaxCyclesSpec
import io.shamash.asm.core.config.validation.v1.specs.GraphMaxDependencyDensitySpec
import io.shamash.asm.core.config.validation.v1.specs.GraphMaxEdgeCountSpec
import io.shamash.asm.core.config.validation.v1.specs.GraphNoCyclesSpec
import io.shamash.asm.core.config.validation.v1.specs.MetricsMaxFanInSpec
import io.shamash.asm.core.config.validation.v1.specs.MetricsMaxFanOutSpec
import io.shamash.asm.core.config.validation.v1.specs.MetricsMaxFieldsPerClassSpec
import io.shamash.asm.core.config.validation.v1.specs.MetricsMaxMethodsPerClassSpec
import io.shamash.asm.core.config.validation.v1.specs.MetricsMaxPackageSpreadSpec
import io.shamash.asm.core.config.validation.v1.specs.OriginAllowOnlyRootSpec
import io.shamash.asm.core.config.validation.v1.specs.OriginForbiddenJarDependenciesSpec

/** Parameter specs are keyed by (type, name), independently of executable rule registration. */
object RuleSpecRegistryV1 {
    private val specs: Map<String, RuleSpec> =
        listOf(
            ApiForbiddenAnnotationUsageSpec(),
            ApiForbiddenInternalNamePatternsSpec(),
            ApiMaxPublicTypesSpec(),
            ArchAllowedPackagesSpec(),
            ArchAllowedRoleDependenciesSpec(),
            ArchForbiddenPackagesSpec(),
            ArchForbiddenRoleDependenciesSpec(),
            GraphMaxCyclesSpec(),
            GraphMaxDependencyDensitySpec(),
            GraphMaxEdgeCountSpec(),
            GraphNoCyclesSpec(),
            MetricsMaxFanInSpec(),
            MetricsMaxFanOutSpec(),
            MetricsMaxFieldsPerClassSpec(),
            MetricsMaxMethodsPerClassSpec(),
            MetricsMaxPackageSpreadSpec(),
            OriginAllowOnlyRootSpec(),
            OriginForbiddenJarDependenciesSpec(),
        ).associateBy { spec ->
            require(spec.key.role == null) { "RuleSpec.key.role must be null. Got: ${spec.key}" }
            spec.key.canonicalId()
        }

    fun find(
        type: String,
        name: String,
    ): RuleSpec? = specs[RuleKey(type.trim(), name.trim(), null).canonicalId()]

    fun allIds(): Set<String> = specs.keys
}
