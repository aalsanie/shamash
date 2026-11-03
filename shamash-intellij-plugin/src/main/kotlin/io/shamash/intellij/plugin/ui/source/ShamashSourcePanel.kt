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

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.IdeAnalysisState
import io.shamash.intellij.plugin.analysis.ResultRelation
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu

class ShamashSourcePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val coordinator = IdeAnalysisCoordinator.getInstance(project)
    val tabs = JBTabbedPane()
    internal val findings = SourceFindingsPanel(project) { tabs.selectedIndex = 1 }
    internal val explore = SourceExplorePanel(project)
    val analyze = JButton("Analyze project")
    val cancel = JButton("Cancel")
    val help = JButton("Help")
    val status = JBLabel("Understand your project's dependencies")
    val scope = JBLabel(SourceGuidance.SCOPE_LINE)
    private val firstRun = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val about = JButton("About these checks")
    private val advanced = JButton("Architecture enforcement and CLI — GitHub")
    private val details = wrappedText()
    private var currentModel: SourceUiModel? = null
    private var disposed = false

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 3))
        toolbar.add(analyze)
        toolbar.add(cancel)
        toolbar.add(help)
        val statusPanel = JPanel(BorderLayout(0, 3))
        statusPanel.add(status, BorderLayout.NORTH)
        details.rows = 3
        details.accessibleContext.accessibleName = "Analysis scope and diagnostics"
        val detailsScroll = JBScrollPane(details)
        detailsScroll.preferredSize = Dimension(200, 76)
        statusPanel.add(detailsScroll, BorderLayout.CENTER)
        statusPanel.add(scope, BorderLayout.SOUTH)
        val header = JPanel(BorderLayout(0, 4))
        header.add(toolbar, BorderLayout.NORTH)
        header.add(statusPanel, BorderLayout.CENTER)
        firstRun.add(about)
        firstRun.add(advanced)
        header.add(firstRun, BorderLayout.SOUTH)
        add(header, BorderLayout.NORTH)

        tabs.addTab("Findings", findings)
        tabs.addTab("Explore", explore)
        tabs.accessibleContext.accessibleName = "Shamash source destinations"
        add(tabs, BorderLayout.CENTER)

        details.text = SourceGuidance.FIRST_RUN
        cancel.isEnabled = false
        analyze.addActionListener { SourceAnalysisActions.analyze(project) }
        cancel.addActionListener { coordinator.cancel() }
        help.addActionListener { showHelp() }
        about.addActionListener { showAbout() }
        advanced.addActionListener { BrowserUtil.browse(SourceGuidance.REPOSITORY_URL) }
        analyze.accessibleContext.accessibleName = "Analyze project source"
        cancel.accessibleContext.accessibleName = "Cancel source analysis"
        help.accessibleContext.accessibleName = "Help and advanced architecture enforcement"
        accessibleContext.accessibleName = "Shamash source analysis"

        coordinator.subscribe(this) { next ->
            if (!disposed && !project.isDisposed) render(next)
        }
    }

    internal fun render(state: IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation>) {
        if (disposed || project.isDisposed) return
        analyze.isEnabled = !state.active
        analyze.text = if (state.requestGeneration == 0L) "Analyze project" else "Analyze again"
        cancel.isEnabled = state.active
        firstRun.isVisible = state.phase == io.shamash.intellij.plugin.analysis.AnalysisPhase.IDLE
        status.text = SourceGuidance.status(state)
        status.accessibleContext.accessibleDescription = SourceGuidance.status(state)
        val relation =
            when (state.resultRelation) {
                ResultRelation.PREVIOUS -> " · Previous result"
                ResultRelation.STALE -> " · Stale result"
                ResultRelation.CURRENT -> ""
                null -> ""
            }
        val graph = state.visibleResult?.payload?.sourceGraph
        val counts =
            if (graph == null) ""
            else "\n" + graph.cycles.size + " observed cyclic groups · " +
                graph.packages.size + " packages · " + graph.entities.size + " declarations" + relation
        details.text = SourceGuidance.detailStatus(state) + counts
        scope.text = SourceGuidance.SCOPE_LINE
        val next = state.visibleResult?.payload?.uiModel
        if (next !== currentModel) {
            currentModel = next
            findings.setGraph(next)
            explore.setGraph(next)
        }
    }

    private fun showHelp() {
        val menu = JPopupMenu()
        fun item(label: String, action: () -> Unit) {
            val entry = JMenuItem(label)
            entry.addActionListener { action() }
            menu.add(entry)
        }
        item("About these checks") { showAbout() }
        item("Architecture enforcement and CLI — GitHub") {
            BrowserUtil.browse(SourceGuidance.REPOSITORY_URL)
        }
        item("License") { BrowserUtil.browse(SourceGuidance.LICENSE_URL) }
        item("Plugin source") { BrowserUtil.browse(SourceGuidance.SOURCE_URL) }
        menu.show(help, 0, help.height)
    }

    private fun showAbout() {
        val text = wrappedText(SourceGuidance.OFFLINE_HELP)
        text.columns = 55
        text.rows = 18
        JOptionPane.showMessageDialog(
            this, JBScrollPane(text), "Shamash · About these checks", JOptionPane.INFORMATION_MESSAGE,
        )
    }

    override fun dispose() {
        disposed = true
        currentModel = null
    }
}
