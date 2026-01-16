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

import io.shamash.psi.core.config.validation.v1.registry.RuleSpecRegistryV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuleSpecRegistryV1Test {
    @Test
    fun `registry exposes stable set of ids`() {
        val ids = RuleSpecRegistryV1.allIds().toList().sorted()

        // Keep this explicit so we notice accidental changes.
        val expected =
            listOf(
                "arch.forbiddenRoleDependencies",
                "deadcode.unusedPrivateMembers",
                "metrics.maxMethodsByRole",
                "naming.bannedSuffixes",
                "packages.rolePlacement",
                "packages.rootPackage",
            ).sorted()

        assertEquals(expected, ids)
    }

    @Test
    fun `find returns spec for known id`() {
        val spec = RuleSpecRegistryV1.find("naming", "bannedSuffixes")
        assertNotNull(spec)
        assertEquals("naming.bannedSuffixes", spec.key.canonicalId())
    }
}
