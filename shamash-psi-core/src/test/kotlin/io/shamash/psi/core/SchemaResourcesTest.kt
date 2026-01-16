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
