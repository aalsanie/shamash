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
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.config.validation.v1.specs.ApiMaxPublicTypesSpec
import io.shamash.asm.core.engine.rules.api.MaxPublicTypesRule
import io.shamash.asm.core.engine.rules.graph.MaxCyclesRule
import io.shamash.asm.core.engine.rules.graph.MaxDependencyDensityRule
import io.shamash.asm.core.engine.rules.graph.MaxEdgeCountRule
import io.shamash.asm.core.engine.rules.graph.NoCyclesRule
import io.shamash.asm.core.facts.FactExtractor
import io.shamash.asm.core.facts.bytecode.BytecodeUnit
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphAndTypeScopeTest {
    @Test
    fun `graph rules use configured granularity even when summaries are disabled`() {
        val facts = graph(listOf("a.A" to "a.B", "a.B" to "a.A"))
        val classConfig = configWithGraphs(Granularity.CLASS)
        val packageConfig = configWithGraphs(Granularity.PACKAGE)
        val rules =
            listOf(
                NoCyclesRule() to rule("graph", "noCycles", emptyMap()),
                MaxCyclesRule() to rule("graph", "maxCycles", mapOf("maxCycles" to 0)),
                MaxEdgeCountRule() to rule("graph", "maxEdgeCount", mapOf("maxEdges" to 0)),
                MaxDependencyDensityRule() to rule("graph", "maxDependencyDensity", mapOf("maxDensity" to 0.0)),
            )
        for ((evaluator, definition) in rules) {
            assertTrue(evaluator.evaluate(facts, definition, classConfig).isNotEmpty(), evaluator.id)
            assertTrue(evaluator.evaluate(facts, definition, packageConfig).isEmpty(), evaluator.id)
        }
    }

    @Test
    fun `external buckets are governed by graph settings`() {
        val facts = graph(listOf("a.A" to "external.B"))
        val off = configWithGraphs(Granularity.CLASS, includeExternalBuckets = false)
        val on = configWithGraphs(Granularity.CLASS, includeExternalBuckets = true)
        val rules =
            listOf(
                MaxEdgeCountRule() to rule("graph", "maxEdgeCount", mapOf("maxEdges" to 0)),
                MaxDependencyDensityRule() to rule("graph", "maxDependencyDensity", mapOf("maxDensity" to 0.0)),
            )
        for ((evaluator, definition) in rules) {
            assertTrue(evaluator.evaluate(facts, definition, off).isEmpty(), evaluator.id)
            assertEquals(1, evaluator.evaluate(facts, definition, on).size, evaluator.id)
        }
    }

    @Test
    fun `maximum cycles counts cyclic components and is order invariant`() {
        val facts = graph(listOf("a.A" to "b.B", "b.B" to "a.A", "c.C" to "d.D", "d.D" to "c.C"))
        val config = configWithGraphs(Granularity.CLASS)
        val definition = rule("graph", "maxCycles", mapOf("maxCycles" to 1))
        val actual = MaxCyclesRule().evaluate(facts, definition, config).single()
        assertEquals("2", actual.data["count"])
        assertEquals(
            actual,
            MaxCyclesRule()
                .evaluate(
                    facts.copy(classes = facts.classes.reversed(), edges = facts.edges.reversed()),
                    definition,
                    config,
                ).single(),
        )
        assertTrue(MaxCyclesRule().evaluate(facts, definition.copy(params = mapOf("maxCycles" to 2)), config).isEmpty())
    }

    @Test
    fun `public type switches use JVM metadata and preserve legal dollar names`() {
        val outer = "sample/Outer"
        val nested = outer + "$" + "Nested"
        val local = outer + "$" + "1Local"
        val dollar = "sample/Top" + "$" + "Dollar"
        val units =
            listOf(
                bytecode(outer, Opcodes.ACC_PUBLIC),
                bytecode(nested, Opcodes.ACC_PUBLIC, outer, "Nested"),
                bytecode(local, Opcodes.ACC_PUBLIC, outer, "Local", localClass = true),
                bytecode(dollar, Opcodes.ACC_PUBLIC),
                bytecode("sample/Contract", Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT),
                bytecode("sample/Kind", Opcodes.ACC_PUBLIC or Opcodes.ACC_ENUM),
                bytecode(
                    "sample/Tagged",
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_ANNOTATION or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
                ),
            )
        val extraction = FactExtractor.extractAll(units.asSequence())
        assertTrue(extraction.errors.isEmpty(), extraction.errors.toString())
        val facts = extraction.facts
        assertEquals(7, facts.classes.size)
        assertTrue(facts.classes.single { it.type.internalName == nested }.isNestedType)
        assertTrue(facts.classes.single { it.type.internalName == local }.isNestedType)
        assertFalse(facts.classes.single { it.type.internalName == dollar }.isNestedType)

        val orphan =
            FactExtractor.extractResult(
                bytecode("sample/Orphan" + "$" + "Nested", Opcodes.ACC_PUBLIC, inner = "Nested"),
            )
        assertTrue(orphan.errors.isEmpty(), orphan.errors.toString())
        assertTrue(orphan.facts.classes.single().isNestedType)

        val spec = ApiMaxPublicTypesSpec()
        val checks =
            listOf(
                emptyMap<String, Any?>() to 7,
                mapOf("includeNested" to false) to 5,
                mapOf("includeInterfaces" to false) to 6,
                mapOf("includeEnums" to false) to 6,
                mapOf("includeAnnotations" to false) to 6,
                mapOf(
                    "includeNested" to false,
                    "includeInterfaces" to false,
                    "includeEnums" to false,
                    "includeAnnotations" to false,
                ) to 2,
            )
        for ((options, count) in checks) {
            val definition = rule("api", "maxPublicTypes", options + ("max" to 0))
            assertTrue(spec.validate("rules[0]", definition, config()).isEmpty(), options.toString())
            val finding = MaxPublicTypesRule().evaluate(facts, definition, config()).single()
            assertEquals(count.toString(), finding.data["count"], options.toString())
            assertTrue(
                MaxPublicTypesRule()
                    .evaluate(
                        facts,
                        definition.copy(params = options + ("max" to count)),
                        config(),
                    ).isEmpty(),
                options.toString(),
            )
        }

        val invalidOptions =
            listOf(
                mapOf("max" to 0, "includeNested" to "maybe"),
                mapOf("max" to 0, "includeInterfaces" to "maybe"),
                mapOf("max" to 0, "includeEnums" to "maybe"),
                mapOf("max" to 0, "includeAnnotations" to "maybe"),
                mapOf("max" to 0, "extra" to true),
                mapOf("max" to -1),
                mapOf("max" to "not-a-number"),
                emptyMap(),
            )
        for (invalid in invalidOptions) {
            val definition = rule("api", "maxPublicTypes", invalid)
            assertTrue(spec.validate("rules[0]", definition, config()).isNotEmpty())
            assertFailsWith<ParamError> {
                MaxPublicTypesRule().evaluate(facts, definition, config())
            }
        }
    }

    private fun bytecode(
        internal: String,
        access: Int,
        outer: String? = null,
        inner: String? = null,
        localClass: Boolean = false,
    ): BytecodeUnit {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, access, internal, null, "java/lang/Object", null)
        if (outer != null || inner != null) writer.visitInnerClass(internal, outer, inner, access)
        if (internal.contains("Top" + "$" + "Dollar")) {
            writer.visitInnerClass("sample/Outer" + "$" + "Nested", "sample/Outer", "Nested", access)
        }
        if (localClass) writer.visitOuterClass(outer, "create", "()V")
        writer.visitEnd()
        val location = SourceLocation(OriginKind.DIR_CLASS, "classes/" + internal + ".class")
        return BytecodeUnit(writer.toByteArray(), location, location.originPath)
    }

    private fun graph(edges: List<Pair<String, String>>): FactIndex {
        val sourceNames = edges.map { it.first }.distinct()
        val classes =
            sourceNames.map { name ->
                ClassFact(
                    type(name),
                    Opcodes.ACC_PUBLIC,
                    null,
                    emptySet(),
                    emptySet(),
                    false,
                    SourceLocation(OriginKind.DIR_CLASS, "classes/" + name.replace('.', '/') + ".class"),
                )
            }
        val dependencies =
            edges.map { (from, to) ->
                DependencyEdge(
                    type(from),
                    type(to),
                    DependencyKind.METHOD_CALL,
                    classes.first { it.fqName == from }.location,
                )
            }
        return FactIndex(classes, emptyList(), emptyList(), dependencies, emptyMap(), emptyMap())
    }

    private fun type(name: String): TypeRef = TypeRef.fromInternalName(name.replace('.', '/'))

    private fun rule(type: String, name: String, params: Map<String, Any?>): RuleDef =
        RuleDef(type, name, null, true, FindingSeverity.ERROR, null, params)

    private fun configWithGraphs(
        granularity: Granularity,
        includeExternalBuckets: Boolean = false,
    ): ShamashAsmConfigV1 {
        val config = config()
        return config.copy(
            analysis =
                config.analysis.copy(
                    graphs =
                        config.analysis.graphs.copy(
                            enabled = false,
                            granularity = granularity,
                            includeExternalBuckets = includeExternalBuckets,
                        ),
                ),
        )
    }

    private fun config(): ShamashAsmConfigV1 {
        val yaml = SchemaResources.openReferenceYaml().reader().use { it.readText() }
        return assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(yaml)).config)
    }
}
