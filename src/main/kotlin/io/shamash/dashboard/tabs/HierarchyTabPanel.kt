package io.shamash.dashboard.tabs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmIndex
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Tab A — Hierarchy
 *
 * - Search box (class name / fqcn)
 * - Superclass chain (up)
 * - Subclasses (down) + "show transitive subclasses"
 * - Implemented interfaces
 *
 * Deterministic + driven entirely by AsmIndex.
 */
class AsmHierarchyTabPanel(@Suppress("unused") private val project: Project) : JPanel(BorderLayout()) {

    // ---------- UI ----------
    private val searchField = JBTextField().apply {
        emptyText.text = "Search class (fqcn or simple name), e.g. com.foo.Bar or Bar"
    }

    private val resultsModel = DefaultListModel<String>()
    private val resultsList = JBList(resultsModel).apply {
        visibleRowCount = 12
    }

    private val selectedLabel = JBLabel("No class selected.")

    private val superLabel = JBLabel("Superclass chain")
    private val superModel = DefaultListModel<String>()
    private val superList = JBList(superModel)

    private val interfacesLabel = JBLabel("Implemented interfaces")
    private val interfacesModel = DefaultListModel<String>()
    private val interfacesList = JBList(interfacesModel)

    private val transitiveToggle = JBCheckBox("Show transitive subclasses", false)

    private val subclassesLabel = JBLabel("Subclasses")
    private val subclassesModel = DefaultListModel<String>()
    private val subclassesList = JBList(subclassesModel)

    // ---------- data (updated on index refresh) ----------
    private var index: AsmIndex? = null
    private var classesByInternal: Map<String, AsmClassInfo> = emptyMap()
    private var subclassesOf: Map<String, Set<String>> = emptyMap()

    private var allFqcns: List<String> = emptyList() // cached for search
    private var selectedInternal: String? = null

