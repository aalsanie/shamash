/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.engine.rules.ContextAwareRule
import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.RuleExecutionContext
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShippedRuleBehaviorTest {
    private val registry = DefaultRuleRegistry.create()

    @Test
    fun `every shipped ASM rule example produces a finding on violating facts and none on clean facts`() {
        val root = repositoryRoot()
        val dir = root.resolve("docs/asm/schema/v1/examples")
        val files = Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".yml") }.sorted().toList()
        }
        for (file in files) {
            val number = file.fileName.toString().substringBefore('_').toInt()
            val validation = Files.newBufferedReader(file).use { ConfigValidation.loadAndValidateV1(it, registry) }
            assertTrue(validation.ok, "$file: ${validation.errors}")
            val config = assertNotNull(validation.config, file.toString())
            val sample = violatingFacts(number)
            for (definition in config.rules.filter { it.enabled }) {
                val id = "${definition.type}.${definition.name}"
                val rule = assertNotNull(registry.byId(id), "$file: no rule $id")
                val actual =
                    if (rule is ContextAwareRule) {
                        rule.evaluate(sample, definition, config, RuleExecutionContext(root))
                    } else {
                        rule.evaluate(sample, definition, config)
                    }
                assertTrue(actual.isNotEmpty(), "$file: $id did not detect a deliberately violating fixture")
                val clean =
                    if (rule is ContextAwareRule) {
                        rule.evaluate(FactIndex.empty(), definition, config, RuleExecutionContext(root))
                    } else {
                        rule.evaluate(FactIndex.empty(), definition, config)
                    }
                assertTrue(clean.isEmpty(), "$file: $id reported a violation without any facts")
            }
        }
    }

    private fun violatingFacts(number: Int): FactIndex =
        when (number) {
            1 -> withEdges(
                listOf("demo.api.Entry", "demo.domain.Model"),
                listOf(0 to 1),
                mapOf(0 to "api", 1 to "domain"),
            )
            2 -> withEdges(
                listOf("demo.api.Entry", "demo.persistence.Store"),
                listOf(0 to 1),
                mapOf(0 to "api", 1 to "persistence"),
            )
            3 -> withEdges(listOf("com.acme.legacy.Legacy"), emptyList(), mapOf(0 to "legacy"))
            4 -> withEdges(listOf("com.acme.unknown.Unsafe"), emptyList(), emptyMap())
            5, 6, 18 -> withEdges(listOf("example.left.A", "example.right.B"), listOf(0 to 1, 1 to 0), emptyMap())
            7 -> {
                val names = listOf("example.source.Root") + (0 until 501).map { "example.target$it.Node" }
                withEdges(names, (1 until names.size).map { 0 to it }, emptyMap())
            }
            8 -> {
                val facts = withEdges(listOf("com.acme.core.Busy"), emptyList(), emptyMap())
                val owner = facts.classes.single()
                facts.copy(methods = (0..80).map { i ->
                    MethodRef(
                        owner.type, "method$i", "()V", null, Opcodes.ACC_PUBLIC, false,
                        null, emptyList(), emptyList(), emptySet(), owner.location,
                    )
                })
            }
            9 -> {
                val facts = withEdges(listOf("com.example.app.Large"), emptyList(), emptyMap())
                val owner = facts.classes.single()
                facts.copy(fields = (0..30).map { i ->
                    FieldRef(owner.type, "field$i", "I", null, Opcodes.ACC_PUBLIC, null, emptySet(), owner.location)
                })
            }
            10, 12 -> {
                val names = listOf("com.acme.core.Hub") + (0 until 31).map { "example.target$it.Node" }
                withEdges(names, (1 until names.size).map { 0 to it }, mapOf(0 to "core"))
            }
            11 -> {
                val names = listOf("com.acme.core.Hub") + (0 until 41).map { "example.source$it.Node" }
                withEdges(names, (1 until names.size).map { it to 0 }, names.indices.associateWith { "core" })
            }
            13 -> withEdges((0..50).map { "com.acme.api.Type$it" }, emptyList(), (0..50).associateWith { "api" })
            14 -> withEdges(listOf("com.acme.api.Outer" + '$' + "Companion"), emptyList(), mapOf(0 to "api"))
            15 -> {
                val facts = withEdges(listOf("example.Annotated"), emptyList(), emptyMap())
                facts.copy(classes = facts.classes.map { it.copy(annotationsFqns = setOf("javax.annotation.Generated")) })
            }
            16 -> {
                val facts = withEdges(listOf("example.Source", "example.Target"), listOf(0 to 1), emptyMap())
                facts.copy(classes = facts.classes.mapIndexed { i, klass ->
                    val jar = if (i == 0) "lib/my-app-1.jar" else "lib/guava-1.jar"
                    klass.copy(location = SourceLocation(OriginKind.JAR_ENTRY, jar, jar))
                })
            }
            17 -> {
                val facts = withEdges(listOf("example.Outside"), emptyList(), emptyMap())
                facts.copy(classes = facts.classes.map {
                    it.copy(location = SourceLocation(OriginKind.DIR_CLASS, "unexpected/Outside.class"))
                })
            }
            else -> error("Missing example behavior fixture $number")
        }

    private fun withEdges(
        names: List<String>,
        connections: List<Pair<Int, Int>>,
        rolesByIndex: Map<Int, String>,
    ): FactIndex {
        val classes =
            names.map { fqName ->
                ClassFact(
                    TypeRef.fromInternalName(fqName.replace('.', '/')),
                    Opcodes.ACC_PUBLIC,
                    null,
                    emptySet(),
                    emptySet(),
                    false,
                    SourceLocation(OriginKind.DIR_CLASS, "build/classes/" + fqName.replace('.', '/') + ".class"),
                )
            }
        val edges = connections.map { (from, to) ->
            DependencyEdge(classes[from].type, classes[to].type, DependencyKind.METHOD_CALL, classes[from].location)
        }
        val classToRole = rolesByIndex.mapKeys { (idx, _) -> classes[idx].fqName }
        return FactIndex(
            classes,
            emptyList(),
            emptyList(),
            edges,
            classToRole.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() },
            classToRole,
        )
    }

    private fun repositoryRoot(): Path =
        generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            ?: error("Cannot locate project root for example fixtures")
}
