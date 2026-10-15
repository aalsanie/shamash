/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.asm.core.rules.graph

import io.shamash.artifacts.baseline.BaselineCoordinator
import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.engine.rules.graph.NoCyclesRule
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoCyclesRuleTest {
    @Test
    fun `unrelated cycle does not invalidate an existing baseline`() {
        val yaml = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }
        val config = assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(yaml)).config)
        val rule =
            RuleDef(
                type = "graph",
                name = "noCycles",
                roles = null,
                enabled = true,
                severity = FindingSeverity.ERROR,
                scope = null,
                params = mapOf("granularity" to "class"),
            )
        val evaluator = NoCyclesRule()
        val original = evaluator.evaluate(facts(listOf("z.A" to "z.B", "z.B" to "z.A")), rule, config)
        val expanded =
            evaluator.evaluate(
                facts(listOf("z.A" to "z.B", "z.B" to "z.A", "a.A" to "a.B", "a.B" to "a.A")),
                rule,
                config,
            )

        assertEquals(1, original.size)
        assertEquals(2, expanded.size)

        val unchanged = expanded.single { it.data["cycle"] == original.single().data["cycle"] }
        assertEquals("1", original.single().data["cycleIndex"])
        assertEquals("2", unchanged.data["cycleIndex"])
        assertEquals("1", original.single().data["cycleCount"])
        assertEquals("2", unchanged.data["cycleCount"])
        assertNotEquals(original.single().filePath, unchanged.filePath)

        val project = Files.createTempDirectory("shamash-cycle-baseline")
        try {
            val baseline = BaselineCoordinator()
            val fingerprints = baseline.computeFingerprints(project, original)
            val suppression = assertNotNull(baseline.createSuppressionPreprocessor(fingerprints))
            val remaining = suppression.process(project, expanded)

            assertEquals(1, remaining.size)
            assertTrue(remaining.single().data["cycle"] != unchanged.data["cycle"])
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    private fun facts(dependencies: List<Pair<String, String>>): FactIndex {
        val classes =
            dependencies
                .flatMap { listOf(it.first, it.second) }
                .distinct()
                .map { fqName ->
                    ClassFact(
                        type = type(fqName),
                        access = 0,
                        superType = null,
                        interfaces = emptySet(),
                        annotationsFqns = emptySet(),
                        hasMainMethod = false,
                        location =
                            SourceLocation(
                                originKind = OriginKind.DIR_CLASS,
                                originPath = "classes/${fqName.replace('.', '/')}.class",
                            ),
                    )
                }
        val edges =
            dependencies.map { (from, to) ->
                DependencyEdge(
                    from = type(from),
                    to = type(to),
                    kind = DependencyKind.METHOD_CALL,
                    location = classes.first { it.fqName == from }.location,
                )
            }
        return FactIndex(
            classes = classes,
            methods = emptyList(),
            fields = emptyList(),
            edges = edges,
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private fun type(fqName: String): TypeRef = TypeRef.fromInternalName(fqName.replace('.', '/'))
}
