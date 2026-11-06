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
import io.shamash.psi.core.config.SchemaResources
import io.shamash.psi.core.config.schema.v1.model.Matcher
import io.shamash.psi.core.config.schema.v1.model.Role
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.RuleScope
import io.shamash.psi.core.config.schema.v1.model.Severity
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.schema.v1.model.UnknownRulePolicyV1
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuleInstanceScopeTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testAuthoredRoleInclusionIntersectsInstanceRole() {
        val file = myFixture.configureByText("Sample.java", "package example; class Sample {}")
        val config = config()
        val service = Role(null, 50, Matcher.ClassNameEndsWith("Sample"))
        val never = Role(null, 50, Matcher.ClassNameEndsWith("Missing"))
        val matching =
            RuleScope(
                includeRoles = listOf("service"),
                excludeRoles = null,
                includePackages = null,
                excludePackages = null,
                includeGlobs = null,
                excludeGlobs = null,
            )
        val definition =
            RuleDef(
                "naming",
                "bannedSuffixes",
                listOf("service"),
                true,
                Severity.ERROR,
                matching,
                mapOf("banned" to listOf("Sample")),
            )
        val withRoles = config.copy(roles = mapOf("service" to service, "never" to never))
        val engine = ShamashPsiEngine()
        val positive = engine.analyzeFileResult(file, withRoles.copy(rules = listOf(definition)))
        assertTrue(positive.errors.isEmpty(), positive.errors.toString())
        assertEquals(listOf("naming.bannedSuffixes.service"), positive.findings.map { it.ruleId })

        val mismatch = definition.copy(scope = matching.copy(includeRoles = listOf("never")))
        val negative = engine.analyzeFileResult(file, withRoles.copy(rules = listOf(mismatch)))
        assertTrue(negative.errors.isEmpty(), negative.errors.toString())
        assertTrue(negative.findings.isEmpty())

        val excluded = definition.copy(scope = matching.copy(excludeRoles = listOf("service")))
        assertTrue(engine.analyzeFileResult(file, withRoles.copy(rules = listOf(excluded))).findings.isEmpty())
    }

    fun testUnknownRequiredRuleProducesRuntimeError() {
        val file = myFixture.configureByText("Sample.java", "package example; class Sample {}")
        val definition = RuleDef("missing", "rule", null, true, Severity.ERROR, null, emptyMap())
        val config =
            config().copy(
                rules = listOf(definition),
                project =
                    config().project.copy(
                        validation = config().project.validation.copy(unknownRule = UnknownRulePolicyV1.ERROR),
                    ),
            )
        val result = ShamashPsiEngine().analyzeFileResult(file, config)
        assertTrue(result.errors.any { it.phase == "rule:unknown" && it.ruleId == "missing.rule" })
        assertTrue(result.findings.isEmpty())
        assertFailsWith<IllegalStateException> { ShamashPsiEngine().analyzeFile(file, config) }
    }

    private fun config(): ShamashPsiConfigV1 =
        SchemaResources.openReferenceYaml().reader().use {
            checkNotNull(ConfigValidation.loadAndValidateV1(StringReader(it.readText())).config)
        }
}
