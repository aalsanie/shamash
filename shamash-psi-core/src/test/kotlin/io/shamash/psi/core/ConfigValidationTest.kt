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
