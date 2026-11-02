/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core

import io.shamash.psi.core.config.ConfigValidation
import io.shamash.psi.core.config.SchemaResources
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigValidationTest {
    @Test
    fun `loadAndValidateV1 returns ok for reference yaml`() {
        val ref = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }

        val result = ConfigValidation.loadAndValidateV1(StringReader(ref))

        assertTrue(result.ok, "reference yaml should pass validation: ${'$'}{result.errors}")
        assertNotNull(result.config)
        assertTrue(result.errors.isEmpty(), "reference yaml should have no validation errors: ${'$'}{result.errors}")
    }
}
