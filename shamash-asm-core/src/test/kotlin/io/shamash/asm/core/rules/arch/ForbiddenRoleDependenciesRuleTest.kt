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
package io.shamash.asm.core.rules.arch

import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.arch.ForbiddenRoleDependenciesRule
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ForbiddenRoleDependenciesRuleTest {
    private val evaluator = ForbiddenRoleDependenciesRule()

    @Test
    fun `validated reference config enforces direct forbidden role dependency`() {
        val config = validatedReferenceConfig()
        val rule = forbiddenRoleRule(config)

        val facts =
            facts(
                edges = listOf(CONTROLLER to REPOSITORY),
            )

        val findings =
            evaluator.evaluate(
                facts = facts,
                rule = rule,
                config = config,
            )

        assertEquals(1, findings.size)

        val finding = findings.single()

        assertEquals("arch.forbiddenRoleDependencies", finding.ruleId)
        assertEquals("controller", finding.data["fromRole"])
        assertEquals("repository", finding.data["toRole"])
        assertEquals("direct", finding.data["mode"])
        assertEquals(
            "Forbidden role dependency observed: 'controller' -> 'repository'.",
            finding.message,
        )
        assertEquals(CONTROLLER, finding.classFqn)
    }

    @Test
    fun `transitive direction detects forbidden dependency through intermediate role`() {
        val config = validatedReferenceConfig()

        val rule =
            forbiddenRoleRule(config).copy(
                params =
                    mapOf(
                        "direction" to "transitive",
                        "forbidden" to
                            mapOf(
                                "controller" to listOf("repository"),
                            ),
                    ),
            )

        val facts =
            facts(
                edges =
                    listOf(
                        CONTROLLER to SERVICE,
                        SERVICE to REPOSITORY,
                    ),
            )

        val findings =
            evaluator.evaluate(
                facts = facts,
                rule = rule,
                config = config,
            )

        assertEquals(1, findings.size)

        val finding = findings.single()

        assertEquals("controller", finding.data["fromRole"])
        assertEquals("repository", finding.data["toRole"])
        assertEquals("transitive", finding.data["mode"])
        assertEquals(
            "controller -> service -> repository",
            finding.data["path"],
        )
        assertEquals(
            "Forbidden transitive role dependency observed: 'controller' -> 'repository'.",
            finding.message,
        )

        assertEquals(CONTROLLER, finding.classFqn)
    }

    @Test
    fun `direct direction does not treat transitive path as direct dependency`() {
        val config = validatedReferenceConfig()
        val rule = forbiddenRoleRule(config)

        val facts =
            facts(
                edges =
                    listOf(
                        CONTROLLER to SERVICE,
                        SERVICE to REPOSITORY,
                    ),
            )

        val findings =
            evaluator.evaluate(
                facts = facts,
                rule = rule,
                config = config,
            )

        assertTrue(findings.isEmpty())
    }

    private fun validatedReferenceConfig(): ShamashAsmConfigV1 {
        val yaml =
            SchemaResources
                .openReferenceYaml()
                .use { it.reader(Charsets.UTF_8).readText() }

        val result =
            ConfigValidation.loadAndValidateV1(
                StringReader(yaml),
            )

        assertTrue(
            result.ok,
            "reference config must validate before testing evaluator contract: ${result.errors}",
        )

        return assertNotNull(result.config)
    }

    private fun forbiddenRoleRule(config: ShamashAsmConfigV1): RuleDef =
        config.rules.single {
            it.type == "arch" &&
                it.name == "forbiddenRoleDependencies"
        }

    private fun facts(edges: List<Pair<String, String>>): FactIndex {
        val controller = classFact(CONTROLLER)
        val service = classFact(SERVICE)
        val repository = classFact(REPOSITORY)

        val classes =
            listOf(
                controller,
                service,
                repository,
            )

        val classesByFqn =
            classes.associateBy { it.fqName }

        return FactIndex(
            classes = classes,
            methods = emptyList(),
            fields = emptyList(),
            edges =
                edges.map { (from, to) ->
                    dependency(
                        from = classesByFqn.getValue(from).type,
                        to = classesByFqn.getValue(to).type,
                    )
                },
            roles =
                mapOf(
                    "controller" to setOf(CONTROLLER),
                    "service" to setOf(SERVICE),
                    "repository" to setOf(REPOSITORY),
                ),
            classToRole =
                mapOf(
                    CONTROLLER to "controller",
                    SERVICE to "service",
                    REPOSITORY to "repository",
                ),
        )
    }

    private fun classFact(fqName: String): ClassFact {
        val internalName = fqName.replace('.', '/')

        return ClassFact(
            type = TypeRef.fromInternalName(internalName),
            access = 0,
            superType = null,
            interfaces = emptySet(),
            annotationsFqns = emptySet(),
            hasMainMethod = false,
            location =
                SourceLocation(
                    originKind = OriginKind.DIR_CLASS,
                    originPath = "src/main/kotlin/$internalName.kt",
                    line = null,
                ),
        )
    }

    private fun dependency(
        from: TypeRef,
        to: TypeRef,
    ): DependencyEdge =
        DependencyEdge(
            from = from,
            to = to,
            kind = DependencyKind.METHOD_CALL,
            location =
                SourceLocation(
                    originKind = OriginKind.DIR_CLASS,
                    originPath = "",
                    line = null,
                ),
        )

    private companion object {
        const val CONTROLLER = "com.example.Controller"
        const val SERVICE = "com.example.Service"
        const val REPOSITORY = "com.example.Repository"
    }
}
