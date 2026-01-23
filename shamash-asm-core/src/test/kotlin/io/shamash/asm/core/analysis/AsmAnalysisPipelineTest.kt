/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
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
package io.shamash.asm.core.analysis

import io.shamash.asm.core.config.schema.v1.model.AnalysisConfig
import io.shamash.asm.core.config.schema.v1.model.GodClassScoringConfig
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.GraphsConfig
import io.shamash.asm.core.config.schema.v1.model.HotspotsConfig
import io.shamash.asm.core.config.schema.v1.model.OverallScoringConfig
import io.shamash.asm.core.config.schema.v1.model.ScoreModel
import io.shamash.asm.core.config.schema.v1.model.ScoreThresholds
import io.shamash.asm.core.config.schema.v1.model.ScoringConfig
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsmAnalysisPipelineTest {
    @Test
    fun `graphs - emits deterministic adjacency and cyclic SCCs`() {
        val facts = tinyFactsGraph()
        val cfg =
            AnalysisConfig(
                graphs = GraphsConfig(enabled = true, granularity = Granularity.CLASS, includeExternalBuckets = true),
                hotspots = HotspotsConfig(enabled = false, topN = 5, includeExternal = false),
                scoring =
                    ScoringConfig(
                        enabled = false,
                        model = ScoreModel.V1,
                        godClass = GodClassScoringConfig(enabled = false, weights = null, thresholds = null),
                        overall = OverallScoringConfig(enabled = false, weights = null, thresholds = null),
                    ),
            )

        val out = AsmAnalysisPipeline.run(facts, cfg)
        val graphs = assertNotNull(out.graphs)

        // Nodes are sorted (external bucket should be present)
        assertEquals(listOf("__external__:ext", "com.example.A", "com.example.B", "com.example.C"), graphs.nodes)

        // Deterministic adjacency ordering
        assertEquals(
            listOf("__external__:ext", "com.example.B", "com.example.C"),
            graphs.adjacency.getValue("com.example.A"),
        )
        assertEquals(listOf("com.example.A"), graphs.adjacency.getValue("com.example.B"))

        // Cyclic SCC includes A<->B (sorted SCC list)
        assertTrue(graphs.cyclicSccs.any { it == listOf("com.example.A", "com.example.B") })
        assertTrue(graphs.sccCount >= 3)
    }

    @Test
    fun `hotspots - aggregates reasons across metrics`() {
        val facts = tinyFactsGraph()
        val cfg =
            AnalysisConfig(
                graphs = GraphsConfig(enabled = false, granularity = Granularity.PACKAGE, includeExternalBuckets = false),
                hotspots = HotspotsConfig(enabled = true, topN = 2, includeExternal = false),
                scoring =
                    ScoringConfig(
                        enabled = false,
                        model = ScoreModel.V1,
                        godClass = GodClassScoringConfig(enabled = false, weights = null, thresholds = null),
                        overall = OverallScoringConfig(enabled = false, weights = null, thresholds = null),
                    ),
            )

        val out = AsmAnalysisPipeline.run(facts, cfg)
        val hs = assertNotNull(out.hotspots)

        // A has highest fanOut and methodCount in this fixture.
        val a = hs.classHotspots.firstOrNull { it.id == "com.example.A" }
        assertNotNull(a)
        assertTrue(a.reasons.any { it.metric == HotspotMetric.FAN_OUT })
        assertTrue(a.reasons.any { it.metric == HotspotMetric.METHOD_COUNT })
    }

    @Test
    fun `scoring - produces stable score rows and severity bands`() {
        val facts = tinyFactsGraph()
        val cfg =
            AnalysisConfig(
                graphs = GraphsConfig(enabled = false, granularity = Granularity.PACKAGE, includeExternalBuckets = false),
                hotspots = HotspotsConfig(enabled = false, topN = 5, includeExternal = false),
                scoring =
                    ScoringConfig(
                        enabled = true,
                        model = ScoreModel.V1,
                        godClass =
                            GodClassScoringConfig(
                                enabled = true,
                                weights = null,
                                thresholds = ScoreThresholds(warning = 0.2, error = 0.4),
                            ),
                        overall =
                            OverallScoringConfig(
                                enabled = true,
                                weights = null,
                                thresholds = ScoreThresholds(warning = 0.2, error = 0.4),
                            ),
                    ),
            )

        val out = AsmAnalysisPipeline.run(facts, cfg)
        val scoring = assertNotNull(out.scoring)
        val god = assertNotNull(scoring.godClass)

        // A should rank first by score due to highest methods+fanOut.
        assertEquals("com.example.A", god.rows.first().classFqn)
        assertTrue(god.rows.first().score >= god.rows.last().score)
        assertTrue(god.rows.first().band == SeverityBand.WARN || god.rows.first().band == SeverityBand.ERROR)

        val overall = assertNotNull(scoring.overall)
        assertTrue(overall.rows.isNotEmpty())
        assertTrue(overall.rows.all { it.score in 0.0..1.0 })
    }

    private fun tinyFactsGraph(): FactIndex {
        val a = cls("com/example/A", originPath = "src/main/kotlin/com/example/A.kt")
        val b = cls("com/example/B", originPath = "src/main/kotlin/com/example/B.kt")
        val c = cls("com/example/C", originPath = "src/main/kotlin/com/example/C.kt")
        val ext = TypeRef.fromInternalName("ext/Lib")

        val edges =
            listOf(
                dep(a.type, b.type),
                dep(b.type, a.type),
                dep(a.type, c.type),
                // external edge (should be bucketed as __external__:ext)
                dep(a.type, ext),
            )

        val methods =
            listOf(
                m(a.type, "m1"),
                m(a.type, "m2"),
                m(a.type, "m3"),
                m(b.type, "m1"),
                m(c.type, "m1"),
            )

        val fields =
            listOf(
                f(a.type, "f1"),
                f(a.type, "f2"),
                f(b.type, "f1"),
            )

        return FactIndex(
            classes = listOf(a, b, c),
            methods = methods,
            fields = fields,
            edges = edges,
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private fun cls(
        internalName: String,
        originPath: String,
    ): ClassFact {
        val t = TypeRef.fromInternalName(internalName)
        return ClassFact(
            type = t,
            access = 0,
            superType = null,
            interfaces = emptySet(),
            annotationsFqns = emptySet(),
            hasMainMethod = false,
            location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = originPath, line = null),
        )
    }

    private fun dep(
        from: TypeRef,
        to: TypeRef,
    ): DependencyEdge =
        DependencyEdge(
            from = from,
            to = to,
            kind = DependencyKind.METHOD_CALL,
            location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = "", line = null),
        )

    private fun m(
        owner: TypeRef,
        name: String,
    ): MethodRef =
        MethodRef(
            owner = owner,
            name = name,
            desc = "()V",
            signature = null,
            access = 0,
            isConstructor = false,
            returnType = null,
            parameterTypes = emptyList(),
            throwsTypes = emptyList(),
            annotationsFqns = emptySet(),
            location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = "", line = null),
        )

    private fun f(
        owner: TypeRef,
        name: String,
    ): FieldRef =
        FieldRef(
            owner = owner,
            name = name,
            desc = "Ljava/lang/String;",
            signature = null,
            access = 0,
            fieldType = null,
            annotationsFqns = emptySet(),
            location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = "", line = null),
        )
}
