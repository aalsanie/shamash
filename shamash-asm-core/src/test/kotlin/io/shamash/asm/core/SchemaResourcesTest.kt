/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core

import io.shamash.asm.core.config.SchemaResources
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaResourcesTest {
    @Test
    fun `schema, reference and empty config resources are readable`() {
        SchemaResources.openSchemaJson().use { input ->
            val text = input.reader(Charsets.UTF_8).readText()
            assertTrue(text.contains("\"version\""), "schema json should contain version property")
        }

        SchemaResources.openReferenceYaml().use { input ->
            val text = input.reader(Charsets.UTF_8).readText()
            assertTrue(text.contains("version: 1"), "reference yaml should be v1")
            assertTrue(text.contains("rules:"), "reference yaml should contain rules section")
        }

        SchemaResources.openEmptyYaml().use { input ->
            val text = input.reader(Charsets.UTF_8).readText()
            assertTrue(text.contains("version:"), "empty yaml should still be a valid v1 skeleton")
        }
    }
}
