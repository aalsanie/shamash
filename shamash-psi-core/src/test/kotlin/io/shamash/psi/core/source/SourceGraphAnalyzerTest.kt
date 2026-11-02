/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourceGraphAnalyzerTest {
    private data class Arc(val from: Int, val to: Int, val occurrences: Long = 1L)
    private val analyzer = SourceGraphAnalyzer()

    @Test
    fun `DAG has accurate uses and used-by but no cycle concern`() {
        val source = fixture(listOf("a", "b", "c"), listOf(Arc(0, 1), Arc(1, 2)))
        val graph = analyzer.analyze(source)
        assertTrue(graph.complete)
        assertTrue(graph.cycleAbsenceEstablished)
        assertTrue(graph.cycles.isEmpty())
        assertEquals(listOf(pkg("b")), graph.packageUses.getValue(pkg("a")))
        assertEquals(listOf(pkg("b")), graph.packageUsedBy.getValue(pkg("c")))
        assertEquals(SourceDependencySummary(1, 1), graph.packageCounts.getValue(pkg("a")))
        assertEquals(emptyList(), graph.packageUsedBy.getValue(pkg("a")))
    }

    @Test
    fun `pair cycle has two real directed edges each with exact evidence`() {
        val graph = analyzer.analyze(fixture(listOf("a", "b"), listOf(Arc(0, 1), Arc(1, 0))))
        assertEquals(1, graph.cycles.size)
        assertEquals(listOf(pkg("a"), pkg("b")), graph.cycles.single().members)
        assertCycleEvidence(graph.cycles.single())
    }

    @Test
    fun `long cycle closes without inventing a reverse dependency`() {
        val graph =
            analyzer.analyze(fixture(listOf("a", "b", "c", "d"), listOf(
                Arc(0, 1), Arc(1, 2), Arc(2, 3), Arc(3, 0),
            )))
        assertEquals(1, graph.cycles.size)
        assertEquals(4, graph.cycles.single().witness.size)
        assertCycleEvidence(graph.cycles.single())
        assertTrue(graph.packageUsedBy.getValue(pkg("b")).contains(pkg("a")))
        assertFalse(graph.packageUsedBy.getValue(pkg("a")).contains(pkg("b")))
    }

    @Test
    fun `intersecting cycles in one SCC give one concern with full membership`() {
        val graph =
            analyzer.analyze(fixture(listOf("a", "b", "c", "d"), listOf(
                Arc(0, 1), Arc(1, 0), Arc(1, 2), Arc(2, 1), Arc(2, 3),
            )))
        assertEquals(1, graph.cycles.size)
        val concern = graph.cycles.single()
        assertEquals(listOf(pkg("a"), pkg("b"), pkg("c")), concern.members)
        assertEquals(2, concern.witness.size) // Representative may cover fewer packages than the SCC.
        assertCycleEvidence(concern)
        assertFalse(concern.members.contains(pkg("d")))
    }

    @Test
    fun `disconnected SCCs stay separate and sort by membership count`() {
        val graph =
            analyzer.analyze(fixture(listOf("a", "b", "c", "x", "y", "z"), listOf(
                Arc(0, 1), Arc(1, 2), Arc(2, 0), Arc(3, 4), Arc(4, 3),
            )))
        assertEquals(listOf(3, 2), graph.cycles.map { it.members.size })
        graph.cycles.forEach(::assertCycleEvidence)
        assertFalse(graph.cycles.any { it.members.contains(pkg("z")) })
    }

    @Test
    fun `same package class cycle remains in entity graph and cannot become a package cycle`() {
        val names = listOf("same", "same")
        val graph = analyzer.analyze(fixture(names, listOf(Arc(0, 1), Arc(1, 0))))
        assertTrue(graph.cycles.isEmpty())
        assertTrue(graph.packageDependencies.isEmpty())
        assertEquals(2, graph.entityUses.values.sumOf { it.size })
        assertEquals(2, graph.entityCounts.values.sumOf { it.occurrences })
    }

    @Test
    fun `the same exact package name in two IDE modules has different graph identity`() {
        val source =
            fixture(
                listOf("shared", "shared"),
                listOf(Arc(0, 1), Arc(1, 0)),
                modules = listOf("module-left", "module-right"),
            )
        val graph = analyzer.analyze(source)
        assertEquals(2, graph.packages.size)
        assertEquals(1, graph.cycles.size)
        assertEquals(
            setOf("module-left", "module-right"),
            graph.cycles.single().members.map { it.moduleId }.toSet(),
        )
        assertCycleEvidence(graph.cycles.single())
    }

    @Test
    fun `twenty references mean one distinct target but twenty occurrences`() {
        val source = fixture(listOf("a", "b"), listOf(Arc(0, 1, 20)))
        val graph = analyzer.analyze(source)
        assertEquals(SourceDependencySummary(1, 20), graph.entityCounts.values.single { it.distinctTargets > 0 })
        assertEquals(SourceDependencySummary(1, 20), graph.packageCounts.getValue(pkg("a")))
        assertEquals(1, graph.packageDependencies.single().distinctEntityRelationships)
        assertEquals(20, graph.packageDependencies.single().witnesses.size)
        assertEquals(20L, graph.packageDependencies.single().occurrences)
        assertTrue(graph.cycles.isEmpty())
    }

    @Test
    fun `full membership and profile define IDs independently of witness ordering`() {
        val source =
            fixture(listOf("a", "b", "c"), listOf(
                Arc(0, 1, 20), Arc(1, 2, 3), Arc(2, 0, 4), Arc(1, 0),
            ))
        val baseline = analyzer.analyze(source)
        val shuffled =
            analyzer.analyze(source.copy(
                entities = source.entities.reversed(),
                relationships = source.relationships.reversed().map { it.copy(witnesses = it.witnesses.reversed()) },
                references = source.references.reversed(),
            ))
        assertEquals(baseline.cycles, shuffled.cycles)
        assertEquals(baseline.packageDependencies, shuffled.packageDependencies)
        assertEquals(baseline.entityUses, shuffled.entityUses)
        assertEquals(baseline.packageUses, shuffled.packageUses)
        val changedVersion = analyzer.analyze(source.copy(profile = source.profile.copy(id = "ide-source-v2")))
        assertNotEquals(baseline.cycles.single().id, changedVersion.cycles.single().id)
        val expanded = analyzer.analyze(source.copy(
            entities = source.entities + entity(3, "d", "module"),
            relationships = source.relationships +
                relationship(source.entities[2].id, entity(3, "d", "module").id),
            references = source.references +
                reference(source.entities[2].id, entity(3, "d", "module").id),
        ))
        assertEquals(baseline.cycles.single().id, expanded.cycles.single().id)
    }

    @Test
    fun `external references remain labeled and never introduce a reverse edge`() {
        val source = fixture(listOf("a"), emptyList())
        val ext = SourceDependency(
            source.entities.single().id, SourceTarget.External("java.util.Map"), SourceDependencyKind.TYPE,
            SourceReference(source.entities.single().id.fileUrl, 9, 3, 1),
        )
        val graph = analyzer.analyze(source.copy(references = listOf(ext)))
        assertTrue(graph.cycles.isEmpty())
        assertTrue(graph.packageDependencies.isEmpty())
        assertEquals(1, graph.externalEvidence.size)
        assertTrue(graph.externalEvidenceIsSampled)
        assertTrue(graph.packageUsedBy.getValue(pkg("a")).isEmpty())
    }

    @Test
    fun `missing source witness cannot fabricate a cycle edge or a clean conclusion`() {
        val source = fixture(listOf("a", "b"), listOf(Arc(0, 1), Arc(1, 0)))
        val broken = source.copy(references = source.references.filterNot { it.from == source.entities[1].id })
        val graph = analyzer.analyze(broken)
        assertFalse(graph.complete)
        assertTrue(graph.diagnostics.any { it.message.contains("verified source occurrence") })
        assertTrue(graph.cycles.isEmpty())
        assertFalse(graph.cycleAbsenceEstablished)
    }

    @Test
    fun `partial graph still reports observed cycle but cannot certify absent cycles`() {
        val source = fixture(listOf("a", "b"), listOf(Arc(0, 1), Arc(1, 0)))
        val partial =
            source.copy(
                complete = false,
                coverage = source.coverage.copy(failedFiles = 1, unresolvedOccurrences = 1),
                diagnostics = listOf(SourceDiagnostic(null, "extract", "file could not be resolved")),
            )
        val graph = analyzer.analyze(partial)
        assertFalse(graph.complete)
        assertEquals(1, graph.cycles.size)
        assertFalse(graph.cycleAbsenceEstablished)
        val emptyPartial = analyzer.analyze(partial.copy(relationships = emptyList(), references = emptyList()))
        assertTrue(emptyPartial.cycles.isEmpty())
        assertFalse(emptyPartial.cycleAbsenceEstablished)
    }

    @Test
    fun `empty supported scope cannot establish cycle absence`() {
        val source = fixture(emptyList(), emptyList())
        val graph = analyzer.analyze(source)
        assertTrue(graph.cycles.isEmpty())
        assertFalse(graph.complete)
        assertFalse(graph.cycleAbsenceEstablished)
    }

    @Test
    fun `five thousand package cycle uses no recursive DFS and retains real witness for every edge`() {
        val count = 5_000
        val source =
            fixture(
                (0 until count).map { "package$it" },
                (0 until count).map { Arc(it, (it + 1) % count) },
            )
        val graph = analyzer.analyze(source)
        assertEquals(1, graph.cycles.size)
        assertEquals(count, graph.cycles.single().members.size)
        assertEquals(count, graph.cycles.single().witness.size)
        assertCycleEvidence(graph.cycles.single())
    }

    private fun assertCycleEvidence(cycle: SourcePackageCycle) {
        assertTrue(cycle.witness.size >= 2)
        assertEquals(cycle.witness.first().from, cycle.witness.last().target)
        for (i in cycle.witness.indices) {
            val edge = cycle.witness[i]
            assertEquals(edge.target, cycle.witness[(i + 1) % cycle.witness.size].from)
            assertTrue(edge.witnesses.isNotEmpty())
            assertTrue(edge.witnesses.all { it.reference.fileUrl == it.from.fileUrl })
            assertTrue(edge.occurrences >= edge.distinctEntityRelationships)
        }
    }

    private fun pkg(value: String) = SourcePackageId("module", value)

    private fun entity(index: Int, packageName: String, module: String): SourceEntity {
        val id =
            SourceEntityId(module, "file:///src", "file:///src/$module/$index.kt", SourceEntityKind.CLASS, "T$index")
        return SourceEntity(id, packageName, "T$index", 0)
    }

    private fun reference(from: SourceEntityId, target: SourceEntityId, index: Int = 0) =
        SourceDependency(from, SourceTarget.Internal(target), SourceDependencyKind.CALL,
            SourceReference(from.fileUrl, 10 + index, 3, 1))

    private fun relationship(from: SourceEntityId, target: SourceEntityId) =
        SourceRelationship(from, target, setOf(SourceDependencyKind.CALL), 1, listOf(
            SourceReference(from.fileUrl, 10, 3, 1),
        ))

    private fun fixture(
        packageNames: List<String>,
        arcs: List<Arc>,
        modules: List<String> = List(packageNames.size) { "module" },
    ): ProjectSourceSnapshot {
        val nodes = packageNames.mapIndexed { index, name -> entity(index, name, modules[index]) }
        val relations =
            arcs.map { (a, b, count) ->
                val from = nodes[a].id
                SourceRelationship(
                    from, nodes[b].id, setOf(SourceDependencyKind.CALL), count,
                    (0 until minOf(count, 20).toInt()).map { offset ->
                        SourceReference(from.fileUrl, offset + 10, 3, 1)
                    },
                )
            }
        val evidence =
            arcs.flatMap { (a, b, count) ->
                (0 until minOf(count, 20).toInt()).map { index -> reference(nodes[a].id, nodes[b].id, index) }
            }
        return ProjectSourceSnapshot(
            profile = SourceAnalysisProfile(),
            entities = nodes,
            references = evidence,
            relationships = relations,
            diagnostics = emptyList(),
            coverage = SourceCoverage(nodes.size, nodes.size, 0, arcs.sumOf { it.occurrences }, 0, 0, false),
            complete = true,
        )
    }
}