    init {
        val left = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(resultsList), BorderLayout.CENTER)
            preferredSize = Dimension(320, 200)
        }

        val right = JPanel(BorderLayout()).apply {
            val top = JPanel(BorderLayout()).apply {
                add(selectedLabel, BorderLayout.CENTER)
            }

            val mid = JPanel(BorderLayout()).apply {
                val upPanel = titledListPanel(superLabel, superList)
                val ifacePanel = titledListPanel(interfacesLabel, interfacesList)

                val upAndIface = JPanel(BorderLayout()).apply {
                    add(upPanel, BorderLayout.CENTER)
                    add(ifacePanel, BorderLayout.SOUTH)
                }
                add(upAndIface, BorderLayout.CENTER)
            }

            val downPanel = JPanel(BorderLayout()).apply {
                val controls = JPanel(BorderLayout()).apply {
                    add(transitiveToggle, BorderLayout.WEST)
                }
                add(controls, BorderLayout.NORTH)
                add(titledListPanel(subclassesLabel, subclassesList), BorderLayout.CENTER)
            }

            val stack = JPanel(BorderLayout()).apply {
                add(top, BorderLayout.NORTH)
                add(mid, BorderLayout.CENTER)
                add(downPanel, BorderLayout.SOUTH)
            }

            add(stack, BorderLayout.CENTER)
        }

        val splitter = Splitter(false, 0.33f).apply {
            firstComponent = left
            secondComponent = right
        }

        add(splitter, BorderLayout.CENTER)

        // ---------- listeners ----------
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updateSearchResults(searchField.text.orEmpty())
            }
        })

        // Enter = choose first result if available
        searchField.addActionListener {
            if (resultsModel.size() > 0) {
                resultsList.selectedIndex = 0
                resultsList.ensureIndexIsVisible(0)
                resultsList.selectedValue?.let { fqcn ->
                    showClass(fqcnToInternal(fqcn))
                }
            } else {
                // also try direct resolve
                resolveToInternal(searchField.text.orEmpty())?.let { showClass(it) }
            }
        }

        resultsList.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val fqcn = resultsList.selectedValue ?: return@addListSelectionListener
            showClass(fqcnToInternal(fqcn))
        }

        transitiveToggle.addActionListener {
            selectedInternal?.let { refreshDetails(it) }
        }

        // Double-click navigation within lists
        attachDoubleClickToSelect(superList)
        attachDoubleClickToSelect(interfacesList)
        attachDoubleClickToSelect(subclassesList)
    }

    fun onIndexUpdated(newIndex: AsmIndex) {
        index = newIndex
        classesByInternal = newIndex.classes

        // Build reverse "super -> children" index (includes project + dependency jars)
        val tmp = HashMap<String, MutableSet<String>>(classesByInternal.size)
        for ((_, info) in classesByInternal) {
            val sup = info.superInternalName ?: continue
            tmp.computeIfAbsent(sup) { LinkedHashSet() }.add(info.internalName)
        }
        subclassesOf = tmp.mapValues { it.value.toSet() }

        allFqcns = classesByInternal.keys
            .asSequence()
            .map { it.replace('/', '.') }
            .sorted()
            .toList()

        updateSearchResults(searchField.text.orEmpty())

        // keep selection stable across rescans if possible
        selectedInternal?.let { internal ->
            if (classesByInternal.containsKey(internal)) refreshDetails(internal)
            else clearDetails("Selected class is no longer indexed.")
        }
    }

    // ---------- UI helpers ----------
    private fun titledListPanel(title: JBLabel, list: JBList<String>): JPanel {
        return JPanel(BorderLayout()).apply {
            add(title, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
        }
    }

    private fun attachDoubleClickToSelect(list: JBList<String>) {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val value = list.selectedValue ?: return
                val internal = fqcnToInternal(value)
                if (classesByInternal.containsKey(internal)) {
                    showClass(internal)
                }
            }
        })
    }

    // ---------- Search ----------
    private fun updateSearchResults(rawQuery: String) {
        resultsModel.clear()
        val q = rawQuery.trim()
        if (q.isEmpty()) return
        if (classesByInternal.isEmpty()) return

        // Exact internal/fqcn hit? Prefer it.
        resolveToInternal(q)?.let { internal ->
            val fqcn = internal.replace('/', '.')
            resultsModel.addElement(fqcn)
            return
        }

        val qLower = q.lowercase()

        // Simple scoring: exact > endsWith(simple) > startsWith > contains
        fun score(fqcn: String): Int {
            val s = fqcn.lowercase()
            if (s == qLower) return 0
            if (s.endsWith(".$qLower")) return 1
            if (s.startsWith(qLower)) return 2
            if (s.contains(qLower)) return 3
            return 99
        }

        allFqcns.asSequence()
            .map { it to score(it) }
            .filter { it.second < 99 }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }
                .thenBy { it.first.length }
                .thenBy { it.first })
            .take(200)
            .forEach { (fqcn, _) -> resultsModel.addElement(fqcn) }
    }

    private fun resolveToInternal(input: String): String? {
        val raw = input.trim()
        if (raw.isEmpty()) return null

        val cleaned = raw.removeSuffix(".class")

        // fqcn or internal
        val candidate =
            when {
                cleaned.contains('/') -> cleaned
                cleaned.contains('.') -> cleaned.replace('.', '/')
                else -> null
            }

        if (candidate != null && classesByInternal.containsKey(candidate)) return candidate

        // Simple name fallback: match by end segment
        if (!cleaned.contains('.') && !cleaned.contains('/')) {
            val simple = cleaned
            val hit = classesByInternal.keys.firstOrNull { internal ->
                internal.endsWith("/$simple") || internal.endsWith("$$simple") || internal.endsWith("/$simple.class")
            }
            if (hit != null) return hit
        }

        return null
    }

    // ---------- Selection + Details ----------
    private fun showClass(internal: String) {
        if (!classesByInternal.containsKey(internal)) {
            clearDetails("Not indexed: ${internal.replace('/', '.')}")
            return
        }
        selectedInternal = internal
        refreshDetails(internal)
    }

    private fun refreshDetails(internal: String) {
        val info = classesByInternal[internal] ?: run {
            clearDetails("Not indexed: ${internal.replace('/', '.')}")
            return
        }

        selectedLabel.text = "Selected: ${info.fqcn}  (module=${info.moduleName ?: "?"}, origin=${info.originDisplayName})"

        // Super chain (up)
        superModel.clear()
        val chain = computeSuperChain(internal)
        superLabel.text = "Superclass chain (${chain.size})"
        for (fqcn in chain) superModel.addElement(fqcn)

        // Interfaces
        interfacesModel.clear()
        val ifaces = info.interfaceInternalNames.map { it.replace('/', '.') }.sorted()
        interfacesLabel.text = "Implemented interfaces (${ifaces.size})"
        for (fqcn in ifaces) interfacesModel.addElement(fqcn)

        // Subclasses (down)
        subclassesModel.clear()
        val direct = subclassesOf[internal].orEmpty()
        val subclasses =
            if (transitiveToggle.isSelected) computeTransitiveSubclasses(internal)
            else direct

        val sorted = subclasses.asSequence()
            .map { it.replace('/', '.') }
            .sorted()
            .toList()

        subclassesLabel.text =
            if (transitiveToggle.isSelected)
                "Subclasses (transitive: ${sorted.size}, direct: ${direct.size})"
            else
                "Subclasses (direct: ${sorted.size})"

        for (fqcn in sorted) subclassesModel.addElement(fqcn)
    }

    private fun clearDetails(message: String) {
        selectedInternal = null
        selectedLabel.text = message
        superLabel.text = "Superclass chain"
        interfacesLabel.text = "Implemented interfaces"
        subclassesLabel.text = "Subclasses"
        superModel.clear()
        interfacesModel.clear()
        subclassesModel.clear()
    }

    private fun computeSuperChain(startInternal: String): List<String> {
        // root-first list of fqcn strings, including the selected class at the end.
        val out = ArrayList<String>(16)
        var currentInternal: String? = startInternal
        var currentInfo: AsmClassInfo? = classesByInternal[currentInternal]

        // Build bottom-up then reverse
        val tmp = ArrayList<String>(16)
        while (currentInternal != null) {
            tmp.add(currentInternal.replace('/', '.'))
            val sup = currentInfo?.superInternalName
            if (sup == null) break
            currentInternal = sup
            currentInfo = classesByInternal[currentInternal]
            // If superclass isn't indexed, we still include it and stop.
            if (currentInfo == null) {
                tmp.add(currentInternal.replace('/', '.'))
                break
            }
        }
        for (i in tmp.size - 1 downTo 0) out.add(tmp[i])
        return out
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

    private fun fqcnToInternal(fqcn: String): String = fqcn.replace('.', '/')
}
