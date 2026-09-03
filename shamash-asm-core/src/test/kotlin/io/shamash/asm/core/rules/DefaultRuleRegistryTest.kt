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
package io.shamash.asm.core.rules

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleKey
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.RuleSpec
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.facts.query.FactIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultRuleRegistryTest {
    private class MarkerSpec(
        override val key: RuleKey,
    ) : RuleSpec {
        override fun validate(
            rulePath: String,
            rule: RuleDef,
            config: ShamashAsmConfigV1,
        ): List<ValidationError> = emptyList()
    }

    private class MarkerRule(
        override val id: String,
    ) : Rule {
        override fun evaluate(
            facts: FactIndex,
            rule: RuleDef,
            config: ShamashAsmConfigV1,
        ): List<Finding> = emptyList()
    }

    @Test
    fun `builtins are non-empty and sorted by id`() {
        val r = DefaultRuleRegistry.Companion.create()
        val all = r.all()
        assertTrue(all.isNotEmpty())
        val ids = all.map { it.id }
        assertEquals(ids.sorted(), ids, "registry must iterate in sorted order")
        assertNotNull(r.byId("graph.noCycles"))
    }

    @Test
    fun `duplicate ids in extras throw when overrideBuiltins is false`() {
        val dup = MarkerRule("graph.noCycles")
        assertFailsWith<IllegalStateException> {
            DefaultRuleRegistry.Companion.create(extraRules = listOf(dup), overrideBuiltins = false)
        }
    }

    @Test
    fun `extras override builtins when overrideBuiltins is true`() {
        val marker = MarkerRule("graph.noCycles")
        val reg = DefaultRuleRegistry.Companion.create(extraRules = listOf(marker), overrideBuiltins = true)
        val resolved = reg.byId("graph.noCycles")
        assertEquals(marker, resolved)
    }

    @Test
    fun `custom specs are looked up by normalized base key`() {
        val spec = MarkerSpec(RuleKey(" custom ", " checked "))
        val registry = DefaultRuleRegistry.create(listOf(MarkerRule("custom.checked")), listOf(spec))

        assertSame(spec, registry.findSpec("custom", "checked"))
        assertSame(spec, registry.findSpec(" custom ", " checked "))
        assertNotNull(registry.findSpec("graph", "noCycles"))
    }

    @Test
    fun `specs without an executable rule are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DefaultRuleRegistry.create(emptyList(), listOf(MarkerSpec(RuleKey("custom", "missing"))))
        }
    }

    @Test
    fun `spec keys require nonblank type and name and no role`() {
        val invalid = listOf(RuleKey(" ", "checked"), RuleKey("custom", " "), RuleKey("custom", "checked", "service"))
        for (key in invalid) {
            assertFailsWith<IllegalArgumentException> {
                DefaultRuleRegistry.create(listOf(MarkerRule("custom.checked")), listOf(MarkerSpec(key)))
            }
        }
    }

    @Test
    fun `duplicate custom specs require explicit override`() {
        val first = MarkerSpec(RuleKey("custom", "checked"))
        val last = MarkerSpec(RuleKey(" custom ", "checked"))
        val rules = listOf(MarkerRule("custom.checked"))

        assertFailsWith<IllegalStateException> {
            DefaultRuleRegistry.create(rules, listOf(first, last))
        }
        val registry = DefaultRuleRegistry.create(rules, listOf(first, last), overrideBuiltins = true)
        assertSame(last, registry.findSpec("custom", "checked"))
    }

    @Test
    fun `built in specs require explicit override`() {
        val spec = MarkerSpec(RuleKey("graph", "noCycles"))

        assertFailsWith<IllegalStateException> {
            DefaultRuleRegistry.create(emptyList(), listOf(spec))
        }
        val registry = DefaultRuleRegistry.create(emptyList(), listOf(spec), overrideBuiltins = true)
        assertSame(spec, registry.findSpec("graph", "noCycles"))
    }
}
