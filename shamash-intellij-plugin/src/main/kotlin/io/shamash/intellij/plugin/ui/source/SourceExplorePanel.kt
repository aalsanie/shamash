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
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
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
    private val direction = JComboBox(arrayOf("Uses", "Used by", "External dependencies"))
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
    private val split = JBSplitter(false, 0.38f)
    internal val detailTabs = JBTabbedPane()
    private val summaryButton = JButton("About selection")
    internal val summaryPopup = JPopupMenu()
    internal val summaryContent = wrappedText()
    private var horizontalProportion = 0.38f
    private var verticalProportion = 0.45f

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
        empty.rows = 1
        left.add(compactMessageScroll(empty), BorderLayout.SOUTH)

        val detail = JPanel(BorderLayout(0, JBUI.scale(4)))
        val toolbar = JPanel(BorderLayout(JBUI.scale(4), 0))
        toolbar.add(back, BorderLayout.WEST)
        toolbar.add(summaryButton, BorderLayout.EAST)
        summaryButton.isEnabled = false
        summaryContent.accessibleContext.accessibleName = "Selected source summary and related cycles"
        val summaryScroll = JBScrollPane(summaryContent)
        summaryScroll.preferredSize = java.awt.Dimension(JBUI.scale(360), JBUI.scale(180))
        summaryPopup.add(summaryScroll)
        summaryPopup.accessibleContext.accessibleName = "Selected source summary"
        summaryButton.accessibleContext.accessibleName = "Show selected source summary"
        summaryButton.addActionListener { showSummary() }

        direction.accessibleContext.accessibleName = "Dependency direction"
        val switches = JPanel(BorderLayout(JBUI.scale(4), 0))
        switches.add(direction, BorderLayout.CENTER)
        switches.add(sort, BorderLayout.EAST)
        relationSearch.emptyText.text = "Search the selected relationship list"
        relationSearch.accessibleContext.accessibleName = "Search all relationships for selected source"
        val top = JPanel(BorderLayout(0, JBUI.scale(4)))
        val fieldsLayout = GridBagLayout()
        val fields = JPanel(fieldsLayout)
        val switchConstraints =
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, JBUI.scale(4))
            }
        val searchConstraints =
            GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            }
        fields.add(switches, switchConstraints)
        fields.add(relationSearch, searchConstraints)
        top.add(toolbar, BorderLayout.NORTH)
        top.add(fields, BorderLayout.SOUTH)
        detail.add(top, BorderLayout.NORTH)

        relationList.model = relationModel
        val relationships = JPanel(BorderLayout(0, JBUI.scale(4)))
        relationships.add(JBScrollPane(relationList), BorderLayout.CENTER)
        val footer = JPanel(BorderLayout(0, JBUI.scale(2)))
        relationEmpty.rows = 1
        footer.add(relationEmpty, BorderLayout.NORTH)
        footer.add(pager, BorderLayout.SOUTH)
        relationships.add(footer, BorderLayout.SOUTH)
        detailTabs.addTab("Relationships", relationships)
        detailTabs.addTab("Source evidence", evidence)
        detailTabs.accessibleContext.accessibleName = "Explore relationship and evidence views"
        detail.add(detailTabs, BorderLayout.CENTER)

        split.firstComponent = left
        split.secondComponent = detail
        add(split, BorderLayout.CENTER)
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    val vertical = width < 560
                    if (split.orientation != vertical) {
                        switchConstraints.weightx = if (vertical) 1.0 else 0.0
                        switchConstraints.insets = Insets(0, 0, 0, if (vertical) 0 else JBUI.scale(4))
                        searchConstraints.gridx = if (vertical) 0 else 1
                        searchConstraints.gridy = if (vertical) 1 else 0
                        searchConstraints.weightx = 1.0
                        searchConstraints.insets = Insets(if (vertical) JBUI.scale(4) else 0, 0, 0, 0)
                        fieldsLayout.setConstraints(switches, switchConstraints)
                        fieldsLayout.setConstraints(relationSearch, searchConstraints)
                        fields.revalidate()
                        if (split.orientation) {
                            verticalProportion = split.proportion
                        } else {
                            horizontalProportion = split.proportion
                        }
                        split.orientation = vertical
                        split.proportion = if (vertical) verticalProportion else horizontalProportion
                    }
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
            sort.text = if (mostDependencies) "Sort: most" else "Sort: name"
            sort.accessibleContext.accessibleDescription =
                if (mostDependencies) "Sorted by most dependencies" else "Sorted by name"
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
        getAccessibleContext().accessibleName = "Explore"
        refreshTree()
    }

    private fun returnToTree() {
        tree.requestFocusInWindow()
        if (split.orientation) split.proportion = 0.65f
    }

    fun setGraph(next: SourceUiModel?) {
        source = next
        refreshTree()
        if (selection != null && tree.selectionPath == null) {
            summaryButton.isEnabled = false
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
        if (selection == null) summaryButton.isEnabled = false
        if (!next.isEmpty()) tree.expandRow(0)
        val path = selection?.let(next::pathFor)
        if (path != null) {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } else if (selection != null) {
            summaryButton.isEnabled = false
            summary.text = "This package or declaration is not in the current view."
            related.text = ""
            relations = emptyList()
            renderPage()
        }
    }

    private fun showSummary() {
        summaryPopup.show(summaryButton, 0, summaryButton.height)
        summaryContent.requestFocusInWindow()
    }

    private fun showSelection() {
        val source = source ?: return
        val selected = selection ?: return
        summaryButton.isEnabled = true
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
        summaryContent.text = summary.text + "\n\n" + related.text
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

    fun dispose() {
        evidence.dispose()
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
