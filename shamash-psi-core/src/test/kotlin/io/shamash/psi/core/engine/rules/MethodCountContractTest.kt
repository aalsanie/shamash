/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.engine.rules

import com.intellij.psi.PsiFile
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import io.shamash.artifacts.params.ParamError
import io.shamash.psi.core.config.ConfigValidation
import io.shamash.psi.core.config.SchemaResources
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.Severity
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.MethodCountKinds
import io.shamash.psi.core.config.validation.v1.specs.MetricsMaxMethodsByRoleSpec
import io.shamash.psi.core.facts.FactExtractor
import io.shamash.psi.core.facts.model.v1.FactsIndex
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MethodCountContractTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testJavaCountKindsAndConstructors() {
        val file =
            myFixture.configureByText(
                "Sample.java",
                """
                package example;
                public class Sample {
                    public Sample() {}
                    public void visible() {}
                    private void hiddenOne() {}
                    private void hiddenTwo() {}
                    private void ignoredPrivate() {}
                }
                """.trimIndent(),
            )
        val config = config()
        val role = config.roles.keys.first()
        val facts = withRole(FactExtractor.extractResult(file).facts, "example.Sample", role)

        assertCount(file, facts, config, role, listOf("publicMethods"), 1)
        assertCount(file, facts, config, role, listOf("privateMethods"), 3)
        assertCount(file, facts, config, role, listOf("declaredMethods"), 4)
        assertCount(file, facts, config, role, listOf("privateMethods", "publicMethods"), 4)
        assertCount(file, facts, config, role, listOf("declaredMethods", "privateMethods"), 4)
        assertCount(file, facts, config, role, listOf("publicMethods", "declaredMethods"), 4)
        assertCount(file, facts, config, role, listOf("declaredMethods", "publicMethods", "privateMethods"), 4)
        assertCount(file, facts, config, role, null, 4)
        assertCount(
            file,
            facts,
            config,
            role,
            listOf("privateMethods"),
            2,
            ignore = listOf("^ignored"),
        )
    }

    fun testKotlinMethodsExcludeGeneratedPropertyAccessors() {
        val file =
            myFixture.configureByText(
                "Sample.kt",
                """
                package example
                class Sample {
                    val generated: String = "text"
                    var mutable: Int = 0
                    constructor()
                    fun visible() {}
                    private fun hiddenOne() {}
                    private fun hiddenTwo() {}
                    private fun ignoredPrivate() {}
                }
                """.trimIndent(),
            )
        val config = config()
        val role = config.roles.keys.first()
        val extracted = FactExtractor.extractResult(file)
        assertTrue(extracted.errors.isEmpty(), extracted.errors.toString())
        assertTrue(extracted.facts.methods.none { it.name == "getGenerated" || it.name == "getMutable" || it.name == "setMutable" })
        val facts = withRole(extracted.facts, "example.Sample", role)
        assertCount(file, facts, config, role, listOf("publicMethods"), 1)
        assertCount(file, facts, config, role, listOf("privateMethods"), 3)
        assertCount(file, facts, config, role, listOf("declaredMethods"), 4)
        assertCount(file, facts, config, role, listOf("publicMethods", "privateMethods"), 4)
        assertCount(file, facts, config, role, listOf("declaredMethods", "publicMethods"), 4)
        assertCount(file, facts, config, role, listOf("privateMethods"), 2, ignore = listOf("^ignored"))
    }

    fun testInvalidCountKindsDoNotSilentlyDefault() {
        val file = myFixture.configureByText("Sample.java", "package example; public class Sample { public void f() {} }")
        val config = config()
        val role = config.roles.keys.first()
        val facts = withRole(FactExtractor.extractResult(file).facts, "example.Sample", role)
        val spec = MetricsMaxMethodsByRoleSpec()

        for (invalid in listOf(listOf("PUBLIC_METHODS"), listOf("PublicMethods"), listOf("unknown"), listOf(""))) {
            val definition = definition(role, invalid)
            assertTrue(spec.validate("rules[0]", definition, config).isNotEmpty())
            assertFailsWith<ParamError> {
                MetricsMaxMethodsByRoleRule().evaluate(file, facts, definition, config)
            }
        }
        val empty = definition(role, emptyList())
        assertTrue(spec.validate("rules[0]", empty, config).isNotEmpty())
        assertFailsWith<ParamError> { MetricsMaxMethodsByRoleRule().evaluate(file, facts, empty, config) }
    }

    private fun assertCount(
        file: PsiFile,
        facts: FactsIndex,
        config: ShamashPsiConfigV1,
        role: String,
        kinds: List<String>?,
        count: Int,
        ignore: List<String> = emptyList(),
    ) {
        val definition = definition(role, kinds, ignore)
        assertTrue(MetricsMaxMethodsByRoleSpec().validate("rules[0]", definition, config).isEmpty())
        val findings = MetricsMaxMethodsByRoleRule().evaluate(file, facts, definition, config)
        assertEquals(1, findings.size, "Expected a finding for $kinds; got $findings")
        assertEquals("example.Sample", findings.single().classFqn)
        assertEquals(count.toString(), findings.single().data["actual"])
        assertEquals(
            emptyList(),
            MetricsMaxMethodsByRoleRule().evaluate(
                file,
                facts,
                definition.copy(params = definition.params + ("limits" to mapOf(role to count))),
                config,
            ),
        )
    }

    private fun definition(
        role: String,
        kinds: List<String>?,
        ignore: List<String> = emptyList(),
    ): RuleDef {
        val parameters =
            mutableMapOf<String, Any?>(
                "limits" to mapOf(role to 0),
                "ignoreMethodNameRegex" to ignore,
            )
        if (kinds != null) parameters["countKinds"] = kinds
        return RuleDef("metrics", "maxMethodsByRole", null, true, Severity.ERROR, null, parameters)
    }

    private fun withRole(facts: FactsIndex, className: String, role: String): FactsIndex =
        facts.copy(roles = mapOf(role to setOf(className)), classToRole = mapOf(className to role))

    private fun config(): ShamashPsiConfigV1 =
        SchemaResources.openReferenceYaml().reader().use {
            assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(it.readText())).config)
        }
}
