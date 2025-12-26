package io.shamash.dashboard.tabs

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

    private val header = JBLabel("No ASM index yet. Run a scan.")

    // Existing index tree (kept as a separate tab)
    private val treeRoot = DefaultMutableTreeNode("Shamash ASM")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    // Tab A
    private val hierarchyTab = AsmHierarchyTabPanel(project)

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

        tabs.addTab("Hierarchy", hierarchyTab)
        tabs.addTab("Index", JPanel(BorderLayout()).apply {
            add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        })

        setContent(
            JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.NORTH)
                add(tabs, BorderLayout.CENTER)
            }
        )

        val tb = createToolbar()
        setToolbar(tb.component)

        // If a scan already happened in this project session, show it.
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

        header.text =
            "Indexed $totalClasses classes. Captured $totalRefs bytecode references. External buckets: $buckets."

        // Update Tab A
        hierarchyTab.onIndexUpdated(index)

        // Update Index tab tree (existing behavior)
        treeRoot.removeAllChildren()
        treeRoot.add(AsmTreeModelBuilder.buildProjectNode(index))
        treeRoot.add(AsmTreeModelBuilder.buildExternalBucketsNode(index))
        treeModel.reload()

        // Root
        tree.expandRow(0)

        // Expand "Project", collapse "External"
        if (tree.rowCount > 1) tree.expandRow(1)
        if (tree.rowCount > 2) tree.collapseRow(2)
    }

    private inner class RescanAction :
        AnAction("Analyze", "Scan module outputs + dependency jars (build ASM index)", ShamashIcons.PLUGIN) {

        override fun actionPerformed(e: AnActionEvent) {
            object : Task.Backgroundable(project, "Shamash ASM Scan", false) {
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
        TODO("Not yet implemented")
    }
}
