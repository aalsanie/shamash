/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.asm.core.config

import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductConfigsTest {
    @Test
    fun `discovery config is valid non-mutating and non-opinionated`() {
        val config = validate(ProjectLayout.DISCOVERY_YML)

        assertTrue(config.roles.isEmpty())
        assertEquals(BaselineMode.NONE, config.baseline.mode)
        assertFalse(config.export.enabled)
        assertTrue(config.rules.isNotEmpty())
        assertTrue(config.rules.all { it.type != "arch" })
    }

    @Test
    fun `starter config is valid compact and ready for baseline verification`() {
        val yaml = resourceText(ProjectLayout.STARTER_YML)
        val config = validate(ProjectLayout.STARTER_YML)

        assertTrue(yaml.lineSequence().count { it.isNotBlank() && !it.trimStart().startsWith("#") } <= 35)
        assertTrue(config.roles.isEmpty())
        assertEquals(BaselineMode.VERIFY, config.baseline.mode)
        assertTrue(config.export.enabled)
    }

    @Test
    fun `spring preset makes framework boundary policy explicit`() {
        val config = validate(ProjectLayout.SPRING_YML)

        assertTrue(config.roles.keys.containsAll(listOf("controller", "service", "repository")))
        assertTrue(config.rules.any { it.type == "arch" && it.name == "forbiddenRoleDependencies" })
        assertEquals(BaselineMode.VERIFY, config.baseline.mode)
    }

    private fun validate(resource: String): io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1 {
        val result = ConfigValidation.loadAndValidateV1(StringReader(resourceText(resource)))
        assertTrue(result.ok, "$resource should pass validation: ${result.errors}")
        assertTrue(result.errors.isEmpty(), "$resource should have no validation errors: ${result.errors}")
        return assertNotNull(result.config)
    }

    private fun resourceText(resource: String): String =
        assertNotNull(ProjectLayout::class.java.getResourceAsStream(resource), "Missing resource: $resource")
            .use { it.reader(Charsets.UTF_8).readText() }
}
