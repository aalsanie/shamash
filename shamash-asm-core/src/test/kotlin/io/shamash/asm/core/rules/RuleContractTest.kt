/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.rules

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.params.ParamError
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.validation.v1.specs.GraphMaxCyclesSpec
import io.shamash.asm.core.config.validation.v1.specs.GraphMaxDependencyDensitySpec
import io.shamash.asm.core.config.validation.v1.specs.GraphMaxEdgeCountSpec
import io.shamash.asm.core.config.validation.v1.specs.OriginAllowOnlyRootSpec
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.RuleExecutionContext
import io.shamash.asm.core.engine.rules.graph.MaxCyclesRule
import io.shamash.asm.core.engine.rules.graph.MaxDependencyDensityRule
import io.shamash.asm.core.engine.rules.graph.MaxEdgeCountRule
import io.shamash.asm.core.engine.rules.origin.AllowOnlyRootRule
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleContractTest {
    private val baseConfig by lazy {
        val yaml = SchemaResources.openReferenceYaml().bufferedReader().use { it.readText() }
        val parsed = ConfigValidation.loadAndValidateV1(StringReader(yaml))
        requireNotNull(parsed.config).copy(
            rules = emptyList(),
            export = requireNotNull(parsed.config).export.copy(enabled = false),
            baseline = requireNotNull(parsed.config).baseline.copy(mode = BaselineMode.NONE),
        )
    }

    private fun rule(
        type: String,
        name: String,
        params: Map<String, Any?>,
    ): RuleDef = RuleDef(type, name, null, true, FindingSeverity.ERROR, null, params)

    private fun location(
        path: String,
        kind: OriginKind = OriginKind.DIR_CLASS,
    ): SourceLocation =
        SourceLocation(originKind = kind, originPath = path, containerPath = if (kind == OriginKind.JAR_ENTRY) path else null)

    private fun facts(
        origins: List<Pair<String, SourceLocation>>,
        cyclic: Boolean = false,
    ): FactIndex {
        val types = origins.map { TypeRef.fromInternalName(it.first.replace('.', '/')) }
        val classes =
            types.zip(origins).map { (type, entry) ->
                ClassFact(type, 0, null, emptySet(), emptySet(), false, entry.second)
            }
        val edges =
            if (cyclic) {
                listOf(0 to 1, 1 to 0).map { (from, to) ->
                    DependencyEdge(types[from], types[to], DependencyKind.METHOD_CALL, classes[from].location)
                }
            } else {
                emptyList()
            }
        return FactIndex(classes, emptyList(), emptyList(), edges, emptyMap(), emptyMap())
    }

    @Test
    fun `validated zero thresholds emit real findings and permissive thresholds do not`() {
        val f = facts(listOf("a.A" to location("build/classes/a/A.class"), "b.B" to location("build/classes/b/B.class")), true)
        val cases =
            listOf(
                Triple(rule("graph", "maxCycles", mapOf("maxCycles" to 0)), GraphMaxCyclesSpec(), MaxCyclesRule()),
                Triple(rule("graph", "maxEdgeCount", mapOf("maxEdges" to 0)), GraphMaxEdgeCountSpec(), MaxEdgeCountRule()),
                Triple(
                    rule("graph", "maxDependencyDensity", mapOf("maxDensity" to 0.0)),
                    GraphMaxDependencyDensitySpec(),
                    MaxDependencyDensityRule(),
                ),
            )
        for ((definition, spec, evaluator) in cases) {
            assertTrue(spec.validate("rules[0]", definition, baseConfig).isEmpty(), definition.toString())
            assertEquals(1, evaluator.evaluate(f, definition, baseConfig).size, definition.toString())
            val result =
                ShamashAsmEngine().analyze(
                    Files.createTempDirectory("shamash-graph"),
                    "test",
                    baseConfig.copy(rules = listOf(definition)),
                    f,
                )
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.findings.size)
        }
        assertTrue(MaxCyclesRule().evaluate(f, rule("graph", "maxCycles", mapOf("maxCycles" to 1)), baseConfig).isEmpty())
        assertTrue(MaxEdgeCountRule().evaluate(f, rule("graph", "maxEdgeCount", mapOf("maxEdges" to 2)), baseConfig).isEmpty())
        assertTrue(
            MaxDependencyDensityRule().evaluate(f, rule("graph", "maxDependencyDensity", mapOf("maxDensity" to 1.0)), baseConfig).isEmpty(),
        )
    }

    @Test
    fun `graph threshold validation and direct execution reject malformed parameters`() {
        val configs =
            listOf(
                Triple("maxCycles", GraphMaxCyclesSpec(), MaxCyclesRule()),
                Triple("maxEdges", GraphMaxEdgeCountSpec(), MaxEdgeCountRule()),
                Triple("maxDensity", GraphMaxDependencyDensitySpec(), MaxDependencyDensityRule()),
            )
        val f = facts(listOf("a.A" to location("a/A.class"), "b.B" to location("b/B.class")), true)
        for ((key, spec, evaluator) in configs) {
            val name = spec.key.name
            val samples =
                listOf(
                    mapOf("max" to 0),
                    mapOf(key to -1),
                    mapOf(key to "oops"),
                    mapOf(key to "0"),
                    mapOf(key to null),
                    mapOf(key to 0, "unexpected" to true),
                    emptyMap(),
                ) + (
                    if (key == "maxDensity") {
                        listOf(mapOf(key to 2.0), mapOf(key to Double.NaN), mapOf(key to Double.POSITIVE_INFINITY))
                    } else {
                        listOf(mapOf(key to 0.0), mapOf(key to 0.5), mapOf(key to Long.MAX_VALUE))
                    }
                )
            for (params in samples) {
                val definition = rule("graph", name, params)
                val errors = spec.validate("rules[0]", definition, baseConfig)
                assertTrue(errors.isNotEmpty(), definition.toString())
                assertTrue(errors.all { it.path.startsWith("rules[0].params.") }, errors.toString())
                assertFailsWith<ParamError>(definition.toString()) { evaluator.evaluate(f, definition, baseConfig) }
                val result =
                    ShamashAsmEngine().analyze(
                        Files.createTempDirectory("shamash-invalid-rule"),
                        "bad",
                        baseConfig.copy(rules = listOf(definition)),
                        f,
                    )
                assertFalse(result.isSuccess)
                assertTrue(result.errors.any { it.details["at"]?.contains(".params.") == true }, result.errors.toString())
            }
        }
    }

    @Test
    fun `origin rule evaluates normalized paths and jar container instead of package names`() {
        val base = Files.createTempDirectory("shamash origin policy ")
        try {
            val allowed = base.resolve("build/classes")
            val rule =
                rule("origin", "allowOnlyRoot", mapOf("roots" to listOf("build/classes", base.resolve("lib/approved.jar").toString())))
            val f =
                facts(
                    listOf(
                        "wrong.pkg.Inside" to location(allowed.resolve("wrong/pkg/Inside.class").toString()),
                        "outside.pkg.Sibling" to location(base.resolve("build/classes-old/Outside.class").toString()),
                        "outside.pkg.Traversal" to location(base.resolve("build/classes/../outside/Outside.class").toString()),
                        "wrong.pkg.Jar" to location(base.resolve("lib/approved.jar").toString(), OriginKind.JAR_ENTRY),
                        "right.pkg.Outside" to location(base.resolve("other/Outside.class").toString()),
                    ),
                )
            val findings = AllowOnlyRootRule().evaluate(f, rule, baseConfig, RuleExecutionContext(base))
            assertEquals(
                setOf("outside.pkg.Sibling", "outside.pkg.Traversal", "right.pkg.Outside"),
                findings.mapNotNull { it.classFqn }.toSet(),
            )
            val result = ShamashAsmEngine().analyze(base, "demo", baseConfig.copy(rules = listOf(rule)), f)
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(3, result.findings.size)
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `relative roots require context and invalid origins are execution errors`() {
        val def = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf("build/classes")))
        val f = facts(listOf("a.A" to location("build/classes/a/A.class")))
        val evaluator = AllowOnlyRootRule()
        assertFailsWith<ParamError> { evaluator.evaluate(f, def, baseConfig) }
        val base = Files.createTempDirectory("shamash-relative-roots")
        try {
            val results = evaluator.evaluate(f, def, baseConfig, RuleExecutionContext(base))
            assertTrue(results.isEmpty())
            val missingOrigin = facts(listOf("a.A" to location(" ")))
            assertFailsWith<ParamError> { evaluator.evaluate(missingOrigin, def, baseConfig, RuleExecutionContext(base)) }
            val directAbsolute = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf(base.toString())))
            val absoluteOrigin = facts(listOf("a.A" to location(base.resolve("a/A.class").toString())))
            assertTrue(evaluator.evaluate(absoluteOrigin, directAbsolute, baseConfig).isEmpty())
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `graph threshold findings select included source anchors`() {
        val source =
            listOf(
                "a.Ignored" to location("build/classes/a/Ignored.class"),
                "b.Active" to location("build/classes/b/Active.class"),
                "c.Active" to location("build/classes/c/Active.class"),
            )
        val baseFacts = facts(source)
        val types = source.map { TypeRef.fromInternalName(it.first.replace('.', '/')) }
        val edges =
            listOf(1 to 2, 2 to 1).map { (a, b) ->
                DependencyEdge(types[a], types[b], DependencyKind.METHOD_CALL, source[a].second)
            }
        val selected =
            baseFacts.copy(
                edges = edges,
                classToRole = mapOf("a.Ignored" to "other", "b.Active" to "core", "c.Active" to "core"),
            )
        val cases =
            listOf(
                MaxCyclesRule() to rule("graph", "maxCycles", mapOf("maxCycles" to 0)),
                MaxEdgeCountRule() to rule("graph", "maxEdgeCount", mapOf("maxEdges" to 0)),
                MaxDependencyDensityRule() to rule("graph", "maxDependencyDensity", mapOf("maxDensity" to 0.0)),
            )
        for ((evaluator, definition) in cases) {
            val findings = evaluator.evaluate(selected, definition.copy(roles = listOf("core")), baseConfig)
            assertEquals(1, findings.size)
            assertEquals("b.Active", findings.single().classFqn)
        }
    }

    @Test
    fun `source anchor honors package scope and resolved bytecode locations`() {
        val entries =
            listOf(
                "a.Ignored" to location("build/classes/a/Ignored.class"),
                "b.Active" to location("build/classes/b/Active.class"),
                "c.Active" to location("build/classes/c/Active.class"),
            )
        val types = entries.map { TypeRef.fromInternalName(it.first.replace('.', '/')) }
        val graph =
            facts(entries).copy(
                edges =
                    listOf(1 to 2, 2 to 1).map { (a, b) ->
                        DependencyEdge(types[a], types[b], DependencyKind.METHOD_CALL, entries[a].second)
                    },
            )
        val scope =
            io.shamash.asm.core.config.schema.v1.model.RuleScope(
                includeRoles = null,
                excludeRoles = null,
                includePackages = listOf("^b$", "^c$"),
                excludePackages = null,
                includeGlobs = null,
                excludeGlobs = null,
            )
        val cases =
            listOf(
                MaxCyclesRule() to rule("graph", "maxCycles", mapOf("maxCycles" to 0)),
                MaxEdgeCountRule() to rule("graph", "maxEdgeCount", mapOf("maxEdges" to 0)),
                MaxDependencyDensityRule() to rule("graph", "maxDependencyDensity", mapOf("maxDensity" to 0.0)),
            )
        for ((evaluator, definition) in cases) {
            val actual = evaluator.evaluate(graph, definition.copy(scope = scope), baseConfig)
            assertEquals("b.Active", actual.single().classFqn)
        }
    }

    @Test
    fun `origin handles jar fallback and existing filesystem roots with symlink following enabled`() {
        val base = Files.createTempDirectory("shamash-origin-realpath")
        try {
            val root = base.resolve("allowed")
            Files.createDirectories(root)
            val cls = root.resolve("A.class")
            Files.write(cls, byteArrayOf())
            val jar = root.resolve("approved.jar")
            Files.write(jar, byteArrayOf())
            val config =
                baseConfig.copy(
                    project =
                        baseConfig.project.copy(
                            scan = baseConfig.project.scan.copy(followSymlinks = true),
                        ),
                )
            val policy = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf(root.toString())))
            val f =
                facts(
                    listOf(
                        "a.A" to location(cls.toString()),
                        "b.B" to SourceLocation(OriginKind.JAR_ENTRY, jar.toString()),
                    ),
                )
            assertTrue(AllowOnlyRootRule().evaluate(f, policy, config, RuleExecutionContext(base)).isEmpty())
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `origin root symlinks follow the configured scan policy`() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val project = Files.createTempDirectory("shamash-origin-link")
        try {
            val real = Files.createDirectories(project.resolve("real"))
            val alias = Files.createSymbolicLink(project.resolve("alias"), real)
            val bytecode = real.resolve("A.class")
            Files.write(bytecode, byteArrayOf())
            val definition = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf(alias.toString())))
            val f = facts(listOf("a.A" to location(bytecode.toString())))
            val following =
                baseConfig.copy(
                    project = baseConfig.project.copy(scan = baseConfig.project.scan.copy(followSymlinks = true)),
                )
            val notFollowing =
                baseConfig.copy(
                    project = baseConfig.project.copy(scan = baseConfig.project.scan.copy(followSymlinks = false)),
                )
            val context = RuleExecutionContext(project)
            assertTrue(AllowOnlyRootRule().evaluate(f, definition, following, context).isEmpty())
            assertEquals(1, AllowOnlyRootRule().evaluate(f, definition, notFollowing, context).size)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `origin native Windows path matching respects case and directory segments`() {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val project = Files.createTempDirectory("shamash-origin-windows")
        try {
            val root = project.resolve("allowed")
            val definition = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf(root.toString().uppercase())))
            val f =
                facts(
                    listOf(
                        "a.Inside" to location(root.resolve("nested/Inside.class").toString().lowercase()),
                        "b.Sibling" to location(project.resolve("allowed-sibling/Sibling.class").toString().lowercase()),
                    ),
                )
            val findings = AllowOnlyRootRule().evaluate(f, definition, baseConfig, RuleExecutionContext(project))
            assertEquals(listOf("b.Sibling"), findings.map { it.classFqn })
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `origin scope and unsupported filesystem paths are explicit`() {
        val project = Files.createTempDirectory("shamash-origin-scope")
        try {
            val def = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf(project.resolve("allowed").toString())))
            val outside =
                facts(
                    listOf(
                        "a.Ignored" to location(project.resolve("outside/A.class").toString()),
                        "b.Active" to location(project.resolve("outside/B.class").toString()),
                    ),
                ).copy(classToRole = mapOf("a.Ignored" to "other", "b.Active" to "core"))
            val evaluator = AllowOnlyRootRule()
            val context = RuleExecutionContext(project)
            val actual = evaluator.evaluate(outside, def.copy(roles = listOf("core")), baseConfig, context)
            assertEquals(listOf("b.Active"), actual.map { it.classFqn })

            val excludedInvalidOrigin =
                outside.copy(
                    classes =
                        listOf(
                            outside.classes.first().copy(location = location("\u0000")),
                            outside.classes.last(),
                        ),
                )
            val excludingPackage =
                def.copy(
                    scope =
                        io.shamash.asm.core.config.schema.v1.model.RuleScope(
                            includeRoles = null,
                            excludeRoles = null,
                            includePackages = null,
                            excludePackages = listOf("^a$"),
                            includeGlobs = null,
                            excludeGlobs = null,
                        ),
                )
            assertEquals(
                listOf("b.Active"),
                evaluator.evaluate(excludedInvalidOrigin, excludingPackage, baseConfig, context).map { it.classFqn },
            )

            val invalid = rule("origin", "allowOnlyRoot", mapOf("roots" to listOf("\u0000")))
            assertTrue(OriginAllowOnlyRootSpec().validate("rules[0]", invalid, baseConfig).isNotEmpty())
            assertFailsWith<ParamError> { evaluator.evaluate(FactIndex.empty(), invalid, baseConfig, context) }

            val invalidOrigin =
                outside.copy(
                    classes =
                        listOf(
                            outside.classes.first().copy(
                                location = location("\u0000"),
                            ),
                        ),
                )
            assertFailsWith<ParamError> { evaluator.evaluate(invalidOrigin, def, baseConfig, context) }
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun `origin params reject undocumented package aliases and malformed root arrays`() {
        val spec = OriginAllowOnlyRootSpec()
        for (params in listOf(
            mapOf("root" to "com.example"),
            mapOf("roots" to emptyList<String>()),
            mapOf("roots" to listOf(" ")),
            mapOf("roots" to "build/classes"),
            mapOf("roots" to listOf(7)),
            mapOf("roots" to listOf("build/classes"), "allowDefaultPackage" to true),
        )) {
            val r = rule("origin", "allowOnlyRoot", params)
            assertTrue(spec.validate("rules[0]", r, baseConfig).isNotEmpty())
            assertFailsWith<ParamError> { AllowOnlyRootRule().evaluate(FactIndex.empty(), r, baseConfig) }
        }
    }
}
