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
package io.shamash.intellij.plugin.e2e

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import io.shamash.intellij.plugin.analysis.AnalysisCoverage
import io.shamash.intellij.plugin.analysis.AnalysisPhase
import io.shamash.intellij.plugin.analysis.AnalysisRequest
import io.shamash.intellij.plugin.analysis.AnalysisSnapshot
import io.shamash.intellij.plugin.analysis.Completeness
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.IdeAnalysisState
import io.shamash.intellij.plugin.analysis.ResultRelation
import io.shamash.intellij.plugin.ui.source.PageControls
import io.shamash.intellij.plugin.ui.source.ShamashSourcePanel
import io.shamash.intellij.plugin.ui.source.SourceEvidence
import io.shamash.intellij.plugin.ui.source.SourceEvidencePanel
import io.shamash.intellij.plugin.ui.source.SourceUiModel
import io.shamash.psi.core.source.ProjectSourceSnapshot
import io.shamash.psi.core.source.SourceAnalysisProfile
import io.shamash.psi.core.source.SourceCoverage
import io.shamash.psi.core.source.SourceDependency
import io.shamash.psi.core.source.SourceDependencyKind
import io.shamash.psi.core.source.SourceEntity
import io.shamash.psi.core.source.SourceEntityId
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceGraphAnalyzer
import io.shamash.psi.core.source.SourcePackageId
import io.shamash.psi.core.source.SourceReference
import io.shamash.psi.core.source.SourceRelationship
import io.shamash.psi.core.source.SourceTarget
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import com.intellij.openapi.ui.JBSplitter
import javax.swing.tree.TreePath

class SourceExperienceE2ETest : ShamashPluginE2eTestBase() {
    fun testFirstUseShowsExactlyTwoDestinationsWithoutStartingAnalysis() {
        runInEdtAndWait {
            val coordinator = IdeAnalysisCoordinator.getInstance(project)
            val before = coordinator.state().requestGeneration
            val view = ShamashSourcePanel(project)
            try {
                assertEquals("Shamash source analysis", view.accessibleContext.accessibleName)
                assertEquals("Findings", view.findings.accessibleContext.accessibleName)
                assertEquals("Explore", view.explore.accessibleContext.accessibleName)
                assertEquals(2, view.tabs.tabCount)
                assertEquals("Findings", view.tabs.getTitleAt(0))
                assertEquals("Explore", view.tabs.getTitleAt(1))
                assertEquals("Analyze project", view.analyze.text)
                assertEquals("Understand your project's dependencies", view.status.text)
                assertTrue(view.scope.text.contains("Project rules not evaluated"))
                assertEquals(before, coordinator.state().requestGeneration)
                assertFalse(view.cancel.isEnabled)
            } finally {
                Disposer.dispose(view)
            }
        }
    }

