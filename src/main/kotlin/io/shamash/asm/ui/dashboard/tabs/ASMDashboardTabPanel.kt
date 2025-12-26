package io.shamash.asm.ui.dashboard.tabs

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.treeStructure.Tree
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.service.AsmIndexService
import io.shamash.asm.ui.AsmTreeModelBuilder
import io.shamash.asm.ui.ShamashIcons
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import io.shamash.asm.service.AsmIndexListener

/**
 * ASM Dashboard (hosts tabs).
 */
class AsmDashboardPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val header = JBLabel("No Shamash index yet. Run a Shamash scan.")

    // Tab hierarchy (main tab)
    private val hierarchyTab = AsmHierarchyTabPanel(project)

    // Tab tree
    private val treeRoot = DefaultMutableTreeNode("Shamash")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    // Tab hotspots
    private val hotspotsTab = AsmHotspotsTabPanel(project)

    //Tab findings
    private val findingsTab = AsmFindingsTabPanel(project)

    private val tabs = JBTabbedPane()

    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.CENTER)
        }

        //refresh panel without waiting for user to rescan and reopen
        project.messageBus.connect(this).subscribe(AsmIndexListener.TOPIC, object : AsmIndexListener {
            override fun indexUpdated(index: AsmIndex) {
                ApplicationManager.getApplication().invokeLater(
                    {
                        render(index)
                        revalidate()
                        repaint()
                    },
                    ModalityState.any()
                )
            }
        })

        // here we add tabs and names "Wohooo!" lets keep it clean ? TODO: export them strings!
        tabs.addTab("Hierarchy", hierarchyTab)
        tabs.addTab("Tree", JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        })
        tabs.addTab("Hotspots", hotspotsTab)
        tabs.addTab("Findings", findingsTab)


        setContent(
            JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.NORTH)
                add(tabs, BorderLayout.CENTER)
            }
        )

        val tb = createToolbar()
        setToolbar(tb.component)

        // if a scan already happened in this project session, show it
        AsmIndexService.getInstance(project).getLatest()?.let { render(it) }
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(RescanAction())
            addSeparator()
            add(ExpandAllAction())
            add(CollapseAllAction())
        }
        return ActionManager.getInstance()
            .createActionToolbar("ShamashAsmDashboard", group, true)
    }

    private fun render(index: AsmIndex) {
        val totalClasses = index.classes.size
        val totalRefs = index.references.values.sumOf { it.size }
        val buckets = index.externalBuckets.size

        // TODO: Make shamash run scan triggered dynamically when dashboard is open Shamash 2.0
        header.text =
            "Indexed $totalClasses classes. Captured $totalRefs bytecode references. External buckets: $buckets." +
                    "If 0 bytecode captured navigate to: Tools -> Press Shamash: Run scan"

        // update main tab hierarchy
        hierarchyTab.onIndexUpdated(index)

        // update tab tree
        treeRoot.removeAllChildren()
        treeRoot.add(AsmTreeModelBuilder.buildProjectNode(index))
        treeRoot.add(AsmTreeModelBuilder.buildExternalBucketsNode(index))
        treeModel.reload()
        tree.expandRow(0)
        // update tab hotspot
        hotspotsTab.onIndexUpdated(index)
        //update tab findings
        findingsTab.onIndexUpdated(index)


        // expand collapse toggle in tree view tab
        if (tree.rowCount > 1) tree.expandRow(1)
        if (tree.rowCount > 2) tree.collapseRow(2)
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

    private inner class ExpandAllAction :
        AnAction("Expand All", "Expand all nodes (Index tab)", AllIcons.Actions.Expandall) {
        override fun actionPerformed(e: AnActionEvent) {
            for (i in 0 until tree.rowCount) tree.expandRow(i)
        }
    }

    private inner class CollapseAllAction :
        AnAction("Collapse All", "Collapse all nodes (Index tab)", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(e: AnActionEvent) {
            for (i in tree.rowCount downTo 1) tree.collapseRow(i)
            tree.expandRow(0)
        }
    }

    override fun dispose() {
        //TODO("I have no idea if we need to clean anything but let's assume we don't for now!")
    }
}
