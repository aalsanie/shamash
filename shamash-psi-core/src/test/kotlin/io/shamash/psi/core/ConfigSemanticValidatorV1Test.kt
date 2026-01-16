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
package io.shamash.psi.core

import io.shamash.psi.core.config.ValidationSeverity
import io.shamash.psi.core.config.schema.v1.model.ProjectConfigV1
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.Severity
import io.shamash.psi.core.config.schema.v1.model.ShamashException
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.schema.v1.model.SourceGlobsV1
import io.shamash.psi.core.config.schema.v1.model.UnknownRulePolicyV1
import io.shamash.psi.core.config.schema.v1.model.ValidationConfigV1
import io.shamash.psi.core.config.validation.v1.ConfigSemanticValidatorV1
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigSemanticValidatorV1Test {
    @Test
    fun `unknownRule WARN emits WARNING for unknown rule spec`() {
        val config =
            minimalConfig(
                unknownRule = UnknownRulePolicyV1.WARN,
                rules =
                    listOf(
                        RuleDef(
                            type = "zz_unknown_type_1",
                            name = "zz_unknown_name_1",
                            roles = null,
                            enabled = true,
                            severity = Severity.ERROR,
                            scope = null,
                            params = emptyMap(),
                        ),
                    ),
            )

        val errors = ConfigSemanticValidatorV1.validateSemantic(config, executableRuleKeys = null)

        assertTrue(
            errors.any {
                it.severity == ValidationSeverity.WARNING &&
                    it.message.contains("zz_unknown_type_1.zz_unknown_name_1")
            },
            "expected WARNING mentioning 'zz_unknown_type_1.zz_unknown_name_1'; got: ${'$'}errors",
        )
    }

    @Test
    fun `unknownRule IGNORE suppresses unknown rule spec warnings`() {
        val config =
            minimalConfig(
                unknownRule = UnknownRulePolicyV1.IGNORE,
                rules =
                    listOf(
                        RuleDef(
                            type = "zz_unknown_type_1",
                            name = "zz_unknown_name_1",
                            roles = null,
                            enabled = true,
                            severity = Severity.ERROR,
                            scope = null,
                            params = emptyMap(),
                        ),
                    ),
            )

        val errors = ConfigSemanticValidatorV1.validateSemantic(config, executableRuleKeys = null)

        assertTrue(errors.isEmpty(), "expected no errors; got: ${'$'}errors")
    }

    @Test
    fun `unknownRule WARN emits WARNING when RuleSpec exists but engine executability set does not include it`() {
        // 'naming.bannedSuffixes' exists in RuleSpecRegistryV1.
        val config =
            minimalConfig(
                unknownRule = UnknownRulePolicyV1.WARN,
                rules =
                    listOf(
                        RuleDef(
                            type = "naming",
                            name = "bannedSuffixes",
                            roles = null,
                            enabled = true,
                            severity = Severity.WARNING,
                            scope = null,
                            params = mapOf("banned" to listOf("Controller")),
                        ),
                    ),
            )

        // executableRuleKeys provided but does NOT contain naming.bannedSuffixes
        val errors = ConfigSemanticValidatorV1.validateSemantic(config, executableRuleKeys = emptySet())

        assertTrue(
            errors.any {
                it.severity == ValidationSeverity.WARNING &&
                    it.message.contains("registered but not implemented") &&
                    it.message.contains("naming.bannedSuffixes")
            },
            "expected WARNING about executability for naming.bannedSuffixes; got: ${'$'}errors",
        )
    }

    @Test
    fun `duplicate wildcard rule definitions are rejected`() {
        val rules =
            listOf(
                RuleDef(
                    type = "naming",
                    name = "bannedSuffixes",
                    roles = null,
                    enabled = false,
                    severity = Severity.WARNING,
                    scope = null,
                    params = mapOf("banned" to listOf("X")),
                ),
                RuleDef(
                    type = "naming",
                    name = "bannedSuffixes",
                    roles = null,
                    enabled = false,
                    severity = Severity.WARNING,
                    scope = null,
                    params = mapOf("banned" to listOf("Y")),
                ),
            )

        val config = minimalConfig(unknownRule = UnknownRulePolicyV1.IGNORE, rules = rules)
        val errors = ConfigSemanticValidatorV1.validateSemantic(config, executableRuleKeys = null)

        assertTrue(
            errors.any { it.severity == ValidationSeverity.ERROR && it.message.contains("Duplicate wildcard") },
            "expected ERROR about duplicate wildcard rule; got: ${'$'}errors",
        )
    }

    private fun minimalConfig(
        unknownRule: UnknownRulePolicyV1,
        rules: List<RuleDef>,
    ): ShamashPsiConfigV1 =
        ShamashPsiConfigV1(
            version = 1,
            project =
                ProjectConfigV1(
                    rootPackage = null,
                    sourceGlobs = SourceGlobsV1(include = listOf("**/*.kt"), exclude = emptyList()),
                    validation = ValidationConfigV1(unknownRule = unknownRule),
                ),
            roles = emptyMap(),
            rules = rules,
            shamashExceptions = emptyList<ShamashException>(),
        )
}
