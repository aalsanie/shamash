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
import io.shamash.psi.core.config.schema.v1.model.RootPackageConfigV1
import io.shamash.psi.core.config.schema.v1.model.RootPackageModeV1
import io.shamash.psi.core.config.schema.v1.model.RuleDef
import io.shamash.psi.core.config.schema.v1.model.RuleScope
import io.shamash.psi.core.config.schema.v1.model.Severity
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.validation.v1.specs.PackagesRootPackageSpec
import io.shamash.psi.core.facts.FactExtractor
import io.shamash.psi.core.facts.model.v1.DependencyFact
import io.shamash.psi.core.facts.model.v1.DependencyKind
import io.shamash.psi.core.facts.model.v1.FactsIndex
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceOwnerScopeContractTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    fun testArchitectureUsesSourceRoleAndPackageGlobIntersections() {
        val file =
            myFixture.configureByText(
                "Controller.java",
                "package example; class Controller { void use(Repository r) {} } class Repository {}",
            )
        val original = FactExtractor.extractResult(file)
        assertTrue(original.errors.isEmpty(), original.errors.toString())
        val roles = mapOf("example.Controller" to "controller", "example.Repository" to "repository")
        val facts =
            original.facts.copy(
                classToRole = roles,
                roles = mapOf("controller" to setOf("example.Controller"), "repository" to setOf("example.Repository")),
                dependencies =
                    listOf(
                        DependencyFact(
                            "example.Controller",
                            "example.Repository",
                            DependencyKind.PARAMETER_TYPE,
                            file.virtualFile.path,
                        ),
                    ),
            )
        val base = rule("arch", "forbiddenRoleDependencies", mapOf(
            "forbidden" to listOf(mapOf("from" to "controller", "to" to listOf("repository"))),
        ))
        val evaluator = ArchForbiddenRoleDependenciesRule()
        val valid =
            RuleScope(
                includeRoles = listOf("controller"),
                excludeRoles = null,
                includePackages = listOf("^example$"),
                excludePackages = null,
                includeGlobs = listOf("**/Controller.java"),
                excludeGlobs = null,
            )
        assertEquals(1, evaluator.evaluate(file, facts, base.copy(scope = valid), config()).size)
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = valid.copy(
            includeRoles = listOf("repository"),
        )), config()).isEmpty())
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = valid.copy(
            excludeRoles = listOf("controller"),
        )), config()).isEmpty())
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = valid.copy(
            excludePackages = listOf("^example$"),
        )), config()).isEmpty())
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = valid.copy(
            excludeGlobs = listOf("**/Controller.java"),
        )), config()).isEmpty())
        assertTrue(evaluator.evaluate(file, facts, base.copy(roles = listOf("repository")), config()).isEmpty())
        assertTrue(evaluator.evaluate(file, facts.copy(classToRole = emptyMap()), base.copy(scope = valid), config()).isEmpty())
        assertFailsWith<ParamError> {
            evaluator.evaluate(file, facts, base.copy(params = base.params + ("kinds" to listOf("invalidKind"))), config())
        }
    }

    fun testRootPackageRequiresConfiguredAutoRootAndAppliesOwnerScope() {
        val file = myFixture.configureByText("Sample.kt", "package examplex\nclass Sample")
        val config = config()
        val extracted = FactExtractor.extractResult(file)
        assertTrue(extracted.errors.isEmpty(), extracted.errors.toString())
        val facts = extracted.facts.withRole("examplex.Sample", "service")
        val evaluator = PackagesRootPackageRule()
        val auto = rule("packages", "rootPackage", mapOf("mode" to "AUTO"))

        assertTrue(PackagesRootPackageSpec().validate("rules[0]", auto, config).isNotEmpty())
        assertFailsWith<ParamError> { evaluator.evaluate(file, facts, auto, config) }

        val configured =
            config.copy(project = config.project.copy(
                rootPackage = RootPackageConfigV1(RootPackageModeV1.EXPLICIT, "example"),
            ))
        assertTrue(PackagesRootPackageSpec().validate("rules[0]", auto, configured).isEmpty())
        assertEquals(1, evaluator.evaluate(file, facts, auto, configured).size)
        assertEquals(1, evaluator.evaluate(
            file, facts, rule("packages", "rootPackage", mapOf("mode" to "EXPLICIT", "value" to "example")), configured,
        ).size)
        val scope =
            RuleScope(
                includeRoles = listOf("service"),
                excludeRoles = null,
                includePackages = null,
                excludePackages = null,
                includeGlobs = null,
                excludeGlobs = null,
            )
        assertEquals(1, evaluator.evaluate(file, facts, auto.copy(scope = scope), configured).size)
        assertTrue(evaluator.evaluate(file, facts, auto.copy(scope = scope.copy(
            excludeRoles = listOf("service"),
        )), configured).isEmpty())
        assertTrue(evaluator.evaluate(file, facts, auto.copy(scope = scope.copy(
            excludePackages = listOf("^examplex$"),
        )), configured).isEmpty())
        assertTrue(evaluator.evaluate(file, facts.copy(classToRole = emptyMap()), auto.copy(scope = scope), configured).isEmpty())
        assertTrue(PackagesRootPackageSpec().validate(
            "rules[0]", auto.copy(params = mapOf("mode" to "AUTO", "value" to "example")), configured,
        ).isNotEmpty())

        val defaultFile = myFixture.configureByText("Default.java", "class Default {}")
        val defaultFacts = FactExtractor.extractResult(defaultFile).facts
        assertTrue(evaluator.evaluate(defaultFile, defaultFacts, auto, configured).isEmpty())
    }

    fun testDeadCodeRuleSkipsExcludedJavaOwners() {
        val file = myFixture.configureByText(
            "Unused.java",
            "package example; class Unused { private void orphan() {} }",
        )
        val facts = FactExtractor.extractResult(file).facts.withRole("example.Unused", "service")
        val evaluator = DeadcodeUnusedPrivateMembersRule()
        val base = rule("deadcode", "unusedPrivateMembers", mapOf(
            "check" to mapOf("fields" to false, "methods" to true, "classes" to false),
        ))
        val selected =
            RuleScope(
                includeRoles = listOf("service"),
                excludeRoles = null,
                includePackages = null,
                excludePackages = null,
                includeGlobs = null,
                excludeGlobs = null,
            )
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = selected), config()).isNotEmpty())
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = selected.copy(
            excludeRoles = listOf("service"),
        )), config()).isEmpty())
        assertTrue(evaluator.evaluate(file, facts, base.copy(scope = selected.copy(
            excludeGlobs = listOf("**/Unused.java"),
        )), config()).isEmpty())
    }

    fun testNestedKotlinClassesFollowTheirOwnRoles() {
        val file = myFixture.configureByText(
            "Nested.kt",
            "package example\nclass Parent { class Nested }",
        )
        val extracted = FactExtractor.extractResult(file)
        assertTrue(extracted.errors.isEmpty(), extracted.errors.toString())
        val facts = extracted.facts.copy(
            classToRole = mapOf("example.Parent" to "parent", "example.Parent.Nested" to "nested"),
        )
        val scope = RuleScope(
            includeRoles = listOf("parent"),
            excludeRoles = null,
            includePackages = listOf("^example$"),
            excludePackages = null,
            includeGlobs = listOf("**/Nested.kt"),
            excludeGlobs = null,
        )
        val definition = rule("naming", "bannedSuffixes", mapOf("banned" to listOf("Parent", "Nested")))
            .copy(scope = scope)
        assertEquals(listOf("example.Parent"), RuleUtil.scopedClasses(facts, definition).map { it.fqName })
        assertTrue(RuleUtil.scopedClasses(facts, definition.copy(
            scope = scope.copy(excludeRoles = listOf("parent")),
        )).isEmpty())
        assertTrue(RuleUtil.scopedClasses(facts, definition.copy(
            scope = scope.copy(excludeGlobs = listOf("**/Nested.kt")),
        )).isEmpty())
    }

    private fun FactsIndex.withRole(classFqn: String, role: String): FactsIndex =
        copy(classToRole = mapOf(classFqn to role), roles = mapOf(role to setOf(classFqn)))

    private fun rule(type: String, name: String, params: Map<String, Any?>): RuleDef =
        RuleDef(type, name, null, true, Severity.ERROR, null, params)

    private fun config(): ShamashPsiConfigV1 =
        SchemaResources.openReferenceYaml().reader().use {
            assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(it.readText())).config)
        }
}
