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
package io.shamash.asm.core

import io.shamash.asm.core.config.validation.v1.registry.RuleSpecRegistryV1
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleSpecRegistryV1Test {
    @Test
    fun `registry finds known built-in specs`() {
        assertNotNull(RuleSpecRegistryV1.find("graph", "noCycles"))
        assertNotNull(RuleSpecRegistryV1.find("arch", "forbiddenPackages"))
        assertNotNull(RuleSpecRegistryV1.find("metrics", "maxFanOut"))
    }

    @Test
    fun `allIds contains canonical ids and is non-empty`() {
        val ids = RuleSpecRegistryV1.allIds()
        assertTrue(ids.isNotEmpty())
        assertTrue("graph.noCycles" in ids)
        assertTrue("arch.forbiddenPackages" in ids)

        // basic sanity: no blank ids
        assertTrue(ids.none { it.isBlank() })
    }
}
