package io.shamash.asm.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.service.AsmIndexService
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * ASM-2: Dashboard UI.
 *
 * Deterministic, fast, and simple:
 * - Rescan action
 * - Tree with project + external buckets (collapsed)
 * - Small summary header
 */
@Deprecated("moved to hierarchy tab")
class AsmDashboardPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val header = JBLabel("No ASM index yet. Run a scan.")
    private val treeRoot = DefaultMutableTreeNode("Shamash ASM")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel)

    init {
        val headerPanel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.CENTER)
        }

        setContent(
            JPanel(BorderLayout()).apply {
                add(headerPanel, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
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

        treeRoot.removeAllChildren()
        treeRoot.add(AsmTreeModelBuilder.buildProjectNode(index))
        treeRoot.add(AsmTreeModelBuilder.buildExternalBucketsNode(index))
        treeModel.reload()

        // Root
        tree.expandRow(0)

        // Expand "Project", collapse "External"
        if (tree.rowCount > 1) tree.expandRow(1)
        // External buckets root child is usually row 2 after expand
        if (tree.rowCount > 2) tree.collapseRow(2)
    }

    private inner class RescanAction :
        AnAction("Rescan", "Rebuild ASM index from module outputs", ShamashIcons.PLUGIN) {

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
        AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall) {
        override fun actionPerformed(e: AnActionEvent) {
            for (i in 0 until tree.rowCount) tree.expandRow(i)
        }
    }

    private inner class CollapseAllAction :
        AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
        override fun actionPerformed(e: AnActionEvent) {
            for (i in tree.rowCount downTo 1) tree.collapseRow(i)
            tree.expandRow(0)
        }
    }
}
