/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.rules

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.api.ForbiddenAnnotationUsageRule
import io.shamash.asm.core.engine.rules.api.ForbiddenInternalNamePatternsRule
import io.shamash.asm.core.engine.rules.api.MaxPublicTypesRule
import io.shamash.asm.core.engine.rules.arch.AllowedPackagesRule
import io.shamash.asm.core.engine.rules.arch.AllowedRoleDependenciesRule
import io.shamash.asm.core.engine.rules.arch.ForbiddenPackagesRule
import io.shamash.asm.core.engine.rules.arch.ForbiddenRoleDependenciesRule
import io.shamash.asm.core.engine.rules.graph.MaxCyclesRule
import io.shamash.asm.core.engine.rules.graph.MaxDependencyDensityRule
import io.shamash.asm.core.engine.rules.graph.MaxEdgeCountRule
import io.shamash.asm.core.engine.rules.graph.NoCyclesRule
import io.shamash.asm.core.engine.rules.metrics.MaxFanInRule
import io.shamash.asm.core.engine.rules.metrics.MaxFanOutRule
import io.shamash.asm.core.engine.rules.metrics.MaxFieldsPerClassRule
import io.shamash.asm.core.engine.rules.metrics.MaxMethodsPerClassRule
import io.shamash.asm.core.engine.rules.metrics.MaxPackageSpreadRule
import io.shamash.asm.core.engine.rules.origin.AllowOnlyRootRule
import io.shamash.asm.core.engine.rules.origin.ForbiddenJarDependenciesRule
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import org.objectweb.asm.Opcodes
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmRuleScopeMatrixTest {
    @Test
    fun `all built-in rules respect matching and nonmatching source owner roles`() {
        val project = Files.createTempDirectory("shamash-scope-matrix")
        try {
            val reference =
                SchemaResources.openReferenceYaml().reader().use {
                    assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(it.readText())).config)
                }
            val config =
                reference.copy(
                    analysis =
                        reference.analysis.copy(
                            graphs = reference.analysis.graphs.copy(enabled = false, granularity = Granularity.CLASS),
                        ),
                )
            val source = "a.Core"
            val target = "b.Api"
            val sourceType = type(source)
            val targetType = type(target)
            val sourceLocation =
                SourceLocation(OriginKind.DIR_CLASS, project.resolve("classes/a/Core.class").toString())
            val targetLocation =
                SourceLocation(OriginKind.DIR_CLASS, project.resolve("classes/b/Api.class").toString())
            val classes =
                listOf(
                    ClassFact(sourceType, Opcodes.ACC_PUBLIC, null, emptySet(), setOf("flag.Forbidden"), false, sourceLocation),
                    ClassFact(targetType, Opcodes.ACC_PUBLIC, null, emptySet(), emptySet(), false, targetLocation),
                )
            val facts =
                FactIndex(
                    classes = classes,
                    methods =
                        listOf(
                            MethodRef(
                                sourceType,
                                "run",
                                "()V",
                                null,
                                Opcodes.ACC_PUBLIC,
                                false,
                                null,
                                emptyList(),
                                emptyList(),
                                setOf("flag.Forbidden"),
                                sourceLocation,
                            ),
                        ),
                    fields =
                        listOf(
                            FieldRef(
                                sourceType,
                                "field",
                                "I",
                                null,
                                Opcodes.ACC_PUBLIC,
                                null,
                                setOf("flag.Forbidden"),
                                sourceLocation,
                            ),
                        ),
                    edges =
                        listOf(
                            DependencyEdge(sourceType, targetType, DependencyKind.METHOD_CALL, sourceLocation),
                            DependencyEdge(targetType, sourceType, DependencyKind.METHOD_CALL, targetLocation),
                        ),
                    roles = mapOf("core" to setOf(source), "api" to setOf(target)),
                    classToRole = mapOf(source to "core", target to "api"),
                )
            val cases: List<Pair<Rule, Map<String, Any?>>> =
                listOf(
                    ForbiddenAnnotationUsageRule() to mapOf("forbid" to listOf("flag.Forbidden")),
                    ForbiddenInternalNamePatternsRule() to mapOf("forbid" to listOf("a/Core")),
                    MaxPublicTypesRule() to mapOf("max" to 0),
                    AllowedPackagesRule() to mapOf("allowPackages" to listOf("^safe$")),
                    AllowedRoleDependenciesRule() to mapOf("allow" to listOf("api->core")),
                    ForbiddenPackagesRule() to mapOf("forbidPackages" to listOf("^a$")),
                    ForbiddenRoleDependenciesRule() to mapOf("forbidden" to mapOf("core" to listOf("api"))),
                    NoCyclesRule() to emptyMap(),
                    MaxCyclesRule() to mapOf("maxCycles" to 0),
                    MaxEdgeCountRule() to mapOf("maxEdges" to 0),
                    MaxDependencyDensityRule() to mapOf("maxDensity" to 0.0),
                    MaxFanInRule() to mapOf("max" to 0, "granularity" to "class"),
                    MaxFanOutRule() to mapOf("max" to 0, "granularity" to "class"),
                    MaxFieldsPerClassRule() to mapOf("maxFields" to 0),
                    MaxMethodsPerClassRule() to mapOf("maxMethods" to 0),
                    MaxPackageSpreadRule() to mapOf("max" to 0),
                    AllowOnlyRootRule() to mapOf("roots" to listOf(project.resolve("allowed").toString())),
                    ForbiddenJarDependenciesRule() to
                        mapOf("forbid" to listOf(mapOf("from" to "classes", "to" to "classes"))),
                )
            val included =
                RuleScope(
                    includeRoles = listOf("core", "api"),
                    excludeRoles = null,
                    includePackages = null,
                    excludePackages = null,
                    includeGlobs = null,
                    excludeGlobs = null,
                )
            val missing = included.copy(includeRoles = listOf("missing"))
            for ((evaluator, params) in cases) {
                val definition =
                    RuleDef(
                        evaluator.id.substringBefore('.'),
                        evaluator.id.substringAfter('.'),
                        null,
                        true,
                        FindingSeverity.ERROR,
                        included,
                        params,
                    )
                assertTrue(
                    evaluator.evaluate(facts, definition, config).isNotEmpty(),
                    "Included owners must match " + evaluator.id,
                )
                assertTrue(
                    evaluator.evaluate(facts, definition.copy(scope = missing), config).isEmpty(),
                    "Missing roles must not match " + evaluator.id,
                )
                assertTrue(
                    evaluator.evaluate(
                        facts,
                        definition.copy(scope = included.copy(excludeRoles = listOf("core", "api"))),
                        config,
                    ).isEmpty(),
                    "Excluded owners must not match " + evaluator.id,
                )
            }
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun type(fqName: String): TypeRef = TypeRef.fromInternalName(fqName.replace('.', '/'))
}
