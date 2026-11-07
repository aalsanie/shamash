/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShippedConfigurationTest {
    @Test
    fun `all complete ASM examples validate through schema binding semantics and registry`() {
        val root = repositoryRoot()
        val exampleDir = root.resolve("docs/asm/schema/v1/examples")
        val files =
            Files.list(exampleDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".yml") }.sorted().toList()
            }
        assertEquals(
            (1..18).toList(),
            files.map {
                it.fileName
                    .toString()
                    .substringBefore('_')
                    .toInt()
            },
        )
        val registry = DefaultRuleRegistry.create()
        val invalidExamples = mutableListOf<String>()
        for (file in files) {
            val result = Files.newBufferedReader(file).use { ConfigValidation.loadAndValidateV1(it, registry) }
            if (!result.ok) {
                invalidExamples += "$file: ${result.errors}"
                continue
            }
            val config = assertNotNull(result.config, file.toString())
            assertTrue(config.rules.isNotEmpty(), "$file contains no rule")
            assertTrue(config.rules.any { it.enabled }, "$file contains no enabled rule")
            config.rules.filter { it.enabled }.forEach { rule ->
                assertNotNull(registry.byId("${rule.type}.${rule.name}"), "$file: unimplemented rule ${rule.type}.${rule.name}")
            }
        }
        assertTrue(invalidExamples.isEmpty(), invalidExamples.joinToString("\n"))
    }

    @Test
    fun `shipped ASM presets and application example validate`() {
        val root = repositoryRoot()
        val resourceDir = root.resolve("shamash-asm-core/src/main/resources/shamash/asm/schema/v1")
        val files =
            listOf(
                resourceDir.resolve("shamash-asm.discovery.yml"),
                resourceDir.resolve("shamash-asm.reference.yml"),
                resourceDir.resolve("shamash-asm.spring.yml"),
                resourceDir.resolve("shamash-asm.starter.yml"),
                root.resolve("docs/asm/pit-violation/src/main/resources/shamash/configs/asm.yml"),
            )
        val registry = DefaultRuleRegistry.create()
        for (file in files) {
            assertTrue(file.isRegularFile(), "Missing shipped configuration: $file")
            val result = Files.newBufferedReader(file).use { ConfigValidation.loadAndValidateV1(it, registry) }
            assertTrue(result.ok, "$file: ${result.errors}")
            assertNotNull(result.config, file.toString())
        }
    }

    @Test
    fun `broken schema and outdated graph parameter names remain invalid`() {
        val root = repositoryRoot()
        val file = root.resolve("docs/asm/schema/v1/examples/18_graph_max_cycles.yml")
        val yaml = Files.readString(file)
        val invalidScope = yaml.replace("    severity: WARNING", "    severity: WARNING\n    scope: null")
        val invalidKey = yaml.replace("maxCycles: 0", "max: 0")
        assertTrue(invalidScope != yaml && invalidKey != yaml)
        for (invalid in listOf(invalidScope, invalidKey)) {
            val result = ConfigValidation.loadAndValidateV1(StringReader(invalid), DefaultRuleRegistry.create())
            assertTrue(!result.ok, "Invalid example unexpectedly passed: $invalid")
        }
    }

    @Test
    fun `disabled baseline and export permit omitted optional fields`() {
        val file = repositoryRoot().resolve("docs/asm/schema/v1/examples/08_metrics_max_methods_per_class.yml")
        val yaml = Files.readString(file)
        val withoutFormats = yaml.replace("  formats: [JSON]\n", "")
        assertTrue(withoutFormats != yaml)
        val result = ConfigValidation.loadAndValidateV1(StringReader(withoutFormats), DefaultRuleRegistry.create())
        assertTrue(result.ok, "Disabled features should not require paths or export formats")
        val config = assertNotNull(result.config)
        assertEquals("", config.baseline.path)
        assertEquals("", config.export.outputDir)
        assertTrue(config.export.formats.isEmpty())
    }

    @Test
    fun `enabled baseline and export still require their configuration`() {
        val file = repositoryRoot().resolve("docs/asm/schema/v1/examples/08_metrics_max_methods_per_class.yml")
        val yaml = Files.readString(file)
        val invalid =
            listOf(
                yaml.replace("mode: NONE", "mode: GENERATE"),
                yaml.replace("enabled: false", "enabled: true"),
            )
        assertTrue(invalid.all { it != yaml })
        for (candidate in invalid) {
            val result = ConfigValidation.loadAndValidateV1(StringReader(candidate), DefaultRuleRegistry.create())
            assertTrue(!result.ok, "Enabled feature accepted an incomplete configuration")
        }
    }

    @Test
    fun `jar-only scans are valid but scans without any input patterns are rejected`() {
        val file = repositoryRoot().resolve("docs/asm/pit-violation/src/main/resources/shamash/configs/asm.yml")
        val yaml = Files.readString(file)
        val registry = DefaultRuleRegistry.create()
        val jarOnly = ConfigValidation.loadAndValidateV1(StringReader(yaml), registry)
        assertTrue(jarOnly.ok, "JAR-only scans must be accepted")
        val withoutJar = yaml.replace(
            "      include:\n        - \"**/build/libs/*.jar\"",
            "      include: []",
        )
        assertTrue(withoutJar != yaml)
        val empty = ConfigValidation.loadAndValidateV1(StringReader(withoutJar), registry)
        assertTrue(!empty.ok, "Scans with no input patterns must be rejected")
        assertTrue(empty.errors.any { it.path == "project.bytecode.outputsGlobs.include" })
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Cannot locate project root for shipped configuration tests")
}
