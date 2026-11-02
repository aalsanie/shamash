/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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

        assertTrue(ids.none { it.isBlank() })
    }
}
