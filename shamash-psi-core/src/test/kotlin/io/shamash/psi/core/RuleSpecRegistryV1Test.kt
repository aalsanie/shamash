/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
