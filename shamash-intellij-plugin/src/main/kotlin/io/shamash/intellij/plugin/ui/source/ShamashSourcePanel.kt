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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.IdeAnalysisState
import io.shamash.intellij.plugin.analysis.ResultRelation
import io.shamash.intellij.plugin.settings.ShamashSourceMigrationState
import java.awt.BorderLayout
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu

class ShamashSourcePanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    private val coordinator = IdeAnalysisCoordinator.getInstance(project)
    val tabs = JBTabbedPane()
    internal val findings = SourceFindingsPanel(project) { tabs.selectedIndex = 1 }
    internal val explore = SourceExplorePanel(project)
    val analyze = JButton("Analyze project")
    val cancel = JButton("Cancel")
    val help = JButton("Help")
    val status = wrappedText("Understand your project's dependencies")
    val scope = wrappedText(SourceGuidance.SCOPE_LINE)
    internal val helpMenu = JPopupMenu()
    private val details = wrappedText()
    internal val detailsPopup = JPopupMenu()
    internal val detailsContent = wrappedText()
    private var currentModel: SourceUiModel? = null
    private var disposed = false

    init {
        ShamashSourceMigrationState.getInstance(project).acknowledge()
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)
        toolbar.add(analyze)
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)))
        toolbar.add(cancel)
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)))
        toolbar.add(help)
        val toolbarScroll =
            object : JBScrollPane(toolbar) {
                override fun getPreferredSize(): Dimension =
                    super.getPreferredSize().apply {
                        height = analyze.preferredSize.height + JBUI.scale(18)
                    }
            }
        toolbarScroll.verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_NEVER
        toolbarScroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        toolbarScroll.border = JBUI.Borders.empty()
        toolbarScroll.accessibleContext.accessibleName = "Source analysis actions"

        status.accessibleContext.accessibleName = "Source analysis status"
        status.toolTipText = status.text
        val statusScroll = compactMessageScroll(status, 2)
        details.accessibleContext.accessibleName = "Analysis scope and diagnostics"
        detailsContent.accessibleContext.accessibleName = "Analysis scope and diagnostics"
        val detailsScroll = JBScrollPane(detailsContent)
        detailsScroll.preferredSize = Dimension(JBUI.scale(360), JBUI.scale(180))
        detailsPopup.add(detailsScroll)
        detailsPopup.accessibleContext.accessibleName = "Analysis scope and diagnostics"
        detailsContent.text = SourceGuidance.FIRST_RUN + "\n\n" + scope.text
        val header = JPanel(BorderLayout(0, JBUI.scale(2)))
        header.add(toolbarScroll, BorderLayout.NORTH)
        header.add(statusScroll, BorderLayout.SOUTH)
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
        analyze.accessibleContext.accessibleName = "Analyze project source"
        cancel.accessibleContext.accessibleName = "Cancel source analysis"
        help.accessibleContext.accessibleName = "Help and advanced architecture enforcement"
        getAccessibleContext().accessibleName = "Shamash source analysis"
        helpMenu.accessibleContext.accessibleName = "Shamash Help"

        fun helpItem(label: String, action: () -> Unit) {
            helpMenu.add(JMenuItem(label).apply { addActionListener { action() } })
        }
        helpItem("Analysis scope and diagnostics") { showDetails() }
        helpItem("About these checks") { showAbout() }
        helpItem("Architecture enforcement and CLI — GitHub") { BrowserUtil.browse(SourceGuidance.REPOSITORY_URL) }
        helpItem("Plugin license (Apache-2.0)") { BrowserUtil.browse(SourceGuidance.LICENSE_URL) }
        helpItem("Core dependencies license (AGPL-3.0)") { BrowserUtil.browse(SourceGuidance.CORE_LICENSE_URL) }
        helpItem("Plugin source") { BrowserUtil.browse(SourceGuidance.SOURCE_URL) }

        coordinator.subscribe(this) { next ->
            if (!disposed && !project.isDisposed) render(next)
        }
    }

    internal fun render(state: IdeAnalysisState<IdeAnalysisCoordinator.SourcePresentation>) {
        if (disposed || project.isDisposed) return
        analyze.isEnabled = !state.active
        analyze.text = if (state.requestGeneration == 0L) "Analyze project" else "Analyze again"
        cancel.isEnabled = state.active
        status.text = SourceGuidance.status(state)
        status.accessibleContext.accessibleDescription = SourceGuidance.status(state)
        status.toolTipText = SourceGuidance.status(state)
        val relation =
            when (state.resultRelation) {
                ResultRelation.PREVIOUS -> " · Previous result"
                ResultRelation.STALE -> " · Stale result"
                ResultRelation.CURRENT -> ""
                null -> ""
            }
        val graph = state.visibleResult?.payload?.sourceGraph
        val counts =
            if (graph == null) {
                ""
            } else {
                "\n" + graph.cycles.size + " observed cyclic groups · " +
                    graph.packages.size + " packages · " + graph.entities.size + " declarations" + relation
            }
        details.text = SourceGuidance.detailStatus(state) + counts
        scope.text = SourceGuidance.SCOPE_LINE
        detailsContent.text = status.text + "\n\n" + details.text + "\n\n" + scope.text
        val next = state.visibleResult?.payload?.uiModel
        if (next !== currentModel) {
            currentModel = next
            findings.setGraph(next)
            explore.setGraph(next)
        }
    }

    private fun showHelp() {
        helpMenu.show(help, 0, help.height)
    }

    private fun showDetails() {
        detailsPopup.show(help, 0, help.height)
        detailsContent.requestFocusInWindow()
    }

    private fun showAbout() {
        val text = wrappedText(SourceGuidance.OFFLINE_HELP)
        text.columns = 55
        text.rows = 18
        JOptionPane.showMessageDialog(
            this,
            JBScrollPane(text),
            "Shamash · About these checks",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    override fun dispose() {
        disposed = true
        currentModel = null
        findings.dispose()
        explore.dispose()
    }
}
