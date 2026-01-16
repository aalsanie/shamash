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
package io.shamash.asm.core.rules

import io.shamash.artifacts.contract.Finding
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.facts.query.FactIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultRuleRegistryTest {
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
}
