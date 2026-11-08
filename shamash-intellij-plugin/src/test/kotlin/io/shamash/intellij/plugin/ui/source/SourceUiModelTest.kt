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
package io.shamash.intellij.plugin.ui.source

import io.shamash.psi.core.source.ProjectSourceSnapshot
import io.shamash.psi.core.source.SourceAnalysisProfile
import io.shamash.psi.core.source.SourceCoverage
import io.shamash.psi.core.source.SourceDependency
import io.shamash.psi.core.source.SourceDependencyKind
import io.shamash.psi.core.source.SourceEntity
import io.shamash.psi.core.source.SourceEntityId
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceExternalReference
import io.shamash.psi.core.source.SourceGraphAnalyzer
import io.shamash.psi.core.source.SourceGraphSnapshot
import io.shamash.psi.core.source.SourcePackageId
import io.shamash.psi.core.source.SourceReference
import io.shamash.psi.core.source.SourceRelationship
import io.shamash.psi.core.source.SourceTarget
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SourceUiModelTest {
    private val a = SourcePackageId("module", "alpha")
    private val b = SourcePackageId("module", "beta")

    @Test
    fun filtersAllFindingsByNameModulePackageAndSourceWitness() {
        val model = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1, 1 to 0)))
        assertEquals(1, model.findings.size)
        assertEquals(1, model.filteredFindings("PACKAGES DEPEND", null, null).size)
        assertEquals(1, model.filteredFindings("module:alpha", null, null).size)
        assertEquals(1, model.filteredFindings("T0", null, null).size)
        assertEquals(1, model.filteredFindings("file:///src/module/T0.kt", null, null).size)
        assertEquals(1, model.filteredFindings("", "module", b).size)
        assertTrue(model.filteredFindings("", "other", null).isEmpty())
        assertTrue(model.filteredFindings("", "module", SourcePackageId("module", "other")).isEmpty())
        assertTrue(model.filteredFindings("not present", null, null).isEmpty())
    }

    @Test
    fun noCyclesStillProvidesExplorationForAllProductionPackages() {
        val model = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1)))
        assertTrue(model.findings.isEmpty())
        assertEquals(listOf(a, b), model.packageIds)
        assertEquals(listOf(b), model.packageIds.filter { it in model.graph.packageUses.getValue(a) })
        assertEquals(1, model.uses(SourceSelection.Package(a)).size)
        assertEquals(1, model.usedBy(SourceSelection.Package(b)).size)
        assertTrue(model.usedBy(SourceSelection.Package(a)).isEmpty())
        assertTrue(model.uses(SourceSelection.Module("module")).isEmpty())
    }

    @Test
    fun incomingAndOutgoingCountsRemainDirectedAndKeepOccurrences() {
        val model = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1), occurrences = 20))
        val out = model.uses(SourceSelection.Package(a)).single()
        val inbound = model.usedBy(SourceSelection.Package(b)).single()
        assertEquals(20L, out.occurrences)
        assertEquals(20, out.evidence.size)
        assertEquals(1, out.distinctTargets)
        assertEquals(out.evidence, inbound.evidence)
        val declarationEvidence = model.uses(SourceSelection.Declaration(model.graph.entities[0].id)).single()
        assertEquals(20L, declarationEvidence.occurrences)
        assertTrue(declarationEvidence.evidence.all {
            it.target is SourceTarget.Internal && it.kind == SourceDependencyKind.CALL
        })
        assertEquals(1, model.usedBy(SourceSelection.Declaration(model.graph.entities[1].id)).size)
        assertTrue(model.usedBy(SourceSelection.Declaration(model.graph.entities[0].id)).isEmpty())
    }

    @Test
    fun packageAndDeclarationFiltersSearchWholeSourceTree() {
        val model = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1)))
        assertEquals(listOf(a, b), model.filteredPackages("module", ""))
        assertEquals(listOf(a), model.filteredPackages("module", "alpha"))
        assertEquals(listOf(a), model.filteredPackages("module", "T0"))
        assertTrue(model.filteredPackages("unknown", "").isEmpty())
        assertEquals(1, model.filteredEntities(a, "T0").size)
        assertEquals(1, model.filteredEntities(a, "MODULE:alpha").size)
        assertTrue(model.filteredEntities(a, "not-there").isEmpty())
    }

    @Test
    fun moduleAndPackageIdentityAreNotConflated() {
        val model = SourceUiModel(
            graph(listOf("shared", "shared"), listOf(0 to 1, 1 to 0), listOf("left", "right")),
        )
        assertEquals(2, model.packagesByModule.size)
        assertEquals(2, model.packageIds.size)
        assertEquals(1, model.filteredFindings("", "left", null).size)
        assertEquals(1, model.filteredFindings("", "right", null).size)
        assertEquals(listOf("left", "right"), model.moduleIds)
        assertEquals(1, model.uses(SourceSelection.Package(SourcePackageId("left", "shared"))).size)
        assertEquals(1, model.usedBy(SourceSelection.Package(SourcePackageId("right", "shared"))).size)
    }

    @Test
    fun relatedCyclesAreScopedBySelectedEntityPackageOrModule() {
        val model = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1, 1 to 0)))
        assertEquals(1, model.relatedCycles(SourceSelection.Module("module")).size)
        assertEquals(1, model.relatedCycles(SourceSelection.Package(a)).size)
        assertEquals(1, model.relatedCycles(SourceSelection.Declaration(model.graph.entities[0].id)).size)
        assertTrue(model.relatedCycles(SourceSelection.Package(SourcePackageId("other", "alpha"))).isEmpty())
        assertTrue(model.scopeLabel(SourceSelection.Module("module")).contains("IDE module"))
        assertTrue(model.scopeLabel(SourceSelection.Package(a)).contains("package"))
        assertTrue(model.scopeLabel(SourceSelection.Declaration(model.graph.entities[0].id)).contains("class"))
        assertTrue(model.summary(SourceSelection.Module("module")).contains("2 packages"))
        assertTrue(model.summary(SourceSelection.Package(a)).contains("1 distinct packages"))
        assertTrue(model.summary(SourceSelection.Declaration(model.graph.entities[0].id)).contains("1 distinct"))
    }

    @Test
    fun sampledExternalDependenciesDoNotInventIncomingEdgesOrFullCounts() {
        val base = graph(listOf("alpha", "beta"), listOf(0 to 1))
        val node = base.entities.first().id
        val evidence = SourceExternalReference(
            node, "external.Service", SourceDependencyKind.TYPE,
            SourceReference(node.fileUrl, 10, 4, 1),
        )
        val model = SourceUiModel(base.copy(externalEvidence = listOf(evidence, evidence)))
        val sampled = model.external(SourceSelection.Package(a)).single()
        assertEquals("external.Service (external sample)", sampled.label)
        assertTrue(sampled.sampled)
        assertEquals(2L, sampled.occurrences)
        assertTrue(model.external(SourceSelection.Package(b)).isEmpty())
        assertEquals(1, model.external(SourceSelection.Declaration(node)).size)
        assertEquals(1, model.external(SourceSelection.Module("module")).size)
        assertTrue(model.usedBy(SourceSelection.Package(a)).isEmpty())
    }

    @Test
    fun evidenceIsBoundedButExactOccurrenceCountsAreNot() {
        val model = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1), occurrences = 35))
        val row = model.uses(SourceSelection.Package(a)).single()
        assertEquals(20, row.evidence.size)
        assertEquals(35L, row.occurrences)
        val declaration = model.uses(SourceSelection.Declaration(model.graph.entities[0].id)).single()
        assertEquals(20, declaration.evidence.size)
        assertEquals(35L, declaration.occurrences)
    }

    @Test
    fun paginationNeverLeaksResultsFromAdjacentPage() {
        val numbers = (0 until 111).toList()
        assertEquals(50, SourceUiModel.page(numbers, 0).size)
        assertEquals((50 until 100).toList(), SourceUiModel.page(numbers, 1))
        assertEquals((100 until 111).toList(), SourceUiModel.page(numbers, 2))
        assertTrue(SourceUiModel.page(numbers, 3).isEmpty())
        assertEquals(numbers.take(50), SourceUiModel.page(numbers, -10))
        assertEquals(3, SourceUiModel.pageCount(111))
        assertEquals(42_949_673, SourceUiModel.pageCount(Int.MAX_VALUE))
        assertEquals(1, SourceUiModel.pageCount(0))
        assertEquals("module:(default)", SourceUiModel.qualified(SourcePackageId("module", "")))
    }

    @Test
    fun findingsSortLargerGroupsBeforeSmallerGroupsNotBySeverity() {
        val two = graph(listOf("alpha", "beta"), listOf(0 to 1, 1 to 0)).cycles.single()
        val larger = two.copy(
            id = "larger",
            members = listOf(a, b, SourcePackageId("module", "gamma")),
        )
        val base = graph(listOf("alpha", "beta"), listOf(0 to 1, 1 to 0))
        val model = SourceUiModel(base.copy(cycles = listOf(two, larger)))
        assertEquals(listOf("larger", two.id), model.findings.map { it.id })
    }

    @Test
    fun hostileSourceTextIsEscapedBeforeUseAsHtmlLabel() {
        assertEquals("&lt;html&gt;&amp;&quot;&#39;", escaped("<html>&\"'"))
        assertEquals("unchanged", escaped("unchanged"))
        assertFalse(SourceGuidance.OFFLINE_HELP.contains("http://"))
    }

    @Test
    fun explorationTreeContainsExactModulePackageAndDeclarationHierarchy() {
        val source = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1)))
        val tree = SourceExploreTreeModel(source, "")
        val root = tree.root
        assertFalse(tree.isEmpty())
        assertEquals(1, tree.getChildCount(root))
        val module = tree.getChild(root, 0)
        assertEquals(SourceSelection.Module("module"), module)
        assertEquals(2, tree.getChildCount(module))
        val pkg = tree.getChild(module, 0)
        assertEquals(SourceSelection.Package(a), pkg)
        assertEquals(1, tree.getChildCount(pkg))
        val declaration = tree.getChild(pkg, 0)
        assertTrue(tree.isLeaf(declaration))
        assertEquals(0, tree.getChildCount(declaration))
        assertEquals(0, tree.getIndexOfChild(root, module))
        assertEquals(0, tree.getIndexOfChild(module, pkg))
        assertEquals(0, tree.getIndexOfChild(pkg, declaration))
        assertEquals(2, tree.pathFor(SourceSelection.Module("module"))?.pathCount)
        assertEquals(3, tree.pathFor(SourceSelection.Package(a))?.pathCount)
        assertEquals(4, tree.pathFor(declaration as SourceSelection)?.pathCount)
        assertEquals(null, tree.pathFor(SourceSelection.Module("missing")))
        assertEquals(null, tree.pathFor(SourceSelection.Package(SourcePackageId("module", "missing"))))
    }

    @Test
    fun explorationTreeFiltersDeclarationsWithoutShowingUnrelatedPackages() {
        val source = SourceUiModel(graph(listOf("alpha", "beta"), listOf(0 to 1)))
        val tree = SourceExploreTreeModel(source, "T1")
        assertFalse(tree.isEmpty())
        val module = tree.getChild(tree.root, 0)
        assertEquals(1, tree.getChildCount(module))
        assertEquals(SourceSelection.Package(b), tree.getChild(module, 0))
        assertEquals(null, tree.pathFor(SourceSelection.Package(a)))
        assertEquals(-1, tree.getIndexOfChild(module, SourceSelection.Package(a)))

        val empty = SourceExploreTreeModel(source, "no_such_name")
        assertTrue(empty.isEmpty())
        assertEquals(0, empty.getChildCount(empty.root))
        assertEquals(-1, empty.getIndexOfChild(empty.root, module))
        assertEquals(null, empty.pathFor(SourceSelection.Module("module")))

        val noSource = SourceExploreTreeModel(null, "")
        assertTrue(noSource.isEmpty())
        assertFalse(noSource.isLeaf(noSource.root))
        assertEquals(0, noSource.getChildCount(noSource.root))
        assertEquals(0, noSource.getChildCount("unknown"))
    }

    private fun graph(
        packages: List<String>,
        arcs: List<Pair<Int, Int>>,
        modules: List<String> = List(packages.size) { "module" },
        occurrences: Long = 1L,
    ): SourceGraphSnapshot {
        val nodes = packages.mapIndexed { index, pkg ->
            val id = SourceEntityId(
                modules[index], "file:///src", "file:///src/" + modules[index] + "/T" + index + ".kt",
                SourceEntityKind.CLASS, "T" + index,
            )
            SourceEntity(id, pkg, "T" + index, 0)
        }
        val relations = arcs.map { (from, target) ->
            val id = nodes[from].id
            val witness = (0 until minOf(occurrences, 20).toInt()).map {
                SourceReference(id.fileUrl, it + 10, 3, 1)
            }
            SourceRelationship(id, nodes[target].id, setOf(SourceDependencyKind.CALL), occurrences, witness)
        }
        val references = arcs.flatMap { (from, target) ->
            (0 until minOf(occurrences, 20).toInt()).map { offset ->
                SourceDependency(
                    nodes[from].id, SourceTarget.Internal(nodes[target].id), SourceDependencyKind.CALL,
                    SourceReference(nodes[from].id.fileUrl, offset + 10, 3, 1),
                )
            }
        }
        val source = ProjectSourceSnapshot(
            SourceAnalysisProfile(), nodes, references, relations, emptyList(),
            SourceCoverage(nodes.size, nodes.size, 0, arcs.size.toLong() * occurrences, 0, 0, false),
            true,
        )
        return SourceGraphAnalyzer().analyze(source)
    }
}
