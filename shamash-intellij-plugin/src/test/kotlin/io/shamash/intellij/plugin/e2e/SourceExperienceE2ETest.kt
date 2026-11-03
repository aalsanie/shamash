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
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBLabel
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
import io.shamash.psi.core.source.SourceReference
import io.shamash.psi.core.source.SourceRelationship
import io.shamash.psi.core.source.SourceTarget
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JSplitPane

class SourceExperienceE2ETest : ShamashPluginE2eTestBase() {
    fun testFirstUseShowsExactlyTwoDestinationsWithoutStartingAnalysis() {
        runInEdtAndWait {
            val coordinator = IdeAnalysisCoordinator.getInstance(project)
            val before = coordinator.state().requestGeneration
            val view = ShamashSourcePanel(project)
            try {
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
                assertTrue(panel.explore.tree.model.getChildCount(panel.explore.tree.model.root) > 0)

                panel.render(presentation(acyclic))
                assertEquals("No package cycles found in the analyzed source", panel.status.text)
                assertEquals(0, panel.findings.findingList.model.size)
                assertTrue(panel.explore.tree.model.getChildCount(panel.explore.tree.model.root) > 0)
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

                val search = descendants(panel.findings).filterIsInstance<JBTextField>().first {
                    it.accessibleContext?.accessibleName == "Search all package cycle findings"
                }
                search.text = "not a real package"
                assertEquals(0, panel.findings.findingList.model.size)
                assertTrue(descendants(panel.findings).filterIsInstance<JBLabel>().any {
                    it.text == "No results match these filters"
                })
                descendants(panel.findings).filterIsInstance<JButton>().first {
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
                panel.render(presentation(graph).copy(
                    phase = AnalysisPhase.STALE, resultRelation = ResultRelation.STALE,
                    sourceRevision = 5,
                ))
                assertEquals("Code changed since this analysis", panel.status.text)
                assertEquals(previous, panel.findings.findingList.model.size)

                panel.render(presentation(graph).copy(
                    phase = AnalysisPhase.FAILED, diagnostics = listOf("injected extraction failure"),
                    resultRelation = ResultRelation.PREVIOUS,
                ))
                assertEquals("Source analysis couldn't finish", panel.status.text)
                assertEquals(previous, panel.findings.findingList.model.size)

                panel.render(presentation(graph).copy(
                    phase = AnalysisPhase.PARTIAL, resultRelation = ResultRelation.CURRENT,
                ))
                assertEquals("Some source dependencies could not be analyzed", panel.status.text)
                panel.render(presentation(graph).copy(
                    phase = AnalysisPhase.CANCELLED, resultRelation = ResultRelation.PREVIOUS,
                ))
                assertEquals("Analysis cancelled", panel.status.text)
                panel.render(IdeAnalysisState(
                    phase = AnalysisPhase.UNAVAILABLE,
                    attemptCoverage = AnalysisCoverage(eligibleFiles = 0, reason = "NO_SOURCE"),
                ))
                assertEquals("No supported production source found", panel.status.text)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    fun testNarrowWindowUsesStackedNativeLayout() {
        runInEdtAndWait {
            val panel = ShamashSourcePanel(project)
            try {
                panel.findings.setSize(350, 700)
                panel.findings.dispatchEvent(java.awt.event.ComponentEvent(
                    panel.findings, java.awt.event.ComponentEvent.COMPONENT_RESIZED,
                ))
                val split = panel.findings.components.filterIsInstance<JSplitPane>().single()
                assertEquals(JSplitPane.VERTICAL_SPLIT, split.orientation)
                panel.findings.setSize(900, 320)
                panel.findings.dispatchEvent(java.awt.event.ComponentEvent(
                    panel.findings, java.awt.event.ComponentEvent.COMPONENT_RESIZED,
                ))
                assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.orientation)
            } finally {
                Disposer.dispose(panel)
            }
        }
    }

    private fun presentation(graph: io.shamash.psi.core.source.SourceGraphSnapshot):
        IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation> {
        val result = AnalysisSnapshot(
            AnalysisRequest(1, 0, 0), Completeness.COMPLETE,
            AnalysisCoverage(graph.coverage.eligibleFiles, graph.coverage.analyzedFiles),
            IdeAnalysisCoordinator.SourcePresentation(sourceGraph = graph, uiModel = SourceUiModel(graph)),
            emptyList(),
        )
        return IdeAnalysisState(
            requestGeneration = 1, phase = AnalysisPhase.COMPLETED,
            visibleResult = result, resultRelation = ResultRelation.CURRENT,
        )
    }

    private fun sampleGraph(cyclePairs: Int): io.shamash.psi.core.source.SourceGraphSnapshot {
        val nodes = (0 until cyclePairs * 2).map { index ->
            val id = SourceEntityId("module", "file:///src", "file:///src/T" + index + ".kt",
                SourceEntityKind.CLASS, "T" + index)
            SourceEntity(id, "p" + index, "T" + index, 0)
        }
        val edges =
            (0 until cyclePairs).flatMap { i ->
                val a = nodes[i * 2].id
                val b = nodes[i * 2 + 1].id
                if (cyclePairs == 1) listOf(a to b)
                else listOf(a to b, b to a)
            }
        val relationships = edges.map { (from, to) ->
            SourceRelationship(from, to, setOf(SourceDependencyKind.CALL), 1,
                listOf(SourceReference(from.fileUrl, 10, 2, 1)))
        }
        val references = edges.map { (from, to) ->
            SourceDependency(from, SourceTarget.Internal(to), SourceDependencyKind.CALL,
                SourceReference(from.fileUrl, 10, 2, 1))
        }
        val snapshot = ProjectSourceSnapshot(
            SourceAnalysisProfile(), nodes, references, relationships, emptyList(),
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
