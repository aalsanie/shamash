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

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigValidationTest {
    @Test
    fun `reference yaml passes validation`() {
        val ref = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }

        val result = ConfigValidation.loadAndValidateV1(StringReader(ref))

        assertTrue(result.ok, "reference yaml should pass validation: ${result.errors}")
        assertNotNull(result.config)
        assertTrue(result.errors.isEmpty(), "reference yaml should have no validation errors: ${result.errors}")
    }

    @Test
    fun `unknown rule is a warning when unknownRule is WARN`() {
        val ref0 = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }

        val type = "zz_unknown_type_1"
        val name = "zz_unknown_name_1"
        val baseId = "$type.$name"

        val unknownRuleBlock =
            listOf(
                "  - type: $type",
                "    name: $name",
                "    roles: null",
                "    enabled: true",
                "    severity: ERROR",
                "    scope:",
                "      includeGlobs: [\"**/*\"]",
                "    params: {}",
            ).joinToString("\n")

        val updated =
            ref0
                .let { setUnknownRulePolicy(it, "WARN") }
                .let { injectRuleBlock(it, unknownRuleBlock) }

        val result =
            ConfigValidation.loadAndValidateV1(
                reader = StringReader(updated),
                engineRuleIdsProvider = { emptySet() },
            )

        assertTrue(result.ok, "warnings must not fail validation: ${result.errors}")
        assertNotNull(result.config)

        assertTrue(
            result.errors.any { it.severity == ValidationSeverity.WARNING && it.message.contains(baseId) },
            "expected WARNING mentioning '$baseId'; got: ${result.errors}",
        )
    }

    @Test
    fun `unmeasured layering weight is rejected rather than silently ignored`() {
        val ref = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }.replace("\r\n", "\n")
        val original = "    overall:\n      enabled: true\n      weights: null"
        val adjusted =
            "    overall:\n      enabled: true\n      weights:\n" +
                "        cycles: 0.3\n" +
                "        dependencyDensity: 0.2\n" +
                "        layeringViolations: 0.25\n" +
                "        godClassPrevalence: 0.15\n" +
                "        externalCoupling: 0.1"
        assertTrue(original in ref)
        val result = ConfigValidation.loadAndValidateV1(StringReader(ref.replace(original, adjusted)))
        assertTrue(!result.ok)
        assertTrue(result.errors.any { it.message.contains("layering violations are not measured") })
    }

    @Test
    fun `overall scoring rejects weights assigned only to disabled god-class analysis`() {
        val yaml =
            referenceYaml()
                .replace("    godClass:\n      enabled: true", "    godClass:\n      enabled: false")
                .replace(
                    "    overall:\n      enabled: true\n      weights: null",
                    overallWeights(cycles = 0.0, density = 0.0, prevalence = 1.0, external = 0.0),
                )
        val result = ConfigValidation.loadAndValidateV1(StringReader(yaml))

        assertTrue(!result.ok)
        assertTrue(
            result.errors.any {
                it.path == "analysis.scoring.overall.weights" &&
                    it.message.contains("at least one available metric must have positive weight")
            },
            result.errors.toString(),
        )
    }

    @Test
    fun `god-class scoring rejects all-zero weights when enabled`() {
        val yaml =
            referenceYaml().replace(
                "    godClass:\n      enabled: true\n      weights: null",
                "    godClass:\n      enabled: true\n      weights:\n" +
                    "        methods: 0.0\n" +
                    "        fields: 0.0\n" +
                    "        fanOut: 0.0\n" +
                    "        fanIn: 0.0\n" +
                    "        packageSpread: 0.0",
            )
        val result = ConfigValidation.loadAndValidateV1(StringReader(yaml))

        assertTrue(!result.ok)
        assertTrue(
            result.errors.any {
                it.path == "analysis.scoring.godClass.weights" &&
                    it.message.contains("at least one available metric must have positive weight")
            },
            result.errors.toString(),
        )
    }

    @Test
    fun `god-class-only overall weights are valid when god-class analysis is enabled`() {
        val yaml =
            referenceYaml().replace(
                "    overall:\n      enabled: true\n      weights: null",
                overallWeights(cycles = 0.0, density = 0.0, prevalence = 1.0, external = 0.0),
            )
        val result = ConfigValidation.loadAndValidateV1(StringReader(yaml))
        assertTrue(result.ok, result.errors.toString())
    }

    @Test
    fun `disabled scoring components do not require positive total weights`() {
        val allZero = overallWeights(cycles = 0.0, density = 0.0, prevalence = 0.0, external = 0.0)
        val yaml =
            referenceYaml()
                .replace("    overall:\n      enabled: true\n      weights: null", allZero.replace("enabled: true", "enabled: false"))
        val result = ConfigValidation.loadAndValidateV1(StringReader(yaml))
        assertTrue(result.ok, result.errors.toString())
    }

    @Test
    fun `overall scoring rejects all-zero available weights even with god-class enabled`() {
        val yaml =
            referenceYaml().replace(
                "    overall:\n      enabled: true\n      weights: null",
                overallWeights(cycles = 0.0, density = 0.0, prevalence = 0.0, external = 0.0),
            )
        val result = ConfigValidation.loadAndValidateV1(StringReader(yaml))
        assertTrue(!result.ok)
        assertTrue(result.errors.any { it.path == "analysis.scoring.overall.weights" }, result.errors.toString())
    }

    private fun referenceYaml(): String =
        SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }.replace("\r\n", "\n")

    private fun overallWeights(
        cycles: Double,
        density: Double,
        prevalence: Double,
        external: Double,
    ): String =
        "    overall:\n      enabled: true\n      weights:\n" +
            "        cycles: $cycles\n" +
            "        dependencyDensity: $density\n" +
            "        layeringViolations: 0.0\n" +
            "        godClassPrevalence: $prevalence\n" +
            "        externalCoupling: $external"

    private fun setUnknownRulePolicy(
        yaml: String,
        policy: String,
    ): String {
        val rx = Regex("(?m)^(\\s*unknownRule:)\\s*\\S+\\s*$")
        if (rx.containsMatchIn(yaml)) return yaml.replace(rx, "$1 $policy")

        val validationRx = Regex("(?m)^\\s*validation:\\s*$")
        if (validationRx.containsMatchIn(yaml)) {
            return yaml.replaceFirst(validationRx, "  validation:\n    unknownRule: $policy")
        }

        return yaml.trimEnd() + "\n\nproject:\n  validation:\n    unknownRule: $policy\n"
    }

    private fun injectRuleBlock(
        yaml: String,
        block: String,
    ): String {
        val inlineEmpty = Regex("(?m)^rules:\\s*\\[\\s*]\\s*$")
        if (inlineEmpty.containsMatchIn(yaml)) {
            return yaml.replaceFirst(inlineEmpty, "rules:\n$block")
        }

        val header = Regex("(?m)^rules:\\s*$")
        if (header.containsMatchIn(yaml)) {
            return yaml.replaceFirst(header, "rules:\n$block")
        }

        return yaml.trimEnd() + "\n\nrules:\n$block\n"
    }
}