    fun testAnalyzeButtonPublishesActualProjectSourceGraphToBothTabs() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        myFixture.addFileToProject(
            "src/main/java/left/Left.java",
            "package left; public class Left { right.Right dependency; }",
        )
        myFixture.addFileToProject(
            "src/main/java/right/Right.java",
            "package right; public class Right { left.Left dependency; }",
        )
        lateinit var panel: ShamashSourcePanel
        runInEdtAndWait {
            panel = ShamashSourcePanel(project)
            panel.analyze.doClick()
        }
        try {
            val coordinator = IdeAnalysisCoordinator.getInstance(project)
            var done = false
            for (attempt in 0 until 500) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                if (!coordinator.state().active) {
                    done = true
                    break
                }
                Thread.sleep(20)
            }
            assertTrue("Source analysis did not terminate", done)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            runInEdtAndWait {
                val current = kotlin.test.assertNotNull(coordinator.state().visibleResult?.payload)
                val graph = kotlin.test.assertNotNull(current.sourceGraph)
                assertEquals(1, graph.cycles.size)
                assertEquals(1, panel.findings.findingList.model.size)
                panel.tabs.selectedIndex = 1
                assertTrue(
                    panel.explore.tree.model
                        .getChildCount(panel.explore.tree.model.root) > 0,
                )
                assertEquals("Analyze again", panel.analyze.text)
            }
        } finally {
            runInEdtAndWait { Disposer.dispose(panel) }
        }
    }

    fun testCycleAndNoCycleStatesShareFindingsAndExploreSnapshot() {
        val cyclic = sampleGraph(2)
        val acyclic = sampleGraph(1)
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.render(presentation(cyclic))
                assertEquals("Source analysis complete", panel.status.text)
                assertEquals(2, panel.findings.findingList.model.size)
                assertEquals(2, panel.tabs.tabCount)
                panel.tabs.selectedIndex = 1
                assertTrue(
                    panel.explore.tree.model
                        .getChildCount(panel.explore.tree.model.root) > 0,
                )

                panel.render(presentation(acyclic))
                assertEquals("No package cycles found in the analyzed source", panel.status.text)
                assertEquals(0, panel.findings.findingList.model.size)
                assertTrue(
                    panel.explore.tree.model
                        .getChildCount(panel.explore.tree.model.root) > 0,
                )
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testSearchUsesWholeResultThenClearResetsAndPaginationIsBounded() {
        val graph = sampleGraph(101)
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.render(presentation(graph))
                assertEquals(50, panel.findings.findingList.model.size)
                val pager = descendants(panel.findings).filterIsInstance<PageControls>().first()
                assertTrue(pager.next.isEnabled)
                pager.next.doClick()
                assertEquals(50, panel.findings.findingList.model.size)
                pager.next.doClick()
                assertEquals(1, panel.findings.findingList.model.size)

                val search =
                    descendants(panel.findings).filterIsInstance<JBTextField>().first {
                        it.accessibleContext?.accessibleName == "Search all package cycle findings"
                    }
                search.text = "not a real package"
                assertEquals(0, panel.findings.findingList.model.size)
                assertTrue(
                    descendants(panel.findings).filterIsInstance<com.intellij.ui.components.JBTextArea>().any {
                        it.text == "No results match these filters"
                    },
                )
                descendants(panel.findings)
                    .filterIsInstance<JButton>()
                    .first {
                        it.text == "Clear filters"
                    }.doClick()
                assertEquals(50, panel.findings.findingList.model.size)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testUnavailablePartialFailureCancelledAndStaleNeverBecomeCleanSuccess() {
        val graph = sampleGraph(2)
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.render(presentation(graph))
                val previous = panel.findings.findingList.model.size
                panel.render(
                    presentation(graph).copy(
                        phase = AnalysisPhase.STALE,
                        resultRelation = ResultRelation.STALE,
                        sourceRevision = 5,
                    ),
                )
                assertEquals("Code changed since this analysis", panel.status.text)
                assertEquals(previous, panel.findings.findingList.model.size)

                panel.render(
                    presentation(graph).copy(
                        phase = AnalysisPhase.FAILED,
                        diagnostics = listOf("injected extraction failure"),
                        resultRelation = ResultRelation.PREVIOUS,
                    ),
                )
                assertEquals("Source analysis couldn't finish", panel.status.text)
                assertEquals(previous, panel.findings.findingList.model.size)

                panel.render(
                    presentation(graph).copy(
                        phase = AnalysisPhase.PARTIAL,
                        resultRelation = ResultRelation.CURRENT,
                    ),
                )
                assertEquals("Some source dependencies could not be analyzed", panel.status.text)
                panel.render(
                    presentation(graph).copy(
                        phase = AnalysisPhase.CANCELLED,
                        resultRelation = ResultRelation.PREVIOUS,
                    ),
                )
                assertEquals("Analysis cancelled", panel.status.text)
                panel.render(
                    IdeAnalysisState(
                        phase = AnalysisPhase.UNAVAILABLE,
                        attemptCoverage = AnalysisCoverage(eligibleFiles = 0, reason = "NO_SOURCE"),
                    ),
                )
                assertEquals("No supported production source found", panel.status.text)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testExploreSearchesAllRelationshipsAndCanReturnFromEvidence() {
        val graph = sampleGraph(1)
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.render(presentation(graph))
                panel.tabs.selectedIndex = 1
                val tree = panel.explore.tree
                val model = tree.model
                val module = model.getChild(model.root, 0)
                val pkg = model.getChild(module, 0)
                tree.selectionPath = TreePath(arrayOf(model.root, module, pkg))
                assertEquals(1, panel.explore.relationList.model.size)

                val search =
                    descendants(panel.explore).filterIsInstance<JBTextField>().first {
                        it.accessibleContext?.accessibleName == "Search all relationships for selected source"
                    }
                search.text = "not present"
                assertEquals(0, panel.explore.relationList.model.size)
                assertTrue(
                    descendants(panel.explore).filterIsInstance<JBTextArea>().any {
                        it.text == "No results match these filters"
                    },
                )
                search.text = "module:p1"
                assertEquals(1, panel.explore.relationList.model.size)
                assertNotNull(
                    panel.explore.relationList.actionMap
                        .get("backToTree"),
                )
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testLargeCycleMembershipIsPagedWithoutTruncatingUnderlyingGroup() {
        val base = sampleGraph(2)
        val members = (0 until 125).map { SourcePackageId("module", "package" + it) }
        val cycle = base.cycles.first().copy(members = members)
        val graph = base.copy(cycles = listOf(cycle))
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.render(presentation(graph))
                val text =
                    descendants(panel.findings).filterIsInstance<JBTextArea>().first {
                        it.text.startsWith("Packages 1–50 of 125")
                    }
                assertFalse(text.text.contains("package99"))
                val membersPager =
                    descendants(panel.findings)
                        .filterIsInstance<PageControls>()
                        .first { it.next.isEnabled }
                membersPager.next.doClick()
                assertTrue(text.text.startsWith("Packages 51–100 of 125"))
                assertTrue(text.text.contains("package99"))
                membersPager.next.doClick()
                assertTrue(text.text.startsWith("Packages 101–125 of 125"))
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testNarrowWindowUsesStackedNativeLayout() {
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                val findingsSplit = panel.findings.components.filterIsInstance<JBSplitter>().single()
                val exploreSplit = panel.explore.components.filterIsInstance<JBSplitter>().single()
                val views =
                    listOf(
                        Triple(panel.findings, findingsSplit, 0.48f to 0.42f),
                        Triple(panel.explore, exploreSplit, 0.45f to 0.38f),
                    )
                for ((view, split, proportions) in views) {
                    assertNotNull(split.firstComponent)
                    assertNotNull(split.secondComponent)
                    view.setSize(350, 700)
                    view.dispatchEvent(
                        java.awt.event.ComponentEvent(view, java.awt.event.ComponentEvent.COMPONENT_RESIZED),
                    )
                    assertTrue(split.orientation)
                    assertEquals(proportions.first, split.proportion)
                    view.setSize(900, 320)
                    view.dispatchEvent(
                        java.awt.event.ComponentEvent(view, java.awt.event.ComponentEvent.COMPONENT_RESIZED),
                    )
                    assertFalse(split.orientation)
                    assertEquals(proportions.second, split.proportion)
                }
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testEvidencePanelNeverOpensMissingOrStaleLocations() {
        runInEdtAndWait {
            val panel = SourceEvidencePanel(project)
            assertEquals("Source evidence", panel.accessibleContext.accessibleName)
            val id =
                SourceEntityId(
                    "missing-module",
                    "file:///missing",
                    "file:///missing.kt",
                    SourceEntityKind.CLASS,
                    "Missing",
                )
            val witness =
                SourceEvidence(
                    SourceReference(id.fileUrl, 10, 2, 1),
                    id,
                    "Missing evidence",
                )
            panel.showEvidence(listOf(witness), 35L)
            val count =
                descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().first {
                    it.text.startsWith("Showing ")
                }
            assertEquals("Showing 1 of 35 reference occurrences (up to 20 locations)", count.text)
            val open = descendants(panel).filterIsInstance<JButton>().single { it.text == "Open source" }
            assertFalse(open.isEnabled)
            panel.list.selectedIndex = 0
            assertTrue(open.isEnabled)
            open.doClick()
            assertTrue(
                descendants(panel).filterIsInstance<com.intellij.ui.components.JBTextArea>().any {
                    it.text.contains("Analyze again")
                },
            )
            panel.showEvidence(emptyList(), 0L)
            assertFalse(open.isEnabled)
            assertEquals(0, panel.list.model.size)
        }
    }

    fun testPageControlsPreventOverflowAndDisableAtBoundaries() {
        runInEdtAndWait {
            val controls = PageControls()
            assertEquals("Result pages", controls.accessibleContext.accessibleName)
            controls.update(0)
            assertFalse(controls.previous.isEnabled)
            assertFalse(controls.next.isEnabled)
            assertEquals(0, controls.page)

            controls.selectPage(105, 111)
            assertEquals(2, controls.page)
            assertTrue(controls.previous.isEnabled)
            assertFalse(controls.next.isEnabled)
            controls.change(-1, 111)
            assertEquals(1, controls.page)
            assertTrue(controls.next.isEnabled)
            controls.change(-99, 111)
            assertEquals(0, controls.page)
            assertFalse(controls.previous.isEnabled)
            controls.reset()
            assertEquals(0, controls.page)
        }
    }

    fun testExploreUsedByIsDirectionalAndSelectsObservedEvidenceOnly() {
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.render(presentation(sampleGraph(1)))
                panel.tabs.selectedIndex = 1
                val sourceTree = panel.explore.tree
                val tree = sourceTree.model
                val module = tree.getChild(tree.root, 0)
                val packageB = tree.getChild(module, 1)
                sourceTree.selectionPath = TreePath(arrayOf(tree.root, module, packageB))
                assertEquals(0, panel.explore.relationList.model.size)
                val direction =
                    descendants(panel.explore)
                        .filterIsInstance<JComboBox<*>>()
                        .single { it.accessibleContext?.accessibleName == "Dependency direction" }
                direction.selectedIndex = 1
                assertEquals(1, panel.explore.relationList.model.size)
                panel.explore.relationList.selectedIndex = 0
                val evidence = descendants(panel.explore).filterIsInstance<SourceEvidencePanel>().single()
                assertEquals(1, evidence.list.model.size)
                direction.selectedIndex = 2
                assertEquals(0, panel.explore.relationList.model.size)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    private fun presentation(
        graph: io.shamash.psi.core.source.SourceGraphSnapshot,
    ): IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation> {
        val result =
            AnalysisSnapshot(
                AnalysisRequest(1, 0, 0),
                Completeness.COMPLETE,
                AnalysisCoverage(graph.coverage.eligibleFiles, graph.coverage.analyzedFiles),
                IdeAnalysisCoordinator.SourcePresentation(sourceGraph = graph, uiModel = SourceUiModel(graph)),
                emptyList(),
            )
        return IdeAnalysisState(
            requestGeneration = 1,
            phase = AnalysisPhase.COMPLETED,
            visibleResult = result,
            resultRelation = ResultRelation.CURRENT,
        )
    }

    private fun sampleGraph(cyclePairs: Int): io.shamash.psi.core.source.SourceGraphSnapshot {
        val nodes =
            (0 until cyclePairs * 2).map { index ->
                val id =
                    SourceEntityId(
                        "module",
                        "file:///src",
                        "file:///src/T" + index + ".kt",
                        SourceEntityKind.CLASS,
                        "T" + index,
                    )
                SourceEntity(id, "p" + index, "T" + index, 0)
            }
        val edges =
            (0 until cyclePairs).flatMap { i ->
                val a = nodes[i * 2].id
                val b = nodes[i * 2 + 1].id
                if (cyclePairs == 1) {
                    listOf(a to b)
                } else {
                    listOf(a to b, b to a)
                }
            }
        val relationships =
            edges.map { (from, to) ->
                SourceRelationship(
                    from,
                    to,
                    setOf(SourceDependencyKind.CALL),
                    1,
                    listOf(SourceReference(from.fileUrl, 10, 2, 1)),
                )
            }
        val references =
            edges.map { (from, to) ->
                SourceDependency(
                    from,
                    SourceTarget.Internal(to),
                    SourceDependencyKind.CALL,
                    SourceReference(from.fileUrl, 10, 2, 1),
                )
            }
        val snapshot =
            ProjectSourceSnapshot(
                SourceAnalysisProfile(),
                nodes,
                references,
                relationships,
                emptyList(),
                SourceCoverage(nodes.size, nodes.size, 0, edges.size.toLong(), 0, 0, false),
                true,
            )
        return SourceGraphAnalyzer().analyze(snapshot)
    }

    private fun descendants(container: Container): List<Component> =
        container.components.flatMap { child ->
            listOf(child) + if (child is Container) descendants(child) else emptyList()
        }
}
