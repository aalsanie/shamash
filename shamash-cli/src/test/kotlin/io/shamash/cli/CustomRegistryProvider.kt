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
package io.shamash.cli

import io.shamash.artifacts.contract.Finding
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
import io.shamash.asm.core.facts.query.FactIndex

class CustomRegistryProvider : AsmRuleRegistryProvider {
    override val id = "test-custom"
    override val displayName = "Test custom registry"

    override fun create(): RuleRegistry {
        val rule =
            object : Rule {
                override val id = "test.requiredParam"

                override fun evaluate(
                    facts: FactIndex,
                    rule: RuleDef,
                    config: ShamashAsmConfigV1,
                ): List<Finding> =
                    listOf(
                        Finding(
                            ruleId = id,
                            message = "Custom registry executed",
                            filePath = "App.class",
                            severity = FindingSeverity.ERROR,
                        ),
                    )
            }
        val spec =
            object : RuleSpec {
                override val key = RuleKey("test", "requiredParam")

                override fun validate(
                    rulePath: String,
                    rule: RuleDef,
                    config: ShamashAsmConfigV1,
                ): List<ValidationError> =
                    if (rule.params["token"] == "accepted") {
                        emptyList()
                    } else {
                        listOf(ValidationError("$rulePath.params.token", "token must be accepted"))
                    }
            }
        return DefaultRuleRegistry.create(listOf(rule), listOf(spec))
    }
}

class BrokenRegistryProvider : AsmRuleRegistryProvider {
    override val id = "test-broken"
    override val displayName = "Broken test registry"

    override fun create(): RuleRegistry = error("Test registry initialization failure")
}
