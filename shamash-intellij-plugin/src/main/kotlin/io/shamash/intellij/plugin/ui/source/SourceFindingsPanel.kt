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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import io.shamash.psi.core.source.SourcePackageDependency
import io.shamash.psi.core.source.SourcePackageId
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FlowLayout
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.KeyStroke
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane

internal class SourceFindingsPanel(project: Project, private val showExplore: () -> Unit) : JPanel(BorderLayout()) {
    private data class Choice<T>(val key: T?, val label: String) {
        override fun toString(): String = label
    }

    private var source: SourceUiModel? = null
    private var filtered: List<SourceUiModel.FindingRow> = emptyList()
    private var selectedId: String? = null
    private var suppressFilters = false
    private val search = JBTextField()
    private val modules = JBComboBox<Choice<String>>()
    private val packages = JBComboBox<Choice<SourcePackageId>>()
    private val clear = JButton("Clear filters")
    private val listModel = DefaultListModel<SourceUiModel.FindingRow>()
    val findingList = sourceList<SourceUiModel.FindingRow>("Package cycle findings") {
        "Cycle · " + it.title + " · " + it.summary
    }
    private val pager = PageControls()
    private val empty = JBLabel("Understand your project's dependencies")
    private val detailsTitle = JBLabel("Select a cycle to investigate")
    private val observation = wrappedText()
    private val members = wrappedText()
    private val memberPager = PageControls()
    private var memberIds: List<SourcePackageId> = emptyList()
    private val guidance = wrappedText()
    private var edges: List<SourcePackageDependency> = emptyList()
    private val edgePager = PageControls()
    private val edgeModel = DefaultListModel<SourcePackageDependency>()
    val edgeList = sourceList<SourcePackageDependency>("Directed edges of the representative cycle") {
        SourceUiModel.qualified(it.from) + " → " + SourceUiModel.qualified(it.target) +
            " · " + it.distinctEntityRelationships + " distinct source relationships · " +
            it.occurrences + " occurrences"
    }
    private val evidence = SourceEvidencePanel(project)
    private val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
    private val explore = JButton("Explore dependencies")
    private val back = JButton("Back to findings")

