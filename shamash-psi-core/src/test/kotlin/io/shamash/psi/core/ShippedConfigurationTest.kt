/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core

import io.shamash.psi.core.config.ConfigValidation
import io.shamash.psi.core.config.SchemaResources
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.validation.v1.specs.DeadcodeUnusedPrivateMembersSpec
import io.shamash.psi.core.config.validation.v1.specs.PackagesRootPackageSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShippedConfigurationTest {
    @Test
    fun `all complete PSI examples are executable configurations`() {
        val root = repositoryRoot()
        val dir = root.resolve("docs/psi/schema/v1/examples")
        val files =
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".yml") }.sorted().toList()
            }
        assertEquals(
            (1..10).toList(),
            files.map {
                it.fileName
                    .toString()
                    .substringBefore('_')
                    .toInt()
            },
        )
        for (file in files) assertValid(file)
    }

    @Test
    fun `shipped PSI presets and application configuration validate`() {
        val root = repositoryRoot()
        assertValid(root.resolve("shamash-psi-core/src/main/resources/shamash/psi/schema/v1/shamash-psi.reference.yml"))
        assertValid(root.resolve("docs/asm/pit-violation/src/main/resources/shamash/configs/psi.yml"))
        assertValid(root.resolve("docs/psi/pit-violation/psi.yml"))
        val reference =
            SchemaResources.openReferenceYaml().bufferedReader().use {
                ConfigValidation.loadAndValidateV1(it)
            }
        assertTrue(reference.ok, "Embedded PSI reference: ${reference.errors}")
    }

    @Test
    fun `deadcode ignore regex must be a list and AUTO root requires a configured root`() {
        val root = repositoryRoot()
        val deadcodeFile = root.resolve("docs/psi/schema/v1/examples/06_deadcode_unused_private_members.yml")
        val deadcode = Files.newBufferedReader(deadcodeFile).use { ConfigValidation.loadAndValidateV1(it) }
        val deadConfig = assertNotNull(deadcode.config)
        val deadRule = deadConfig.rules.single { it.type == "deadcode" }
        assertTrue(DeadcodeUnusedPrivateMembersSpec().validate("rules[0]", deadRule, deadConfig).isEmpty())
        val invalid = deadRule.copy(params = deadRule.params + ("ignoreNameRegex" to "^_"))
        assertTrue(DeadcodeUnusedPrivateMembersSpec().validate("rules[0]", invalid, deadConfig).isNotEmpty())

        val showcase =
            Files.newBufferedReader(root.resolve("docs/psi/schema/v1/examples/10_full_showcase.yml")).use {
                ConfigValidation.loadAndValidateV1(it)
            }
        val showcaseConfig = assertNotNull(showcase.config)
        val rootRule = showcaseConfig.rules.single { it.type == "packages" && it.name == "rootPackage" }
        assertTrue(PackagesRootPackageSpec().validate("rules[0]", rootRule, showcaseConfig).isEmpty())
        val badAuto = rootRule.copy(params = mapOf("mode" to "AUTO", "value" to ""))
        assertTrue(PackagesRootPackageSpec().validate("rules[0]", badAuto, showcaseConfig).isNotEmpty())
        val missingRoot = showcaseConfig.copy(project = showcaseConfig.project.copy(rootPackage = null))
        val auto = rootRule.copy(params = mapOf("mode" to "AUTO"))
        assertTrue(PackagesRootPackageSpec().validate("rules[0]", auto, missingRoot).isNotEmpty())
    }

    private fun assertValid(file: Path) {
        assertTrue(Files.isRegularFile(file), "Missing shipped configuration: $file")
        val result = Files.newBufferedReader(file).use { ConfigValidation.loadAndValidateV1(it) }
        assertTrue(result.ok, "$file: ${result.errors}")
        assertNotNull(result.config, "$file has no bound configuration")
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Cannot locate project root for shipped configuration tests")
}
