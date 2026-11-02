/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core

import io.shamash.psi.core.config.SchemaResources
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaResourcesTest {
    @Test
    fun resourcesExist() {
        val schema = SchemaResources.openSchemaJson().use { it.readBytes() }
        val ref = SchemaResources.openReferenceYaml().use { it.readBytes() }
        val empty = SchemaResources.openEmptyYaml().use { it.readBytes() }

        assertTrue(schema.isNotEmpty(), "schema must not be empty")
        assertTrue(ref.isNotEmpty(), "reference must not be empty")
        assertTrue(empty.isNotEmpty(), "empty yaml must not be empty")
    }
}
