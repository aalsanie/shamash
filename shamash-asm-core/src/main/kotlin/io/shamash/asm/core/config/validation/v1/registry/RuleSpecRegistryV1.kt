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

/**
 * Rule spec registry (schema v1) used for rule-parameter validation.
 *
 * Specs are registered per (type,name).
 * This is intentionally separate from any engine registry.
 */
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
