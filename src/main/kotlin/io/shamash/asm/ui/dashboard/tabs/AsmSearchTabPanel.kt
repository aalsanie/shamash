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
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.codeStyle.NameUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.Tree
import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.model.AsmOrigin
import io.shamash.asm.ui.dashboard.export.ExportUtil
import io.shamash.asm.ui.dashboard.export.HierarchyExport
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Tab A — Hierarchy (Shamash-grade)
 *
 * Left:
 * - IDE-native search field + matcher (CamelHumps / partial / fqcn)
 * - Search results show context (project vs library, package, module)
 *
 * Right:
 * - Single hierarchy tree:
 *   - Supertypes (up)
 *   - Interfaces
 *   - Subtypes (down) + transitive toggle
 *
 * Deterministic + driven entirely by AsmIndex.
 */
class AsmSearchTabPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    // ui controls
    private val searchField =
        SearchTextField(false).apply {
            textEditor.emptyText.text =
                "Search class (fqcn / simple / CamelHumps), e.g. OrderSvc, com.foo.OrderService"
        }

    private val exportJsonBtn = JButton("Export JSON")
    private val exportXmlBtn = JButton("Export XML")

    private val includeLibrariesToggle = JBCheckBox("Include libraries", true)

    private val resultsModel = DefaultListModel<SearchHit>()
    private val resultsList =
        JBList(resultsModel).apply {
            visibleRowCount = 12
            cellRenderer =
                object : ColoredListCellRenderer<SearchHit>() {
                    override fun customizeCellRenderer(
                        list: JList<out SearchHit>,
                        value: SearchHit,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean,
                    ) {
                        icon =
                            when (value.origin) {
                                AsmOrigin.MODULE_OUTPUT -> AllIcons.Nodes.Class
                                AsmOrigin.DEPENDENCY_JAR -> AllIcons.Nodes.PpLib
                            }

                        append(value.simpleName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

                        if (value.packageName.isNotEmpty()) {
                            append(value.packageName, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        } else {
                            append("(default package)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }

                        value.moduleName?.let {
                            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                            append("[$it]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        }
                    }
                }
        }

    private val selectedLabel =
        JBLabel("No class selected.").apply {
            foreground = com.intellij.ui.JBColor.GRAY
        }

    private val transitiveToggle = JBCheckBox("Transitive subtypes", false)

    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode(NodeMessage("No class selected.")))
    private val hierarchyTree =
        Tree(treeModel).apply {
            cellRenderer = HierarchyTreeRenderer()
            isRootVisible = true
            showsRootHandles = true
        }

    // ---------- data (updated on index refresh) ----------
    private var index: AsmIndex? = null
    private var classesByInternal: Map<String, AsmClassInfo> = emptyMap()
    private var subclassesOf: Map<String, Set<String>> = emptyMap()

    private var allHits: List<SearchHit> = emptyList()
    private var selectedInternal: String? = null

    init {
        val left =
            JPanel(BorderLayout()).apply {
                val top =
                    JPanel(BorderLayout()).apply {
                        add(searchField, BorderLayout.CENTER)
                    }
                val toggles =
                    JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                        add(includeLibrariesToggle)
                    }
                add(top, BorderLayout.NORTH)
                add(toggles, BorderLayout.CENTER)
                add(ScrollPaneFactory.createScrollPane(resultsList), BorderLayout.SOUTH)
                preferredSize = Dimension(380, 200)
            }

        val right =
            JPanel(BorderLayout()).apply {
                val header =
                    JPanel(BorderLayout()).apply {
                        add(selectedLabel, BorderLayout.CENTER)
                    }
                val controls =
                    JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                        add(transitiveToggle)
                    }

                val north =
                    JPanel(BorderLayout()).apply {
                        add(header, BorderLayout.NORTH)
                        add(controls, BorderLayout.SOUTH)
                    }

                add(north, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(hierarchyTree), BorderLayout.CENTER)
            }

        val splitter =
            Splitter(false, 0.38f).apply {
                firstComponent = left
                secondComponent = right
            }
        add(splitter, BorderLayout.CENTER)
        exportJsonBtn.addActionListener { exportHierarchy(ExportUtil.Format.JSON) }
        exportXmlBtn.addActionListener { exportHierarchy(ExportUtil.Format.XML) }

        val exportBar =
            JPanel(BorderLayout()).apply {
                border = com.intellij.util.ui.JBUI.Borders.empty(6, 8)
                val row =
                    JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                        add(exportJsonBtn)
                        add(exportXmlBtn)
                    }
                add(row, BorderLayout.WEST)
            }
        add(exportBar, BorderLayout.SOUTH)

        TreeUIHelper.getInstance().installTreeSpeedSearch(
            hierarchyTree,
            { path ->
                val node = path.lastPathComponent as? DefaultMutableTreeNode
                val obj = node?.userObject
                when (obj) {
                    is NodeHeader -> obj.text
                    is NodeSection -> obj.text
                    is NodeClassRef -> obj.fqcn
                    is NodeMessage -> obj.text
                    else -> obj?.toString() ?: ""
                }
            },
            true,
        )

        // ---------- listeners ----------
        searchField.textEditor.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    updateSearchResults(searchField.text.orEmpty())
                }
            },
        )

        // Enter = choose first result if available
        searchField.textEditor.addActionListener {
            if (resultsModel.size() > 0) {
                resultsList.selectedIndex = 0
                resultsList.ensureIndexIsVisible(0)
                resultsList.selectedValue?.let { hit -> showClass(hit.internalName) }
            } else {
                resolveToInternal(searchField.text.orEmpty())?.let { showClass(it) }
            }
        }

        includeLibrariesToggle.addActionListener {
            updateSearchResults(searchField.text.orEmpty())
        }

        resultsList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val hit = resultsList.selectedValue ?: return@addListSelectionListener
            showClass(hit.internalName)
        }

        resultsList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val hit = resultsList.selectedValue ?: return
                    showClass(hit.internalName)
                    navigateIfInProject(hit.internalName)
                }
            },
        )

        transitiveToggle.addActionListener {
            selectedInternal?.let { rebuildTree(it) }
        }

        hierarchyTree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val path = hierarchyTree.selectionPath ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val obj = node.userObject
                    if (obj is NodeClassRef) {
                        if (classesByInternal.containsKey(obj.internalName)) {
                            showClass(obj.internalName)
                            navigateIfInProject(obj.internalName)
                        }
                    }
                }
            },
        )
    }

    fun onIndexUpdated(newIndex: AsmIndex) {
        index = newIndex
        classesByInternal = newIndex.classes

        // Build reverse "super -> children" index
        val tmp = HashMap<String, MutableSet<String>>(classesByInternal.size)
        for ((_, info) in classesByInternal) {
            val sup = info.superInternalName ?: continue
            tmp.computeIfAbsent(sup) { LinkedHashSet() }.add(info.internalName)
        }
        subclassesOf = tmp.mapValues { it.value.toSet() }

        // Precompute search hits once per scan (fast UI)
        allHits =
            classesByInternal.values.map { info ->
                SearchHit(
                    internalName = info.internalName,
                    fqcn = info.fqcn,
                    simpleName = info.fqcn.substringAfterLast('.'),
                    packageName = info.fqcn.substringBeforeLast('.', missingDelimiterValue = ""),
                    moduleName = info.moduleName,
                    origin = info.origin,
                )
            }

        updateSearchResults(searchField.text.orEmpty())

        // keep selection stable across rescans if possible
        selectedInternal?.let { internal ->
            if (classesByInternal.containsKey(internal)) {
                rebuildTree(internal)
            } else {
                clearSelection("Selected class is no longer indexed.")
            }
        }
    }

    // ---------- Search ----------
    private fun updateSearchResults(rawQuery: String) {
        resultsModel.clear()
        val q = rawQuery.trim()
        if (q.isEmpty()) return
        if (classesByInternal.isEmpty()) return

        // Exact internal/fqcn hit? Prefer it.
        resolveToInternal(q)?.let { internal ->
            val info = classesByInternal[internal]
            resultsModel.addElement(
                SearchHit(
                    internalName = internal,
                    fqcn = internal.replace('/', '.'),
                    simpleName = internal.substringAfterLast('/'),
                    packageName = internal.replace('/', '.').substringBeforeLast('.', missingDelimiterValue = ""),
                    moduleName = info?.moduleName,
                    origin = info?.origin ?: AsmOrigin.MODULE_OUTPUT,
                ),
            )
            return
        }

        val matcher = NameUtil.buildMatcher(q).build()
        val allowLibs = includeLibrariesToggle.isSelected

        fun match(hit: SearchHit): Boolean =
            matcher.matches(hit.simpleName) ||
                    matcher.matches(hit.fqcn) ||
                    hit.simpleName.contains(q, ignoreCase = true) ||
                    hit.fqcn.contains(q, ignoreCase = true)

        fun score(hit: SearchHit): Int {
            val fq = hit.fqcn
            val sn = hit.simpleName
            return when {
                fq.equals(q, ignoreCase = true) -> 0
                sn.equals(q, ignoreCase = true) -> 1
                fq.endsWith(".$q", ignoreCase = true) -> 2
                sn.startsWith(q, ignoreCase = true) -> 3
                fq.startsWith(q, ignoreCase = true) -> 4
                else -> 10
            }
        }

        allHits
            .asSequence()
            .filter { allowLibs || it.origin == AsmOrigin.MODULE_OUTPUT }
            .filter { match(it) }
            .map { it to score(it) }
            .sortedWith(
                compareBy<Pair<SearchHit, Int>> { it.second }
                    .thenBy { it.first.simpleName.length }
                    .thenBy { it.first.fqcn },
            ).take(250)
            .forEach { (hit, _) -> resultsModel.addElement(hit) }
    }

    private fun resolveToInternal(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        val cleaned = raw.removeSuffix(".class")

        val candidate =
            when {
                cleaned.contains('/') -> cleaned
                cleaned.contains('.') -> cleaned.replace('.', '/')
                else -> null
            }

        if (candidate != null && classesByInternal.containsKey(candidate)) return candidate

        if (!cleaned.contains('.') && !cleaned.contains('/')) {
            val simple = cleaned
            val hit =
                classesByInternal.keys.firstOrNull { internal ->
                    internal.endsWith("/$simple") || internal.endsWith("$$simple")
                }
            if (hit != null) return hit
        }

        return null
    }

    // ---------- Selection + Tree ----------
    private fun showClass(internal: String) {
        if (!classesByInternal.containsKey(internal)) {
            clearSelection("Not indexed: ${internal.replace('/', '.')}")
            return
        }
        selectedInternal = internal
        rebuildTree(internal)
    }

    private fun rebuildTree(internal: String) {
        val info =
            classesByInternal[internal] ?: run {
                clearSelection("Not indexed: ${internal.replace('/', '.')}")
                return
            }

        selectedLabel.text =
            "${info.fqcn}   •   ${info.originDisplayName}${info.moduleName?.let { "   •   module=$it" } ?: ""}"
        selectedLabel.foreground = com.intellij.ui.JBColor.foreground()

        val root = DefaultMutableTreeNode(NodeHeader(info.fqcn, internal))

        // Supertypes
        val superChain = computeSuperChainInternal(internal)
        val superNode = DefaultMutableTreeNode(NodeSection("Supertypes (${superChain.size})"))
        // show Object -> ... -> selected
        for (i in superChain.size - 1 downTo 0) {
            superNode.add(classNode(superChain[i]))
        }

        // Interfaces
        val ifaceInternals = info.interfaceInternalNames.distinct().sorted()
        val ifaceNode = DefaultMutableTreeNode(NodeSection("Interfaces (${ifaceInternals.size})"))
        ifaceInternals.forEach { ifaceNode.add(classNode(it, forceInterfaceIcon = true)) }

        // Subtypes
        val direct = subclassesOf[internal].orEmpty()
        val subs =
            if (transitiveToggle.isSelected) {
                computeTransitiveSubclasses(internal)
            } else {
                direct
            }

        val subTitle =
            if (transitiveToggle.isSelected) {
                "Subtypes (transitive ${subs.size}, direct ${direct.size})"
            } else {
                "Subtypes (direct ${subs.size})"
            }

        val subNode = DefaultMutableTreeNode(NodeSection(subTitle))
        val maxShow = 2000
        val sorted = subs.asSequence().sorted().toList()
        val shown = sorted.take(maxShow)
        shown.forEach { subNode.add(classNode(it)) }
        if (sorted.size > maxShow) {
            subNode.add(DefaultMutableTreeNode(NodeMessage("… truncated (${sorted.size - maxShow} more)")))
        }

        root.add(superNode)
        root.add(ifaceNode)
        root.add(subNode)

        treeModel.setRoot(root)
        treeModel.reload()

        // Expand root + sections
        hierarchyTree.expandRow(0)
        hierarchyTree.expandRow(1)
        hierarchyTree.expandRow(2)
        hierarchyTree.expandRow(3)
    }

    private fun clearSelection(message: String) {
        selectedInternal = null
        selectedLabel.text = message
        selectedLabel.foreground = com.intellij.ui.JBColor.GRAY

        treeModel.setRoot(DefaultMutableTreeNode(NodeMessage(message)))
        treeModel.reload()
    }

    private fun classNode(
        internal: String,
        forceInterfaceIcon: Boolean = false,
    ): DefaultMutableTreeNode {
        val fqcn = internal.replace('/', '.')
        val info = classesByInternal[internal]
        return DefaultMutableTreeNode(
            NodeClassRef(
                internalName = internal,
                fqcn = fqcn,
                origin = info?.origin,
                moduleName = info?.moduleName,
                forceInterfaceIcon = forceInterfaceIcon,
            ),
        )
    }

    private fun computeSuperChainInternal(startInternal: String): List<String> {
        val tmp = ArrayList<String>(16)
        var currentInternal: String? = startInternal
        var currentInfo: AsmClassInfo? = classesByInternal[currentInternal]

        while (currentInternal != null) {
            tmp.add(currentInternal)
            val sup = currentInfo?.superInternalName
            if (sup == null) break
            currentInternal = sup
            currentInfo = classesByInternal[currentInternal]
            if (currentInfo == null) {
                tmp.add(currentInternal)
                break
            }
        }
        return tmp
    }

    private fun computeTransitiveSubclasses(rootInternal: String): Set<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        subclassesOf[rootInternal].orEmpty().forEach { queue.add(it) }

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (!seen.add(cur)) continue
            subclassesOf[cur].orEmpty().forEach { queue.add(it) }
        }
        return seen
    }

    // ---------- Navigation ----------
    private fun navigateIfInProject(internal: String) {
        val fqcn = internal.replace('/', '.')
        val psiClass =
            JavaPsiFacade
                .getInstance(project)
                .findClass(fqcn, GlobalSearchScope.projectScope(project))
                ?: return

        val vf = psiClass.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, vf, psiClass.textOffset).navigate(true)
    }

    // ---------- Models ----------
    private data class SearchHit(
        val internalName: String,
        val fqcn: String,
        val simpleName: String,
        val packageName: String,
        val moduleName: String?,
        val origin: AsmOrigin,
    )

    private data class NodeHeader(
        val text: String,
        val internalName: String,
    )

    private data class NodeSection(
        val text: String,
    )

    private data class NodeMessage(
        val text: String,
    )

    private data class NodeClassRef(
        val internalName: String,
        val fqcn: String,
        val origin: AsmOrigin?,
        val moduleName: String?,
        val forceInterfaceIcon: Boolean,
    )

    private class HierarchyTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: javax.swing.JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val obj = node.userObject

            when (obj) {
                is NodeHeader -> {
                    icon = AllIcons.Nodes.Class
                    append(obj.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is NodeSection -> {
                    icon = AllIcons.Nodes.Folder
                    append(obj.text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is NodeMessage -> {
                    icon = AllIcons.General.Information
                    append(obj.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is NodeClassRef -> {
                    val simple = obj.fqcn.substringAfterLast('.')
                    val pkg = obj.fqcn.substringBeforeLast('.', missingDelimiterValue = "")

                    icon =
                        when {
                            obj.forceInterfaceIcon -> AllIcons.Nodes.Interface
                            obj.origin == AsmOrigin.DEPENDENCY_JAR -> AllIcons.Nodes.PpLib
                            else -> AllIcons.Nodes.Class
                        }

                    append(simple, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (pkg.isNotEmpty()) {
                        append("  $pkg", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    obj.moduleName?.let {
                        append("  [$it]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                else -> {
                    append(obj?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }

    private fun exportHierarchy(format: ExportUtil.Format) {
        val internal = selectedInternal ?: return
        val info = classesByInternal[internal] ?: return

        val superChain = computeSuperChainInternal(internal).map { it.replace('/', '.') }
        val ifaces =
            info.interfaceInternalNames
                .distinct()
                .sorted()
                .map { it.replace('/', '.') }

        val direct = subclassesOf[internal].orEmpty()
        val subs = if (transitiveToggle.isSelected) computeTransitiveSubclasses(internal) else direct
        val subList =
            subs
                .asSequence()
                .sorted()
                .map { it.replace('/', '.') }
                .toList()

        HierarchyExport.exportSnapshot(
            project = project,
            format = format,
            info = info,
            transitiveSubtypes = transitiveToggle.isSelected,
            superChainFqcn = superChain,
            interfacesFqcn = ifaces,
            subtypesFqcn = subList,
        )
    }
}
