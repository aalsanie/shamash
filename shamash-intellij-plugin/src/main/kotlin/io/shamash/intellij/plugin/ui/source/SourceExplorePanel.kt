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

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.KeyStroke
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
    private val modulePackages =
        source?.moduleIds.orEmpty().associateWith {
            source?.filteredPackages(it, query).orEmpty()
        }
    private val packageEntities =
        modulePackages.values.flatten().associateWith { pkg ->
            source?.filteredEntities(pkg, query).orEmpty()
        }
    private val modules: List<SourceSelection.Module> =
        modulePackages
            .filterValues { it.isNotEmpty() }
            .keys
            .sorted()
            .map(SourceSelection::Module)

    override fun getRoot(): Any = root

    override fun getChild(
        parent: Any?,
        index: Int,
    ): Any =
        when (parent) {
            root -> {
                modules[index]
            }

            is SourceSelection.Module -> {
                SourceSelection.Package(modulePackages.getValue(parent.moduleId)[index])
            }

            is SourceSelection.Package -> {
                SourceSelection.Declaration(packageEntities.getValue(parent.id)[index].id)
            }

            else -> {
                throw IndexOutOfBoundsException("Not a tree branch")
            }
        }

    override fun getChildCount(parent: Any?): Int =
        when (parent) {
            root -> modules.size
            is SourceSelection.Module -> modulePackages[parent.moduleId]?.size ?: 0
            is SourceSelection.Package -> packageEntities[parent.id]?.size ?: 0
            else -> 0
        }

    override fun isLeaf(node: Any?): Boolean = node is SourceSelection.Declaration

    override fun getIndexOfChild(
        parent: Any?,
        child: Any?,
    ): Int =
        when (parent) {
            root -> {
                modules.indexOf(child)
            }

            is SourceSelection.Module -> {
                val id = (child as? SourceSelection.Package)?.id
                if (id == null) -1 else modulePackages[parent.moduleId]?.indexOf(id) ?: -1
            }

            is SourceSelection.Package -> {
                packageEntities[parent.id]?.indexOfFirst {
                    it.id == (child as? SourceSelection.Declaration)?.id
                } ?: -1
            }

            else -> {
                -1
            }
        }

    override fun valueForPathChanged(
        path: TreePath?,
        newValue: Any?,
    ) = Unit

    override fun addTreeModelListener(l: TreeModelListener?) = Unit

    override fun removeTreeModelListener(l: TreeModelListener?) = Unit

    fun pathFor(selection: SourceSelection): TreePath? {
        val sourceModel = source ?: return null
        val module =
            when (selection) {
                is SourceSelection.Module -> selection.moduleId
                is SourceSelection.Package -> selection.id.moduleId
                is SourceSelection.Declaration -> selection.id.moduleId
            }
        val moduleNode = SourceSelection.Module(module)
        if (moduleNode !in modules) return null
        if (selection is SourceSelection.Module) return TreePath(arrayOf(root, moduleNode))
        val pkg =
            when (selection) {
                is SourceSelection.Package -> {
                    selection.id
                }

                is SourceSelection.Declaration -> {
                    val entity = sourceModel.declarations[selection.id] ?: return null
                    io.shamash.psi.core.source
                        .SourcePackageId(module, entity.packageName)
                }

                else -> {
                    return null
                }
            }
        if (pkg !in modulePackages[module].orEmpty()) return null
        val packageNode = SourceSelection.Package(pkg)
        if (selection is SourceSelection.Package) return TreePath(arrayOf(root, moduleNode, packageNode))
        val declaration = selection as SourceSelection.Declaration
        if (packageEntities[pkg].orEmpty().none { it.id == declaration.id }) return null
        return TreePath(arrayOf(root, moduleNode, packageNode, declaration))
    }

    fun isEmpty(): Boolean = modules.isEmpty()
}

