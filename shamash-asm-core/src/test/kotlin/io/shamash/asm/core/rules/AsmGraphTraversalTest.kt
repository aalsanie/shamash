/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.rules

import io.shamash.artifacts.contract.FindingSeverity
import io.shamash.artifacts.report.layout.ExportOutputLayout
import io.shamash.asm.core.analysis.AsmAnalysisPipeline
import io.shamash.asm.core.config.ConfigValidation
import io.shamash.asm.core.config.SchemaResources
import io.shamash.asm.core.config.schema.v1.model.BaselineMode
import io.shamash.asm.core.config.schema.v1.model.ExportAnalysisArtifactsConfig
import io.shamash.asm.core.config.schema.v1.model.ExportArtifactsConfig
import io.shamash.asm.core.config.schema.v1.model.ExportFormat
import io.shamash.asm.core.config.schema.v1.model.Granularity
import io.shamash.asm.core.config.schema.v1.model.RuleDef
import io.shamash.asm.core.config.schema.v1.model.ShamashAsmConfigV1
import io.shamash.asm.core.engine.ShamashAsmEngine
import io.shamash.asm.core.engine.rules.RuleUtil
import io.shamash.asm.core.engine.rules.graph.MaxCyclesRule
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsmGraphTraversalTest {
    @Test
    fun deepAcyclicGraphsDoNotOverflow() {
        for (size in listOf(12_000, 32_000)) {
            val nodes = names(size)
            val edges = nodes.zipWithNext()
            val graph = graph(nodes, edges)
            assertEquals(size, RuleUtil.stronglyConnectedComponents(graph).size)
            assertTrue(RuleUtil.cyclicComponents(graph).isEmpty())
            assertTrue(
                RuleUtil.representativeCyclesBounded(
                    nodes = nodes,
                    outgoing = { graph.successors(it) },
                ).isEmpty(),
            )
        }
    }

    @Test
    fun largeRingIsOneCyclicComponentButHasNoInvalidShortWitness() {
        val nodes = names(12_000)
        val edges = nodes.zipWithNext() + (nodes.last() to nodes.first())
        val graph = graph(nodes, edges)

        assertEquals(1, RuleUtil.cyclicComponents(graph).size)
        assertEquals(nodes.toSet(), RuleUtil.cyclicComponents(graph).single())
        assertTrue(RuleUtil.representativeCyclesBounded(nodes, graph::successors, maxCycleNodes = 120).isEmpty())

        val full = RuleUtil.representativeCyclesBounded(nodes, graph::successors, maxCycleNodes = 12_001).single()
        assertEquals(12_001, full.size)
        assertEquals(full.first(), full.last())
        assertTrue(full.zipWithNext().all { (from, to) -> to in graph.successors(from) })
    }

    @Test
    fun cycleAfterLongAcyclicPrefixRetainsClosedWitness() {
        val nodes = names(14_000)
        val edges = nodes.zipWithNext() + (nodes.last() to nodes[nodes.size - 3])
        val graph = graph(nodes, edges)
        assertEquals(1, RuleUtil.cyclicComponents(graph).size)
        assertEquals(nodes.takeLast(3).toSet(), RuleUtil.cyclicComponents(graph).single())
        val cycle = RuleUtil.representativeCyclesBounded(nodes, graph::successors, maxCycleNodes = 4).single()
        assertEquals(4, cycle.size)
        assertEquals(cycle.first(), cycle.last())
        assertTrue(cycle.zipWithNext().all { (from, to) -> to in graph.successors(from) })
        assertTrue(RuleUtil.representativeCyclesBounded(nodes, graph::successors, maxCycleNodes = 3).isEmpty())
    }

    @Test
    fun disconnectedComponentsSelfLoopsAndSingletonsAreExact() {
        val nodes = listOf("self", "a", "b", "c", "d", "alone", "tail")
        val edges =
            listOf(
                "self" to "self",
                "a" to "b",
                "b" to "a",
                "b" to "tail",
                "c" to "d",
                "d" to "c",
            )
        val graph = graph(nodes, edges)
        assertEquals(5, RuleUtil.stronglyConnectedComponents(graph).size)
        assertEquals(
            listOf(setOf("a", "b"), setOf("c", "d"), setOf("self")),
            RuleUtil.cyclicComponents(graph),
        )
        val witnesses =
            RuleUtil.representativeCyclesBounded(
                nodes.sorted(),
                { graph.successors(it).sorted() },
                maxCycles = 10,
            )
        assertEquals(3, witnesses.size)
        assertTrue(witnesses.all { it.first() == it.last() })
        assertTrue(witnesses.all { path -> path.zipWithNext().all { (from, to) -> to in graph.successors(from) } })
    }

    @Test
    fun denseGraphSamplesAreClosedAndBounded() {
        val nodes = names(64)
        val graph = graph(nodes, nodes.flatMap { from -> nodes.map { to -> from to to } })
        assertEquals(1, RuleUtil.cyclicComponents(graph).size)
        val witnesses =
            RuleUtil.representativeCyclesBounded(
                nodes,
                { graph.successors(it).sorted() },
                maxCycles = 5,
                maxCycleNodes = 4,
            )
        assertEquals(5, witnesses.size)
        assertTrue(witnesses.all { it.size in 2..4 && it.first() == it.last() })
        assertTrue(witnesses.all { path -> path.zipWithNext().all { (from, to) -> to in graph.successors(from) } })
    }

    @Test
    fun graphOrderAndCycleSampleBoundsAreDeterministic() {
        val nodes = names(36)
        val edges =
            nodes.zipWithNext() +
                listOf(nodes[4] to nodes[1], nodes[9] to nodes[5], nodes[14] to nodes[10], nodes.last() to nodes.first())
        val original = graph(nodes, edges)
        val reversed = graph(nodes.reversed(), edges.reversed())

        assertEquals(RuleUtil.stronglyConnectedComponents(original), RuleUtil.stronglyConnectedComponents(reversed))
        assertEquals(RuleUtil.cyclicComponents(original), RuleUtil.cyclicComponents(reversed))
        val sample = { g: RuleUtil.DirectedGraph ->
            RuleUtil.representativeCyclesBounded(g.nodes.sorted(), { g.successors(it).sorted() }, maxCycles = 3)
        }
        assertEquals(sample(original), sample(reversed))
        assertEquals(3, sample(original).size)
    }

    @Test
    fun emptyAndInvalidSampleBudgetsAreSafe() {
        val self = graph(listOf("self"), listOf("self" to "self"))
        assertTrue(RuleUtil.representativeCyclesBounded(emptyList<String>(), { emptyList<String>() }).isEmpty())
        assertTrue(RuleUtil.representativeCyclesBounded(self.nodes, self::successors, maxCycles = 0).isEmpty())
        assertTrue(RuleUtil.representativeCyclesBounded(self.nodes, self::successors, maxCycles = -1).isEmpty())
        assertTrue(RuleUtil.representativeCyclesBounded(self.nodes, self::successors, maxCycleNodes = 0).isEmpty())
        assertTrue(RuleUtil.representativeCyclesBounded(self.nodes, self::successors, maxCycleNodes = 1).isEmpty())
        assertEquals(listOf(listOf("self", "self")), RuleUtil.representativeCyclesBounded(self.nodes, self::successors, maxCycleNodes = 2))
        val singleton = graph(listOf("alone"), emptyList())
        assertEquals(listOf(setOf("alone")), RuleUtil.stronglyConnectedComponents(singleton))
        assertTrue(RuleUtil.cyclicComponents(singleton).isEmpty())
        assertTrue(RuleUtil.representativeCyclesBounded(singleton.nodes, singleton::successors).isEmpty())
    }

    @Test
    fun configuredRulesAndSummariesRetainTruthfulCounts() {
        val nodes = names(12_000)
        val edges = nodes.zipWithNext() + (nodes.last() to nodes.first())
        val facts = facts(nodes, edges)
        val reference = referenceConfig()
        val base = reference.copy(analysis = analysisWithGraph(reference, false))

        assertNull(AsmAnalysisPipeline.run(facts, base.analysis).graphs)
        val clean = facts(nodes, nodes.zipWithNext())
        assertTrue(NoCyclesRule().evaluate(clean, noCyclesRule(), base).isEmpty())
        assertTrue(MaxCyclesRule().evaluate(clean, maxCyclesRule(0), base).isEmpty())

        for (enabled in listOf(false, true)) {
            val config = base.copy(analysis = analysisWithGraph(reference, enabled))
            val noCycles = NoCyclesRule().evaluate(facts, noCyclesRule(), config).single()
            assertEquals("1", noCycles.data["cycleCount"])
            assertEquals("true", noCycles.data["cyclesTruncated"])
            assertFalse("cycle" in noCycles.data)
            val maxCycles = MaxCyclesRule().evaluate(facts, maxCyclesRule(0), config).single()
            assertEquals("1", maxCycles.data["count"])
            assertTrue(MaxCyclesRule().evaluate(facts, maxCyclesRule(1), config).isEmpty())

            val summary = AsmAnalysisPipeline.run(facts, config.analysis).graphs
            if (enabled) {
                val graphs = assertNotNull(summary)
                assertEquals(1, graphs.cyclicSccs.size)
                assertEquals(12_000, graphs.cyclicSccs.single().size)
                assertEquals(1, graphs.sccCount)
                assertTrue(graphs.representativeCycles.isEmpty())
            } else {
                assertNull(summary)
            }
        }
    }

    @Test
    fun sampledCycleFindingsMarkUnrepresentedComponents() {
        val longRing = names(130)
        val shortCycle = listOf("extra.A", "extra.B")
        val nodes = longRing + shortCycle
        val edges =
            longRing.zipWithNext() +
                (longRing.last() to longRing.first()) +
                listOf(shortCycle[0] to shortCycle[1], shortCycle[1] to shortCycle[0])
        val config = referenceConfig().let { it.copy(analysis = analysisWithGraph(it, false)) }
        val findings = NoCyclesRule().evaluate(facts(nodes, edges), noCyclesRule(), config)
        assertEquals(1, findings.size)
        assertEquals("2", findings.single().data["cycleCount"])
        assertEquals("true", findings.single().data["cyclesTruncated"])
        val witness = assertNotNull(findings.single().data["cycle"])
        assertTrue(witness.startsWith("extra.A -> extra.B -> extra.A"))
    }

    @Test
    fun manyCyclicComponentsAreCountedBeyondSampleLimit() {
        val nodes = names(24)
        val edges = nodes.chunked(2).flatMap { pair -> listOf(pair[0] to pair[1], pair[1] to pair[0]) }
        val config = referenceConfig().let { it.copy(analysis = analysisWithGraph(it, false)) }
        val findings = NoCyclesRule().evaluate(facts(nodes, edges), noCyclesRule(), config)
        assertEquals(10, findings.size)
        assertEquals("12", findings.first().data["cycleCount"])
        assertTrue(findings.all { it.data["cyclesTruncated"] == "true" })
        val threshold = MaxCyclesRule().evaluate(facts(nodes, edges), maxCyclesRule(10), config).single()
        assertEquals("12", threshold.data["count"])
    }

    @Test
    fun enginePublishesLargeGraphFindingsAndAnalysisWithoutErrors() {
        val nodes = names(12_000)
        val edges = nodes.zipWithNext() + (nodes.last() to nodes.first())
        val facts = facts(nodes, edges)
        val reference = referenceConfig()
        val project = Files.createTempDirectory("shamash-asm-graph-depth")
        try {
            val config =
                reference.copy(
                    rules = listOf(noCyclesRule(), maxCyclesRule(0)),
                    analysis = analysisWithGraph(reference, true),
                    baseline = reference.baseline.copy(mode = BaselineMode.NONE),
                    export =
                        reference.export.copy(
                            enabled = true,
                            outputDir = "reports",
                            formats = listOf(ExportFormat.JSON),
                            overwrite = true,
                            artifacts =
                                ExportArtifactsConfig(
                                    analysis =
                                        ExportAnalysisArtifactsConfig(
                                            enabled = true,
                                            graphs = true,
                                            hotspots = false,
                                            scoring = false,
                                        ),
                                ),
                        ),
                )
            val result = ShamashAsmEngine().analyze(project, "large-graph", config, facts)
            assertTrue(result.isSuccess, result.errors.toString())
            assertEquals(2, result.findings.size)
            assertEquals(0, result.summary.ruleStats.failedRuleInstances)
            assertEquals("1", result.findings.single { it.ruleId.contains("maxCycles") }.data["count"])
            assertEquals(1, assertNotNull(result.analysis?.graphs).cyclicSccs.size)
            val export = assertNotNull(result.export)
            assertTrue(Files.size(export.outputDir.resolve(ExportOutputLayout.JSON_FILE_NAME)) > 0L)
            assertTrue(Files.size(assertNotNull(export.analysisGraphsPath)) > 0L)
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun crossEdgesToFinishedNodesAndEmptyGraphsAreHandled() {
        val empty = graph(emptyList(), emptyList())
        assertTrue(RuleUtil.stronglyConnectedComponents(empty).isEmpty())
        assertTrue(RuleUtil.cyclicComponents(empty).isEmpty())

        val dag =
            graph(
                listOf("a", "b", "c"),
                listOf("a" to "b", "a" to "c", "b" to "c"),
            )
        assertEquals(3, RuleUtil.stronglyConnectedComponents(dag).size)
        assertTrue(RuleUtil.representativeCyclesBounded(dag.nodes.sorted(), { dag.successors(it).sorted() }).isEmpty())
    }

    @Test
    fun sccPartitionAgreesWithIndependentReachabilityOracle() {
        for (seed in 0 until 100) {
            val nodes = names(9)
            val random = java.util.Random(seed.toLong())
            val edges =
                nodes.flatMap { from ->
                    nodes.mapNotNull { to -> if (random.nextInt(4) == 0) from to to else null }
                }
            val graph = graph(nodes, edges)
            val components = RuleUtil.stronglyConnectedComponents(graph)
            assertEquals(nodes.toSet(), components.flatten().toSet())
            assertEquals(nodes.size, components.sumOf { it.size })

            val group = components.flatMapIndexed { id, members -> members.map { it to id } }.toMap()
            val reachable = nodes.associateWith { RuleUtil.reachable(graph, it) }
            for (from in nodes) {
                for (to in nodes) {
                    val mutuallyReachable =
                        from == to ||
                            (to in reachable.getValue(from) && from in reachable.getValue(to))
                    assertEquals(mutuallyReachable, group.getValue(from) == group.getValue(to))
                }
            }
            val expectedCyclic =
                components.filter { it.size > 1 || it.single() in graph.successors(it.single()) }
            assertEquals(expectedCyclic, RuleUtil.cyclicComponents(graph))
        }
    }

    private fun names(size: Int): List<String> = (0 until size).map { "pkg.N" + it.toString().padStart(5, '0') }

    private fun graph(
        nodes: List<String>,
        edges: List<Pair<String, String>>,
    ): RuleUtil.DirectedGraph =
        RuleUtil.DirectedGraph(
            nodes.toSet(),
            edges.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() },
            edges.size,
        )

    private fun facts(
        nodes: List<String>,
        edges: List<Pair<String, String>>,
    ): FactIndex {
        val types = nodes.associateWith { TypeRef.fromInternalName(it.replace('.', '/')) }
        val classes =
            nodes.map { name ->
                ClassFact(
                    type = types.getValue(name),
                    access = 0,
                    superType = null,
                    interfaces = emptySet(),
                    annotationsFqns = emptySet(),
                    hasMainMethod = false,
                    location = SourceLocation(OriginKind.DIR_CLASS, "classes/" + name.replace('.', '/') + ".class"),
                )
            }
        val dependencies =
            edges.map { (from, to) ->
                DependencyEdge(
                    from = types.getValue(from),
                    to = types.getValue(to),
                    kind = DependencyKind.METHOD_CALL,
                    location = SourceLocation(OriginKind.DIR_CLASS, "classes/" + from.replace('.', '/') + ".class"),
                )
            }
        return FactIndex(classes, emptyList(), emptyList(), dependencies, emptyMap(), emptyMap())
    }

    private fun noCyclesRule(): RuleDef = RuleDef("graph", "noCycles", null, true, FindingSeverity.ERROR, null, emptyMap())

    private fun maxCyclesRule(max: Int): RuleDef =
        RuleDef("graph", "maxCycles", null, true, FindingSeverity.ERROR, null, mapOf("maxCycles" to max))

    private fun analysisWithGraph(
        reference: ShamashAsmConfigV1,
        enabled: Boolean,
    ) = reference.analysis.copy(
        graphs = reference.analysis.graphs.copy(enabled = enabled, granularity = Granularity.CLASS, includeExternalBuckets = false),
        hotspots = reference.analysis.hotspots.copy(enabled = false),
        scoring = reference.analysis.scoring.copy(enabled = false),
    )

    private fun referenceConfig(): ShamashAsmConfigV1 {
        val yaml = SchemaResources.openReferenceYaml().use { it.reader(Charsets.UTF_8).readText() }
        return assertNotNull(ConfigValidation.loadAndValidateV1(StringReader(yaml)).config)
    }
}