    init {
        val filters = JPanel(FlowLayout(FlowLayout.LEFT, 6, 3))
        search.emptyText.text = "Search findings, packages or declarations"
        search.columns = 18
        search.accessibleContext.accessibleName = "Search all package cycle findings"
        modules.accessibleContext.accessibleName = "Filter cycle findings by IDE module"
        packages.accessibleContext.accessibleName = "Filter cycle findings by package"
        filters.add(search)
        filters.add(modules)
        filters.add(packages)
        filters.add(clear)

        val left = JPanel(BorderLayout(0, 4))
        val leftTop = JPanel(BorderLayout())
        leftTop.add(filters, BorderLayout.NORTH)
        leftTop.add(empty, BorderLayout.SOUTH)
        left.add(leftTop, BorderLayout.NORTH)
        findingList.model = listModel
        left.add(JBScrollPane(findingList), BorderLayout.CENTER)
        val leftBottom = JPanel(BorderLayout())
        leftBottom.add(pager, BorderLayout.NORTH)
        leftBottom.add(explore, BorderLayout.SOUTH)
        left.add(leftBottom, BorderLayout.SOUTH)

        val right = JPanel()
        right.layout = BoxLayout(right, BoxLayout.Y_AXIS)
        right.add(back)
        right.add(JBLabel("What we found"))
        right.add(detailsTitle)
        right.add(observation)
        right.add(JBLabel("Complete cyclic group membership"))
        right.add(members)
        right.add(memberPager)
        right.add(JBLabel("Evidence · representative directed cycle"))
        edgeList.model = edgeModel
        edgeList.visibleRowCount = 5
        val edgesScroll = JBScrollPane(edgeList)
        edgesScroll.preferredSize = Dimension(200, 125)
        right.add(edgesScroll)
        right.add(edgePager)
        right.add(evidence)
        evidence.preferredSize = Dimension(200, 140)
        right.add(guidance)
        val detailsScroll = JBScrollPane(right)
        detailsScroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER

        split.leftComponent = left
        split.rightComponent = detailsScroll
        split.resizeWeight = 0.42
        split.isContinuousLayout = false
        add(split, BorderLayout.CENTER)

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                split.orientation = if (width < 560) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
                split.resizeWeight = if (width < 560) 0.48 else 0.42
            }
        })

        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshFilter()
            override fun removeUpdate(e: DocumentEvent?) = refreshFilter()
            override fun changedUpdate(e: DocumentEvent?) = refreshFilter()
        })
        modules.addActionListener {
            if (!suppressFilters) {
                updatePackages()
                refreshFilter()
            }
        }
        packages.addActionListener { if (!suppressFilters) refreshFilter() }
        clear.addActionListener {
            search.text = ""
            modules.selectedIndex = 0
            packages.selectedIndex = 0
            refreshFilter()
        }
        pager.previous.addActionListener { pager.change(-1, filtered.size); renderPage() }
        pager.next.addActionListener { pager.change(1, filtered.size); renderPage() }
        memberPager.previous.addActionListener {
            memberPager.change(-1, memberIds.size)
            renderMembers()
        }
        memberPager.next.addActionListener {
            memberPager.change(1, memberIds.size)
            renderMembers()
        }
        edgePager.previous.addActionListener { edgePager.change(-1, edges.size); renderEdges() }
        edgePager.next.addActionListener { edgePager.change(1, edges.size); renderEdges() }
        findingList.addListSelectionListener { if (!it.valueIsAdjusting) selectFinding() }
        edgeList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val edge = edgeList.selectedValue
                if (edge == null) evidence.showEvidence(emptyList(), 0)
                else evidence.showEvidence(
                    edge.witnesses.map { witness ->
                        SourceEvidence(witness.reference, witness.from, witness.from.signature + " → " + witness.target.signature)
                    },
                    edge.occurrences,
                )
            }
        }
        explore.addActionListener { showExplore() }
        back.addActionListener { returnToFindings() }
        val escape = KeyStroke.getKeyStroke("ESCAPE")
        for (component in listOf(edgeList, evidence.list)) {
            component.inputMap.put(escape, "backToFindings")
            component.actionMap.put("backToFindings", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = returnToFindings()
            })
        }
        accessibleContext.accessibleName = "Findings"
        resetFilters()
        renderPage()
    }

    private fun returnToFindings() {
        findingList.requestFocusInWindow()
        if (split.orientation == JSplitPane.VERTICAL_SPLIT) split.setDividerLocation(0.65)
    }

    fun setGraph(next: SourceUiModel?) {
        val oldId = selectedId
        source = next
        resetFilters(preserve = true)
        selectedId = oldId
        refreshFilter()
        if (oldId != null && next?.findings?.none { it.id == oldId } == true) {
            clearDetails("Previously selected cycle no longer appears in this analysis.")
        }
    }

    private fun resetFilters(preserve: Boolean = false) {
        suppressFilters = true
        val priorModule = if (preserve) (modules.selectedItem as? Choice<*>)?.key else null
        val all = listOf(Choice<String>(null, "All modules")) +
            source?.moduleIds.orEmpty().map { Choice(it, it) }
        modules.model = DefaultComboBoxModel(all.toTypedArray())
        modules.selectedItem = all.firstOrNull { it.key == priorModule } ?: all.first()
        suppressFilters = false
        updatePackages()
    }

    private fun updatePackages() {
        suppressFilters = true
        val selected = (packages.selectedItem as? Choice<*>)?.key
        val module = (modules.selectedItem as? Choice<String>)?.key
        val all = listOf(Choice<SourcePackageId>(null, "All packages")) +
            source?.packageIds.orEmpty()
                .filter { module == null || it.moduleId == module }
                .map { Choice(it, SourceUiModel.qualified(it)) }
        packages.model = DefaultComboBoxModel(all.toTypedArray())
        packages.selectedItem = all.firstOrNull { it.key == selected } ?: all.first()
        suppressFilters = false
    }

    private fun refreshFilter() {
        if (suppressFilters) return
        val module = (modules.selectedItem as? Choice<String>)?.key
        val pkg = (packages.selectedItem as? Choice<SourcePackageId>)?.key
        filtered = source?.filteredFindings(search.text, module, pkg).orEmpty()
        pager.reset()
        selectedId?.let { id ->
            val index = filtered.indexOfFirst { it.id == id }
            if (index >= 0) pager.selectPage(index, filtered.size)
        }
        renderPage()
    }

    private fun renderPage() {
        pager.update(filtered.size)
        val current = SourceUiModel.page(filtered, pager.page)
        listModel.clear()
        current.forEach(listModel::addElement)
        empty.text =
            when {
                source == null -> "Understand your project's dependencies"
                source?.findings?.isEmpty() == true ->
                    if (source?.graph?.complete == true)
                        "No package cycles found in the analyzed source. Explore dependencies."
                    else "No cycles observed in the available evidence; analysis is incomplete."
                filtered.isEmpty() -> "No results match these filters"
                else -> filtered.size.toString() + " observed cyclic groups · sorted by size, not risk"
            }
        if (current.isEmpty() && selectedId == null) clearDetails(
            if (source?.findings?.isEmpty() == true)
                if (source?.graph?.complete == true) "No package cycles found in the analyzed source."
                else "No cycles observed; analysis is incomplete."
            else if (source == null) "" else "No results match these filters",
        )
        val index = current.indexOfFirst { it.id == selectedId }
        if (index >= 0) {
            findingList.selectedIndex = index
        } else {
            findingList.clearSelection()
            if (selectedId == null && current.isNotEmpty()) findingList.selectedIndex = 0
            else if (selectedId != null) clearDetails("Selected cycle is not on this page or is hidden by filters")
        }
    }

    private fun selectFinding() {
        val finding = findingList.selectedValue ?: return
        selectedId = finding.id
        detailsTitle.text = "Cyclic group of " + finding.cycle.members.size + " packages"
        val cycle = finding.cycle
        observation.text =
            "Observed: " + SourceUiModel.qualified(cycle.members.first()) + " and " +
                (cycle.members.size - 1) + " other package(s) are mutually reachable through " +
                "resolved source dependencies. This is one advisory cyclic group."
        memberIds = cycle.members
        memberPager.reset()
        renderMembers()
        guidance.text = SourceGuidance.CYCLE_EXPLANATION
        edges = finding.cycle.witness
        edgePager.reset()
        renderEdges()
    }

    private fun renderMembers() {
        memberPager.update(memberIds.size)
        val page = SourceUiModel.page(memberIds, memberPager.page)
        val first = memberPager.page * SourceUiModel.PAGE_SIZE + 1
        members.text = if (page.isEmpty()) "" else
            "Packages " + first + "–" + (first + page.size - 1) + " of " + memberIds.size +
                ":\n" + page.joinToString("\n", transform = SourceUiModel::qualified)
    }

    private fun renderEdges() {
        edgePager.update(edges.size)
        edgeModel.clear()
        SourceUiModel.page(edges, edgePager.page).forEach(edgeModel::addElement)
        if (!edgeModel.isEmpty) edgeList.selectedIndex = 0
        else evidence.showEvidence(emptyList(), 0)
    }

    private fun clearDetails(message: String) {
        detailsTitle.text = message.ifEmpty { "Select a cycle to investigate" }
        observation.text = ""
        members.text = ""
        memberIds = emptyList()
        memberPager.reset()
        memberPager.update(0)
        guidance.text = ""
        edges = emptyList()
        edgeModel.clear()
        edgePager.reset()
        edgePager.update(0)
        evidence.showEvidence(emptyList(), 0)
    }
}