internal class SourceExplorePanel(
    project: Project,
) : JPanel(BorderLayout()) {
    private var source: SourceUiModel? = null
    private var selection: SourceSelection? = null
    private var relations: List<SourceRelationRow> = emptyList()
    private val search = JBTextField()
    private val relationSearch = JBTextField()
    val tree = Tree()
    private val summary = wrappedText()
    private val related = wrappedText()
    private val empty = wrappedText("Analyze project to explore dependencies")
    private val direction = JBComboBox(arrayOf("Uses", "Used by", "External dependencies"))
    private val sort = JButton("Sort: name")
    private var mostDependencies = false
    private val relationModel = DefaultListModel<SourceRelationRow>()
    val relationList =
        sourceList<SourceRelationRow>("Source relationships") {
            it.label +
                if (it.sampled) {
                    " · " + it.occurrences + " sampled references"
                } else {
                    " · " + it.distinctTargets + " distinct source relationships · " + it.occurrences + " occurrences"
                }
        }
    private val pager = PageControls()
    private val relationEmpty = wrappedText("")
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
                    tree: JTree?,
                    value: Any?,
                    sel: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean,
                ): java.awt.Component {
                    super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                    val text =
                        when (value) {
                            is SourceSelection.Module -> {
                                value.moduleId
                            }

                            is SourceSelection.Package -> {
                                value.id.packageName.ifEmpty { "(default package)" }
                            }

                            is SourceSelection.Declaration -> {
                                source?.declarations?.get(value.id)?.displayName ?: value.id.signature
                            }

                            else -> {
                                "Production source"
                            }
                        }
                    this.text = "<html>" + escaped(text) + "</html>"
                    return this
                }
            }
        left.add(JBScrollPane(tree), BorderLayout.CENTER)
        empty.rows = 2
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
        relationSearch.emptyText.text = "Search the selected relationship list"
        relationSearch.accessibleContext.accessibleName = "Search all relationships for selected source"
        top.add(relationSearch)
        detail.add(top, BorderLayout.NORTH)
        relationList.model = relationModel
        val center = JPanel(BorderLayout(0, 4))
        center.add(JBScrollPane(relationList), BorderLayout.CENTER)
        val footer = JPanel(BorderLayout())
        relationEmpty.rows = 2
        footer.add(relationEmpty, BorderLayout.NORTH)
        footer.add(pager, BorderLayout.SOUTH)
        center.add(footer, BorderLayout.SOUTH)
        detail.add(center, BorderLayout.CENTER)
        detail.add(evidence, BorderLayout.SOUTH)
        evidence.preferredSize = Dimension(200, 145)

        split.leftComponent = left
        split.rightComponent = detail
        split.resizeWeight = 0.38
        add(split, BorderLayout.CENTER)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    split.orientation = if (width < 560) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
                    split.resizeWeight = if (width < 560) 0.45 else 0.38
                }
            },
        )

        tree.addTreeSelectionListener {
            val selected = tree.lastSelectedPathComponent as? SourceSelection
            if (selected != null) {
                selection = selected
                showSelection()
            }
        }
        search.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = refreshTree()

                override fun removeUpdate(e: DocumentEvent?) = refreshTree()

                override fun changedUpdate(e: DocumentEvent?) = refreshTree()
            },
        )
        clear.addActionListener { search.text = "" }
        relationSearch.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = renderRelationships()

                override fun removeUpdate(e: DocumentEvent?) = renderRelationships()

                override fun changedUpdate(e: DocumentEvent?) = renderRelationships()
            },
        )
        direction.addActionListener { renderRelationships() }
        sort.addActionListener {
            mostDependencies = !mostDependencies
            sort.text = if (mostDependencies) "Sort: most dependencies" else "Sort: name"
            renderRelationships()
        }
        pager.previous.addActionListener {
            pager.change(-1, relations.size)
            renderPage()
        }
        pager.next.addActionListener {
            pager.change(1, relations.size)
            renderPage()
        }
        relationList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val row = relationList.selectedValue
                if (row == null) {
                    evidence.showEvidence(emptyList(), 0)
                } else {
                    evidence.showEvidence(row.evidence, row.occurrences)
                }
            }
        }
        back.addActionListener { returnToTree() }
        val escape = KeyStroke.getKeyStroke("ESCAPE")
        for (component in listOf(relationList, evidence.list)) {
            component.inputMap.put(escape, "backToTree")
            component.actionMap.put(
                "backToTree",
                object : AbstractAction() {
                    override fun actionPerformed(e: java.awt.event.ActionEvent?) = returnToTree()
                },
            )
        }
        detail
            .getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .put(escape, "backToTree")
        detail.actionMap.put(
            "backToTree",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = returnToTree()
            },
        )
        accessibleContext.accessibleName = "Explore"
        refreshTree()
    }

    private fun returnToTree() {
        tree.requestFocusInWindow()
        if (split.orientation == JSplitPane.VERTICAL_SPLIT) split.setDividerLocation(0.65)
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
        if (!next.isEmpty()) tree.expandRow(0)
        val path = selection?.let(next::pathFor)
        if (path != null) {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else if (selection != null) {
            summary.text = "This package or declaration is not in the current view."
            related.text = ""
            relations = emptyList()
            renderPage()
        }
    }

    private fun showSelection() {
        val source = source ?: return
        val selected = selection ?: return
        summary.text = source.scopeLabel(selected) + "\n" + source.summary(selected)
        val cycles = source.relatedCycles(selected)
        related.text =
            if (cycles.isEmpty()) {
                "No observed related package cycles"
            } else {
                "Related cyclic groups: " + cycles.size +
                    "\n" +
                    cycles.take(3).joinToString("\n") { cycle ->
                        val sample = cycle.members.take(5).joinToString(", ") { SourceUiModel.qualified(it) }
                        sample +
                            if (cycle.members.size > 5) {
                                " · +" + (cycle.members.size - 5) +
                                    " other packages in group"
                            } else {
                                ""
                            }
                    } + if (cycles.size > 3) "\nShowing 3 of " + cycles.size else ""
            }
        renderRelationships()
    }

    private fun renderRelationships() {
        val source = source ?: return
        val selected = selection ?: return
        relations =
            when (direction.selectedIndex) {
                1 -> source.usedBy(selected)
                2 -> source.external(selected)
                else -> source.uses(selected)
            }.let { rows ->
                val term = relationSearch.text.trim().lowercase(Locale.ROOT)
                val matching =
                    if (term.isEmpty()) {
                        rows
                    } else {
                        rows.filter { row ->
                            row.label.lowercase(Locale.ROOT).contains(term) ||
                                row.evidence.any { evidence ->
                                    evidence.label.lowercase(Locale.ROOT).contains(term)
                                }
                        }
                    }
                if (mostDependencies) {
                    matching.sortedWith(
                        compareByDescending<SourceRelationRow> { it.distinctTargets }
                            .thenByDescending { it.occurrences }
                            .thenBy { it.label },
                    )
                } else {
                    matching.sortedBy { it.label }
                }
            }
        pager.reset()
        renderPage()
    }

    private fun renderPage() {
        pager.update(relations.size)
        relationEmpty.text =
            when {
                source == null || selection == null -> ""
                relations.isNotEmpty() -> ""
                relationSearch.text.isNotBlank() -> "No results match these filters"
                else -> "No observed relationships in this direction"
            }
        relationModel.clear()
        SourceUiModel.page(relations, pager.page).forEach(relationModel::addElement)
        if (relationModel.isEmpty) evidence.showEvidence(emptyList(), 0)
    }
}
