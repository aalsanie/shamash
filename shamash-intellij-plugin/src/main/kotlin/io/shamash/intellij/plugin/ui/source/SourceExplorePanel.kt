/*
 * Copyright © 2025-2026 | Shamash
 * Author: @aalsanie
 * SPDX-License-Identifier: Apache-2.0
 */
package io.shamash.intellij.plugin.ui.source

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Locale
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.TreeModelListener
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreeModel
import javax.swing.tree.TreePath

internal class SourceExploreTreeModel(
    private val source: SourceUiModel?,
    private val query: String,
) : TreeModel {
    private val root = "Production source"
    private val modules: List<SourceSelection.Module> =
        source?.moduleIds.orEmpty()
            .filter { source?.filteredPackages(it, query)?.isNotEmpty() == true }
            .map(SourceSelection::Module)

    override fun getRoot(): Any = root

    override fun getChild(parent: Any?, index: Int): Any =
        when (parent) {
            root -> modules[index]
            is SourceSelection.Module ->
                SourceSelection.Package(source!!.filteredPackages(parent.moduleId, query)[index])
            is SourceSelection.Package ->
                SourceSelection.Declaration(source!!.filteredEntities(parent.id, query)[index].id)
            else -> throw IndexOutOfBoundsException("Not a tree branch")
        }

    override fun getChildCount(parent: Any?): Int =
        when (parent) {
            root -> modules.size
            is SourceSelection.Module -> source?.filteredPackages(parent.moduleId, query)?.size ?: 0
            is SourceSelection.Package -> source?.filteredEntities(parent.id, query)?.size ?: 0
            else -> 0
        }

    override fun isLeaf(node: Any?): Boolean = node is SourceSelection.Declaration

    override fun getIndexOfChild(parent: Any?, child: Any?): Int =
        when (parent) {
            root -> modules.indexOf(child)
            is SourceSelection.Module -> {
                val id = (child as? SourceSelection.Package)?.id
                if (id == null) -1 else source?.filteredPackages(parent.moduleId, query)?.indexOf(id) ?: -1
            }
            is SourceSelection.Package ->
                source?.filteredEntities(parent.id, query)?.indexOfFirst {
                    it.id == (child as? SourceSelection.Declaration)?.id
                } ?: -1
            else -> -1
        }

    override fun valueForPathChanged(path: TreePath?, newValue: Any?) = Unit
    override fun addTreeModelListener(l: TreeModelListener?) = Unit
    override fun removeTreeModelListener(l: TreeModelListener?) = Unit

    fun pathFor(selection: SourceSelection): TreePath? {
        val module = when (selection) {
            is SourceSelection.Module -> selection.moduleId
            is SourceSelection.Package -> selection.id.moduleId
            is SourceSelection.Declaration -> selection.id.moduleId
        }
        val moduleNode = SourceSelection.Module(module)
        if (moduleNode !in modules) return null
        if (selection is SourceSelection.Module) return TreePath(arrayOf(root, moduleNode))
        val pkg = when (selection) {
            is SourceSelection.Package -> selection.id
            is SourceSelection.Declaration -> {
                val entity = source?.declarations?.get(selection.id) ?: return null
                io.shamash.psi.core.source.SourcePackageId(module, entity.packageName)
            }
            else -> return null
        }
        if (pkg !in source!!.filteredPackages(module, query)) return null
        val packageNode = SourceSelection.Package(pkg)
        if (selection is SourceSelection.Package) return TreePath(arrayOf(root, moduleNode, packageNode))
        val declaration = selection as SourceSelection.Declaration
        if (source.filteredEntities(pkg, query).none { it.id == declaration.id }) return null
        return TreePath(arrayOf(root, moduleNode, packageNode, declaration))
    }

    fun isEmpty(): Boolean = modules.isEmpty()
}

internal class SourceExplorePanel(project: Project) : JPanel(BorderLayout()) {
    private var source: SourceUiModel? = null
    private var selection: SourceSelection? = null
    private var relations: List<SourceRelationRow> = emptyList()
    private val search = JBTextField()
    val tree = Tree()
    private val summary = wrappedText()
    private val related = wrappedText()
    private val empty = JBLabel("Analyze project to explore dependencies")
    private val direction = JBComboBox(arrayOf("Uses", "Used by", "External dependencies"))
    private val sort = JButton("Sort: name")
    private var mostDependencies = false
    private val relationModel = DefaultListModel<SourceRelationRow>()
    val relationList = sourceList<SourceRelationRow>("Source relationships") {
        it.label + " · " + it.distinctTargets + " distinct targets · " +
            it.occurrences + " occurrences" + if (it.sampled) " · sampled" else ""
    }
    private val pager = PageControls()
    private val evidence = SourceEvidencePanel(project)
    private val clear = JButton("Clear search")
    private val back = JButton("Back to source tree")
    private val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

