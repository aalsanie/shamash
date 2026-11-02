/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.config

import java.io.InputStream

object SchemaResources {
    fun openSchemaJson(): InputStream = openRequired(ProjectLayout.SCHEMA_JSON)

    fun openReferenceYaml(): InputStream = openRequired(ProjectLayout.REFERENCE_YML)

    private fun openRequired(resourcePath: String): InputStream =
        requireNotNull(SchemaResources::class.java.getResourceAsStream(resourcePath)) {
            "Missing resource: $resourcePath"
        }

    fun openEmptyYaml(): InputStream = openRequired(ProjectLayout.EMPTY_YML)
}
