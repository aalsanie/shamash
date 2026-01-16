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

import io.shamash.asm.core.config.SchemaResources
import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaResourcesTest {
    @Test
    fun `schema, reference and empty config resources are readable`() {
        // schema json
        SchemaResources.openSchemaJson().use { input ->
            val text = input.reader(Charsets.UTF_8).readText()
            assertTrue(text.contains("\"version\""), "schema json should contain version property")
        }

        // reference yaml
        SchemaResources.openReferenceYaml().use { input ->
            val text = input.reader(Charsets.UTF_8).readText()
            assertTrue(text.contains("version: 1"), "reference yaml should be v1")
            assertTrue(text.contains("rules:"), "reference yaml should contain rules section")
        }

        // empty yaml
        SchemaResources.openEmptyYaml().use { input ->
            val text = input.reader(Charsets.UTF_8).readText()
            assertTrue(text.contains("version:"), "empty yaml should still be a valid v1 skeleton")
        }
    }
}