    init {
        search.emptyText.text = "Search modules, packages or declarations"
        search.accessibleContext.accessibleName = "Search all production source declarations"
        val searchRow = JPanel(BorderLayout(5, 0))
        searchRow.add(search, BorderLayout.CENTER)
        searchRow.add(clear, BorderLayout.EAST)
        val left = JPanel(BorderLayout(0, 5))
        left.add(searchRow, BorderLayout.NORTH)
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.accessibleContext.accessibleName = "IDE module, package and declaration tree"
        tree.cellRenderer =
            object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(
                    tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
                    leaf: Boolean, row: Int, hasFocus: Boolean,
                ): java.awt.Component {
                    val cell = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                    val text =
                        when (value) {
                            is SourceSelection.Module -> value.moduleId
                            is SourceSelection.Package -> value.id.packageName.ifEmpty { "(default package)" }
                            is SourceSelection.Declaration ->
                                source?.declarations?.get(value.id)?.displayName ?: value.id.signature
                            else -> "Production source"
                        }
                    this.text = "<html>" + escaped(text) + "</html>"
                    return cell
                }
            }
        left.add(JBScrollPane(tree), BorderLayout.CENTER)
        left.add(empty, BorderLayout.SOUTH)

        val detail = JPanel(BorderLayout(0, 5))
        val top = JPanel()
        top.layout = javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS)
        top.add(back)
        top.add(summary)
        top.add(related)
        val switches = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        direction.accessibleContext.accessibleName = "Dependency direction"
        switches.add(direction)
        switches.add(sort)
        top.add(switches)
        detail.add(top, BorderLayout.NORTH)
        relationList.model = relationModel
        val center = JPanel(BorderLayout(0, 4))
        center.add(JBScrollPane(relationList), BorderLayout.CENTER)
        center.add(pager, BorderLayout.SOUTH)
        detail.add(center, BorderLayout.CENTER)
        detail.add(evidence, BorderLayout.SOUTH)
        evidence.preferredSize = Dimension(200, 145)

        split.leftComponent = left
        split.rightComponent = detail
        split.resizeWeight = 0.38
        add(split, BorderLayout.CENTER)
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                split.orientation = if (width < 560) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
                split.resizeWeight = if (width < 560) 0.45 else 0.38
            }
        })

        tree.addTreeSelectionListener {
            val selected = tree.lastSelectedPathComponent as? SourceSelection
            if (selected != null) {
                selection = selected
                showSelection()
            }
        }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshTree()
            override fun removeUpdate(e: DocumentEvent?) = refreshTree()
            override fun changedUpdate(e: DocumentEvent?) = refreshTree()
        })
        clear.addActionListener { search.text = "" }
        direction.addActionListener { renderRelationships() }
        sort.addActionListener {
            mostDependencies = !mostDependencies
            sort.text = if (mostDependencies) "Sort: most dependencies" else "Sort: name"
            renderRelationships()
        }
        pager.previous.addActionListener { pager.change(-1, relations.size); renderPage() }
        pager.next.addActionListener { pager.change(1, relations.size); renderPage() }
        relationList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = relationList.selectedValue
                if (row == null) evidence.showEvidence(emptyList(), 0)
                else evidence.showEvidence(row.evidence, row.occurrences)
            }
        }
        back.addActionListener {
            tree.requestFocusInWindow()
            if (split.orientation == JSplitPane.VERTICAL_SPLIT) split.setDividerLocation(0.65)
        }
        accessibleContext.accessibleName = "Explore"
        refreshTree()
    }

    fun setGraph(next: SourceUiModel?) {
        source = next
        refreshTree()
        if (selection != null && tree.selectionPath == null) {
            summary.text = "This declaration or package no longer appears. Analyze again."
            related.text = ""
            relations = emptyList()
            renderPage()
        }
    }

    private fun refreshTree() {
        val next = SourceExploreTreeModel(source, search.text)
        tree.model = next
        empty.text =
            when {
                source == null -> "Analyze project to explore dependencies"
                next.isEmpty() && search.text.isNotBlank() -> "No results match these filters"
                next.isEmpty() -> "No supported declarations in this result"
                else -> source?.moduleIds?.size.toString() + " IDE modules · expand a module to inspect packages"
            }
        val path = selection?.let(next::pathFor)
        if (path != null) {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun showSelection() {
        val source = source ?: return
        val selected = selection ?: return
        summary.text = source.scopeLabel(selected) + "\n" + source.summary(selected)
        val cycles = source.relatedCycles(selected)
        related.text =
            if (cycles.isEmpty()) "No observed related package cycles"
            else "Related cyclic groups: " + cycles.size +
                "\n" + cycles.take(10).joinToString("\n") { cycle ->
                    cycle.members.joinToString(" → ") { SourceUiModel.qualified(it) }
                } + if (cycles.size > 10) "\nShowing 10 of " + cycles.size else ""
        renderRelationships()
    }

    private fun renderRelationships() {
        val source = source ?: return
        val selected = selection ?: return
        relations = when (direction.selectedIndex) {
            1 -> source.usedBy(selected)
            2 -> source.external(selected)
            else -> source.uses(selected)
        }.let { rows ->
            if (mostDependencies) rows.sortedWith(compareByDescending<SourceRelationRow> { it.occurrences }.thenBy { it.label })
            else rows.sortedBy { it.label }
        }
        pager.reset()
        renderPage()
    }

    private fun renderPage() {
        pager.update(relations.size)
        relationModel.clear()
        SourceUiModel.page(relations, pager.page).forEach(relationModel::addElement)
        if (relationModel.isEmpty) evidence.showEvidence(emptyList(), 0)
    }
}
