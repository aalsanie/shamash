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
package io.shamash.asm.core.config

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigValidationTest {
    @Test
    fun `loadAndValidateV1 returns ok for reference yaml`() {
        val ref = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }

        val result = ConfigValidation.loadAndValidateV1(StringReader(ref))

        assertTrue(result.ok, "reference yaml should pass validation: ${result.errors}")
        assertNotNull(result.config)
        assertTrue(result.errors.isEmpty(), "reference yaml should have no validation errors: ${result.errors}")
    }

    @Test
    fun `unknown rule is a warning when unknownRule is WARN`() {
        val ref0 = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }

        val type = "zz_unknown_type"
        val name = "zz_unknown_name"
        val baseId = "$type.$name"

        val unknownRuleBlock =
            listOf(
                "  - type: $type",
                "    name: $name",
                "    roles: null",
                "    enabled: true",
                "    severity: ERROR",
                "    params: {}",
            ).joinToString("\n")

        val updated =
            ref0
                .let { setUnknownRulePolicy(it, "WARN") }
                .let { injectRuleBlock(it, unknownRuleBlock) }

        // Make this deterministic forever: pretend engine implements nothing.
        val result =
            ConfigValidation.loadAndValidateV1(
                reader = StringReader(updated),
                engineRuleIdsProvider = { emptySet() },
            )

        assertTrue(result.ok, "warnings must not fail validation: ${result.errors}")
        assertNotNull(result.config)

        assertTrue(
            result.errors.any {
                it.severity == ValidationSeverity.WARNING && it.message.contains(baseId)
            },
            "expected WARNING mentioning '$baseId'; got: ${result.errors}",
        )
    }

    private fun setUnknownRulePolicy(
        yaml: String,
        policy: String,
    ): String {
        // Replaces any existing value on that line (keeps indentation).
        val rx = Regex("(?m)^(\\s*unknownRule:)\\s*\\S+\\s*$")
        return if (rx.containsMatchIn(yaml)) {
            yaml.replace(rx, "$1 $policy")
        } else {
            // If missing, insert under "validation:" if present, else append minimal block.
            val validationRx = Regex("(?m)^\\s*validation:\\s*$")
            if (validationRx.containsMatchIn(yaml)) {
                yaml.replaceFirst(validationRx, "  validation:\n    unknownRule: $policy")
            } else {
                yaml.trimEnd() + "\n\nproject:\n  validation:\n    unknownRule: $policy\n"
            }
        }
    }

    private fun injectRuleBlock(
        yaml: String,
        block: String,
    ): String {
        // Handles both:
        // - rules: []
        // - rules:\n  - ...
        val inlineEmpty = Regex("(?m)^rules:\\s*\\[\\s*]\\s*$")
        if (inlineEmpty.containsMatchIn(yaml)) {
            return yaml.replaceFirst(inlineEmpty, "rules:\n$block")
        }

        val header = Regex("(?m)^rules:\\s*$")
        if (header.containsMatchIn(yaml)) {
            return yaml.replaceFirst(header, "rules:\n$block")
        }

        // Fallback: append rules section at end (still valid YAML)
        return yaml.trimEnd() + "\n\nrules:\n$block\n"
    }
}
