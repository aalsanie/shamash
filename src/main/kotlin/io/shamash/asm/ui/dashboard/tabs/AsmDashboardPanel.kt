/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
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
package io.shamash.asm.ui.dashboard.tabs

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.service.AsmIndexListener
import io.shamash.asm.service.AsmIndexService
import io.shamash.asm.ui.AsmTreeModelBuilder
import io.shamash.asm.ui.ShamashIcons
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * ASM Dashboard (hosts all tabs).
 */
class AsmDashboardPanel(
    private val project: Project,
) : SimpleToolWindowPanel(true, true),
    Disposable {
    private val header = JBLabel("No Shamash index yet. Run a Shamash scan.")

    // Tab search (main tab)
    private val searchTab = AsmSearchTabPanel(project)

    // Tab hierarchy tree
    private val treeRoot = DefaultMutableTreeNode("Shamash")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val treeTab = Tree(treeModel)

    // Tab hotspots
    private val hotspotsTab = AsmHotspotsTabPanel(project)

    // Tab findings
    private val findingsTab = AsmFindingsTabPanel(project)

    // Tab report
    private val report = AsmReportTabPanel(project)

    private val tabs = JBTabbedPane()

    init {
        val headerPanel =
            JPanel(BorderLayout()).apply {
                add(header, BorderLayout.CENTER)
            }

        // refresh panel without waiting for user to rescan and reopen
        project.messageBus.connect(this).subscribe(
            AsmIndexListener.TOPIC,
            object : AsmIndexListener {
                override fun indexUpdated(index: AsmIndex) {
                    ApplicationManager.getApplication().invokeLater(
                        {
                            render(index)
                            revalidate()
                            repaint()
                        },
                        ModalityState.any(),
                    )
                }
            },
        )

        tabs.addTab("Search", searchTab)
        tabs.addTab(
            "Hierarchy",
            JPanel(BorderLayout()).apply {
                add(ScrollPaneFactory.createScrollPane(treeTab), BorderLayout.CENTER)
            },
        )
        tabs.addTab("Hotspots", hotspotsTab)
        tabs.addTab("Findings", findingsTab)
        tabs.addTab("Report", report)

        setContent(
            JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.NORTH)
                add(tabs, BorderLayout.CENTER)
            },
        )
        findingsTab.setReportSink(report)

        val tb = createToolbar()
        setToolbar(tb.component)

        // if a scan already happened in this project session, show it
        AsmIndexService.getInstance(project).getLatest()?.let { render(it) }
    }

    private fun createToolbar(): ActionToolbar {
        val group =
            DefaultActionGroup().apply {
                add(RescanAction())
                addSeparator()
                add(ExpandAllAction())
                add(CollapseAllAction())
            }
        return ActionManager
            .getInstance()
            .createActionToolbar("ShamashAsmDashboard", group, true)
    }

    private fun render(index: AsmIndex) {
        val totalClasses = index.classes.size
        val totalRefs = index.references.values.sumOf { it.size }
        val buckets = index.externalBuckets.size
        header.text = "Indexed $totalClasses classes. Captured $totalRefs bytecode references. " +
                "External buckets: $buckets."

        // update main tab hierarchy
        searchTab.onIndexUpdated(index)

        // update tab tree
        treeRoot.removeAllChildren()
        treeRoot.add(AsmTreeModelBuilder.buildProjectNode(index))
        treeRoot.add(AsmTreeModelBuilder.buildExternalBucketsNode(index))
        treeModel.reload()
        treeTab.expandRow(0)
        // update tab hotspot
        hotspotsTab.onIndexUpdated(index)
        // update tab findings
        findingsTab.onIndexUpdated(index)
        // update tab report
        report.onIndexUpdated(index)
        // expand collapse toggle in tree view tab
        if (treeTab.rowCount > 1) treeTab.expandRow(1)
        if (treeTab.rowCount > 2) treeTab.collapseRow(2)
    }

    private inner class RescanAction :
        AnAction("Analyze", "Scan module outputs + dependency jars (build Shamash index)", ShamashIcons.PLUGIN) {
        override fun actionPerformed(e: AnActionEvent) {
            object : Task.Backgroundable(project, "Shamash scan", false) {
                override fun run(indicator: ProgressIndicator) {
                    val index = AsmIndexService.getInstance(project).rescan(indicator)
                    ApplicationManager.getApplication().invokeLater { render(index) }
                }
            }.queue()
        }
    }

    private inner class ExpandAllAction : AnAction("Expand All", "Expand all nodes (Index tab)", AllIcons.Actions.Expandall) {
        override fun actionPerformed(e: AnActionEvent) {
            for (i in 0 until treeTab.rowCount) treeTab.expandRow(i)
        }
    }

    private inner class CollapseAllAction :
        AnAction("Collapse All", "Collapse all nodes (Index tab)", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(e: AnActionEvent) {
            for (i in treeTab.rowCount downTo 1) treeTab.collapseRow(i)
            treeTab.expandRow(0)
        }
    }

    override fun dispose() {
        // TODO("I have no idea if we need to clean anything but let's assume we don't for now!")
    }
}
