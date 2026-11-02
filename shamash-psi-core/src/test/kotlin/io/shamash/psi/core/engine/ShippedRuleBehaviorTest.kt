/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.engine

import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.psi.core.config.ConfigValidation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShippedRuleBehaviorTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testNamingExampleFindsActionSuffixOnlyForApplicableRole() {
        assertExample(
            "04_naming_banned_suffixes.yml",
            "CreateAction.java",
            "package com.acme.controller; class CreateAction {}",
            "CreateController.java",
            "package com.acme.controller; class CreateController {}",
            "naming.bannedSuffixes",
        )
    }

    fun testDeadcodeExampleDoesNotSuppressEveryRole() {
        assertExample(
            "06_deadcode_unused_private_members.yml",
            "Unused.java",
            "package example; class Unused { private void orphan() {} }",
            "Ignored.java",
            "package example; class Ignored { private void _ignored() {} }",
            "deadcode.unusedPrivateMembers",
        )
    }

    fun testRolePlacementMatchesClassNamesOutsideTheExpectedPackage() {
        assertExample(
            "07_packages_role_placement.yml",
            "BadController.java",
            "package example.wrong; class BadController {}",
            "GoodController.java",
            "package example.controller; class GoodController {}",
            "packages.rolePlacement",
        )
    }

    fun testRootPackageExampleEnforcesItsDeclaredRoot() {
        assertExample(
            "08_packages_root_package_explicit.yml",
            "Outside.java",
            "package example.wrong; class Outside {}",
            "Inside.java",
            "package com.acme; class Inside {}",
            "packages.rootPackage",
        )
    }

    fun testShowcaseDoesNotPretendAutoInference() {
        assertExample(
            "10_full_showcase.yml",
            "Outside.java",
            "package example.wrong; class Outside {}",
            "Inside.java",
            "package com.acme; class Inside {}",
            "packages.rootPackage",
        )
    }

    private fun assertExample(
        yamlName: String,
        violatingFile: String,
        violatingSource: String,
        cleanFile: String,
        cleanSource: String,
        ruleId: String,
    ) {
        val root =
            generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
                .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val path = root.resolve("docs/psi/schema/v1/examples").resolve(yamlName)
        val validation = Files.newBufferedReader(path).use { ConfigValidation.loadAndValidateV1(it) }
        assertTrue(validation.ok, "$path: ${validation.errors}")
        val config = assertNotNull(validation.config)

        val violating = myFixture.configureByText(violatingFile, violatingSource)
        val detected = ShamashPsiEngine().analyzeFileResult(violating, config)
        assertTrue(detected.errors.isEmpty(), "$yamlName violating: ${detected.errors}")
        assertTrue(detected.findings.any { it.ruleId.startsWith(ruleId) }, "$yamlName did not detect: ${detected.findings}")

        val clean = myFixture.configureByText(cleanFile, cleanSource)
        val passed = ShamashPsiEngine().analyzeFileResult(clean, config)
        assertTrue(passed.errors.isEmpty(), "$yamlName clean: ${passed.errors}")
        assertTrue(passed.findings.none { it.ruleId.startsWith(ruleId) }, "$yamlName false positive: ${passed.findings}")
    }
}
