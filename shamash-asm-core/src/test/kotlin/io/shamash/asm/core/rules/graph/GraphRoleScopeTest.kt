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

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.RuleScope
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.rules.Rule
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.engine.rules.graph.MaxCyclesRule
import io.shamash.asm.core.engine.rules.graph.MaxDependencyDensityRule
import io.shamash.asm.core.engine.rules.graph.MaxEdgeCountRule
import io.shamash.asm.core.engine.rules.graph.NoCyclesRule
import io.shamash.asm.core.engine.rules.metrics.MaxFanInRule
import io.shamash.asm.core.engine.rules.metrics.MaxFanOutRule
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

class GraphRoleScopeTest {
    @Test
    fun roleRestrictionsApplyToAllGraphAndMetricRules() {
        val config = referenceConfig()
        val facts =
            facts(
                roles = mapOf("core.A" to "core", "api.B" to "api"),
                dependencies = listOf("core.A" to "api.B", "api.B" to "core.A"),
            )
        val cases: List<Pair<Rule, Map<String, Any?>>> =
            listOf(
                NoCyclesRule() to mapOf("granularity" to "class"),
                MaxCyclesRule() to mapOf("max" to 0, "granularity" to "class"),
                MaxEdgeCountRule() to mapOf("max" to 0, "granularity" to "class"),
                MaxDependencyDensityRule() to mapOf("max" to 0.0, "granularity" to "class"),
                MaxFanInRule() to mapOf("max" to 0, "granularity" to "class"),
                MaxFanOutRule() to mapOf("max" to 0, "granularity" to "class"),
            )

        for ((evaluator, params) in cases) {
            val unrestricted =
                rule(
                    type = evaluator.id.substringBefore('.'),
                    name = evaluator.id.substringAfter('.'),
                    params = params,
                )
            val restricted =
                listOf(
                    unrestricted.copy(roles = listOf("core")),
                    unrestricted.copy(scope = roleScope(includeRoles = listOf("core"))),
                    unrestricted.copy(scope = roleScope(excludeRoles = listOf("api"))),
                )

            assertTrue(
                evaluator.evaluate(facts, unrestricted, config).isNotEmpty(),
                "${evaluator.id} should detect unrestricted dependencies",
            )
            for (scoped in restricted) {
                assertTrue(
                    evaluator.evaluate(facts, scoped, config).isEmpty(),
                    "${evaluator.id} ignored the scoped source roles",
                )
            }
        }
    }

    @Test
    fun packageGraphExcludesEdgesFromDisallowedClassesSharingAnIncludedNode() {
        val facts =
            facts(
                roles =
                    mapOf(
                        "alpha.Core" to "core",
                        "alpha.Api" to "api",
                        "beta.Core" to "core",
                        "beta.Api" to "api",
                    ),
                dependencies =
                    listOf(
                        "alpha.Core" to "beta.Api",
                        "beta.Api" to "alpha.Core",
                        "alpha.Api" to "beta.Core",
                    ),
            )
        val unrestricted = dependencyGraph(facts, rule("graph", "noCycles", mapOf("granularity" to "package")))
        val scoped =
            dependencyGraph(
                facts,
                rule("graph", "noCycles", mapOf("granularity" to "package"), roles = listOf("core")),
            )

        assertEquals(2, unrestricted.edgeCount)
        assertEquals(1, scoped.edgeCount)
        assertEquals(setOf("alpha", "beta"), scoped.nodes)
        assertEquals(setOf("beta"), scoped.successors("alpha"))
        assertTrue(scoped.successors("beta").isEmpty())
        assertEquals(1, RuleUtil.cyclicComponents(unrestricted).size)
        assertTrue(RuleUtil.cyclicComponents(scoped).isEmpty())
    }

    @Test
    fun analysisGraphWithoutRuleRetainsItsUnrestrictedDependencies() {
        val facts =
            facts(
                roles = mapOf("core.A" to "core", "api.B" to "api"),
                dependencies = listOf("core.A" to "api.B", "api.B" to "core.A"),
            )

        val graph =
            RuleUtil.buildDependencyGraph(
                facts = facts,
                granularity = Granularity.CLASS,
                includeExternalBuckets = false,
            )

        assertEquals(2, graph.edgeCount)
        assertEquals(1, RuleUtil.cyclicComponents(graph).size)
    }

    private fun dependencyGraph(
        facts: FactIndex,
        rule: RuleDef,
    ): RuleUtil.DirectedGraph =
        RuleUtil.buildDependencyGraph(
            facts = facts,
            granularity = Granularity.PACKAGE,
            includeExternalBuckets = false,
            scope = RuleUtil.compileScope(rule.scope),
            rule = rule,
        )

    private fun roleScope(
        includeRoles: List<String>? = null,
        excludeRoles: List<String>? = null,
    ): RuleScope =
        RuleScope(
            includeRoles = includeRoles,
            excludeRoles = excludeRoles,
            includePackages = null,
            excludePackages = null,
            includeGlobs = null,
            excludeGlobs = null,
        )

    private fun rule(
        type: String,
        name: String,
        params: Map<String, Any?>,
        roles: List<String>? = null,
    ): RuleDef =
        RuleDef(
            type = type,
            name = name,
            roles = roles,
            enabled = true,
            severity = FindingSeverity.ERROR,
            scope = null,
            params = params,
        )

    private fun referenceConfig(): ShamashAsmConfigV1 {
        val yaml = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }
        return assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(yaml)).config)
    }

    private fun facts(
        roles: Map<String, String>,
        dependencies: List<Pair<String, String>>,
    ): FactIndex {
        val classes =
            roles.keys.map { fqName ->
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

        return FactIndex(
            classes = classes,
            methods = emptyList(),
            fields = emptyList(),
            edges =
                dependencies.map { (from, to) ->
                    DependencyEdge(
                        from = type(from),
                        to = type(to),
                        kind = DependencyKind.METHOD_CALL,
                        location = classes.first { it.fqName == from }.location,
                    )
                },
            roles = roles.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() },
            classToRole = roles,
        )
    }

    private fun type(fqName: String): TypeRef = TypeRef.fromInternalName(fqName.replace('.', '/'))
}
