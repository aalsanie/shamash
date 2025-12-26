package io.shamash.asm.ui.dashboard.tabs

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBComboBoxLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import io.shamash.asm.model.AsmClassInfo
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.scan.ExternalBucketResolver
import org.objectweb.asm.Opcodes
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class AsmHotspotsTabPanel(private val project: Project) : JPanel(BorderLayout()) {

    //TODO: move labels and massages out to a unified place
    private enum class Mode(val label: String) {
        GOD("God classes (score)"),
        CHAINS("Deep inheritance chains"),
        FANOUT("High fan-out"),
        DRIFT("Style drift (Service↔Controller)")
        ;

        override fun toString(): String = label
    }

    private data class Row(
        val fqcn: String,
        val internal: String,
        val score: Int,
        val depth: Int,
        val methods: Int,
        val publicMethods: Int,
        val fields: Int,
        val instructions: Int,
        val fanOut: Int,
        val fanIn: Int,
        val reason: String
    )

    private val modeBox = JComboBox(Mode.entries.toTypedArray())
    private val projectOnlyEdges = JBCheckBox("Project-only edges", true)
    private val excludeJdkFromFan = JBCheckBox("Exclude JDK from fan-out", true)

    private val header = JBLabel("Run scan to compute hotspots. Hint: higher score = hotter (worse).")

    private val details = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    private val model = ListTableModel<Row>(
        arrayOf(
            col("Score") { it.score },
            col("Class") { it.fqcn },
            col("Depth") { it.depth },
            col("Methods") { it.methods },
            col("Public") { it.publicMethods },
            col("Fields") { it.fields },
            col("Instr") { it.instructions },
            col("FanOut") { it.fanOut },
            col("FanIn") { it.fanIn },
            col("Reason") { it.reason }
        ),
        mutableListOf()
    )

    private val table = TableView(model).apply {
        setAutoCreateRowSorter(true)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private var index: AsmIndex? = null

    init {
        val top = JPanel(BorderLayout()).apply {
            //I'm a label "yay!" bored af today, I will finish shamash dashboard P.s. I love FE stuff
            val boxy = JBComboBoxLabel()
            boxy.text = "View"
            val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
                add(boxy)
                add(modeBox)
                add(projectOnlyEdges)
                add(excludeJdkFromFan)
            }
            add(controls, BorderLayout.NORTH)
            add(header, BorderLayout.SOUTH)
        }

        val bottom = JPanel(BorderLayout()).apply {
            add(JBLabel("Evidence / Explanation"), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(details), BorderLayout.CENTER)
        }

        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        modeBox.addActionListener { recomputeAndRender() }
        projectOnlyEdges.addActionListener { recomputeAndRender() }
        excludeJdkFromFan.addActionListener { recomputeAndRender() }

        table.selectionModel.addListSelectionListener {
            val row = table.selectedObject ?: return@addListSelectionListener
            details.text = buildString {
                appendLine(row.fqcn)
                appendLine("score=${row.score}, depth=${row.depth}, fanOut=${row.fanOut}, fanIn=${row.fanIn}")
                appendLine("methods=${row.methods} (public=${row.publicMethods}), fields=${row.fields}, instr=${row.instructions}")
                if (row.reason.isNotBlank()) {
                    appendLine()
                    appendLine("Reason:")
                    appendLine(row.reason)
                }
            }
        }

        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2) return
                val row = table.selectedObject ?: return
                navigateToSource(row.internal)
            }
        })
    }

    fun onIndexUpdated(newIndex: AsmIndex) {
        index = newIndex
        recomputeAndRender()
    }

    private fun recomputeAndRender() {
        val idx = index ?: return
        val classes = idx.classes
        if (classes.isEmpty()) return

        val projectSet = classes.keys

        // Reverse graph for fan-in (project only)
        val fanIn = HashMap<String, Int>(classes.size)
        for ((from, refs) in idx.references) {
            if (projectOnlyEdges.isSelected && from !in projectSet) continue
            for (to in refs) {
                if (to !in projectSet) continue
                fanIn[to] = (fanIn[to] ?: 0) + 1
            }
        }

        // Depth memoization
        val depthMemo = HashMap<String, Int>(classes.size)
        fun depthOf(internal: String): Int {
            depthMemo[internal]?.let { return it }
            val sup = classes[internal]?.superInternalName ?: run {
                depthMemo[internal] = 1
                return 1
            }
            val d = if (sup in classes) 1 + depthOf(sup) else 2 // 2 means “has external parent”
            depthMemo[internal] = d
            return d
        }

        fun isPublic(access: Int) = (access and Opcodes.ACC_PUBLIC) != 0

        fun roleOf(info: AsmClassInfo): String {
            val fq = info.fqcn
            val simple = fq.substringAfterLast('.')
            return when {
                fq.contains(".controller.", ignoreCase = true) || simple.endsWith("Controller") -> "Controller"
                fq.contains(".service.", ignoreCase = true) || simple.endsWith("Service") -> "Service"
                fq.contains(".repository.", ignoreCase = true) || simple.endsWith("Repository") -> "Repository"
                fq.contains(".dao.", ignoreCase = true) || simple.endsWith("Dao") -> "Dao"
                fq.contains(".util.", ignoreCase = true) || simple.endsWith("Util") -> "Util"
                else -> "Unknown"
            }
        }

        fun controllerSignals(refs: Set<String>): List<String> {
            val sig = mutableListOf<String>()
            val controllerRefs = refs.count { it.contains("/controller/") || it.endsWith("Controller") }
            val webRefs = refs.count { it.contains("/web/") || it.contains("springframework/web") || it.contains("jakarta/servlet") || it.contains("javax/servlet") }
            if (controllerRefs > 0) sig += "references controller types ($controllerRefs)"
            if (webRefs > 0) sig += "references web/servlet types ($webRefs)"
            return sig
        }

        fun daoSignals(refs: Set<String>): List<String> {
            val sig = mutableListOf<String>()
            val repoRefs = refs.count { it.contains("/repository/") || it.endsWith("Repository") }
            val daoRefs = refs.count { it.contains("/dao/") || it.endsWith("Dao") }
            if (repoRefs > 0) sig += "references repository types ($repoRefs)"
            if (daoRefs > 0) sig += "references DAO types ($daoRefs)"
            return sig
        }

        fun fanOutCount(info: AsmClassInfo): Int {
            val refs = info.referencedInternalNames
            return if (!excludeJdkFromFan.isSelected) {
                refs.size
            } else {
                refs.count { ExternalBucketResolver.bucketForInternalName(it).id != "ext:JDK" }
            }
        }

        fun publicMethodCount(info: AsmClassInfo): Int =
            info.methods.count { it.name != "<init>" && it.name != "<clinit>" && isPublic(it.access) }

        fun godScore(info: AsmClassInfo, fin: Int, fout: Int, depth: Int): Int {
            // deterministic weights (tunable)
            val m = info.methods.size
            val pm = publicMethodCount(info)
            val f = info.fieldCount
            val ins = info.instructionCount / 50 // scale
            return (m * 3) + (pm * 2) + (f * 2) + ins + (fout * 2) + (fin * 2) + (depth - 1)
        }

        val rows = when (modeBox.selectedItem as Mode) {
            Mode.GOD -> {
                classes.values.asSequence()
                    .filter { !projectOnlyEdges.isSelected || it.internalName in projectSet }
                    .map { info ->
                        val d = depthOf(info.internalName)
                        val fout = fanOutCount(info)
                        val fin = fanIn[info.internalName] ?: 0
                        Row(
                            fqcn = info.fqcn,
                            internal = info.internalName,
                            score = godScore(info, fin, fout, d),
                            depth = d,
                            methods = info.methods.size,
                            publicMethods = publicMethodCount(info),
                            fields = info.fieldCount,
                            instructions = info.instructionCount,
                            fanOut = fout,
                            fanIn = fin,
                            reason = "Weighted: methods, publicMethods, fields, instr/50, fanOut, fanIn, depth."
                        )
                    }
                    .sortedByDescending { it.score }
                    .take(40)
                    .toList()
            }

            Mode.CHAINS -> {
                classes.values.asSequence()
                    .map { info ->
                        val d = depthOf(info.internalName)
                        val fout = fanOutCount(info)
                        val fin = fanIn[info.internalName] ?: 0
                        Row(
                            fqcn = info.fqcn,
                            internal = info.internalName,
                            score = d,
                            depth = d,
                            methods = info.methods.size,
                            publicMethods = publicMethodCount(info),
                            fields = info.fieldCount,
                            instructions = info.instructionCount,
                            fanOut = fout,
                            fanIn = fin,
                            reason = if (info.superInternalName == null) "" else "super=${info.superInternalName.replace('/', '.')}"
                        )
                    }
                    .filter { it.depth >= 6 }
                    .sortedByDescending { it.depth }
                    .take(60)
                    .toList()
            }

            Mode.FANOUT -> {
                classes.values.asSequence()
                    .map { info ->
                        val d = depthOf(info.internalName)
                        val fout = fanOutCount(info)
                        val fin = fanIn[info.internalName] ?: 0
                        Row(
                            fqcn = info.fqcn,
                            internal = info.internalName,
                            score = fout,
                            depth = d,
                            methods = info.methods.size,
                            publicMethods = publicMethodCount(info),
                            fields = info.fieldCount,
                            instructions = info.instructionCount,
                            fanOut = fout,
                            fanIn = fin,
                            reason = "fanOut derived from bytecode refs (optionally excluding JDK)."
                        )
                    }
                    .sortedByDescending { it.fanOut }
                    .take(60)
                    .toList()
            }

            Mode.DRIFT -> {
                classes.values.asSequence()
                    .mapNotNull { info ->
                        val role = roleOf(info)
                        val refs = info.referencedInternalNames
                        val pub = publicMethodCount(info)

                        // Service behaving like controller (and reverse smell)
                        val reason = when (role) {
                            "Service" -> {
                                val sig = controllerSignals(refs)
                                if (sig.isNotEmpty() && pub >= 8) {
                                    "Service drift: publicMethods=$pub, " + sig.joinToString(", ")
                                } else null
                            }
                            "Controller" -> {
                                val sig = daoSignals(refs)
                                if (sig.isNotEmpty()) {
                                    "Controller drift: " + sig.joinToString(", ")
                                } else null
                            }
                            else -> null
                        } ?: return@mapNotNull null

                        val d = depthOf(info.internalName)
                        val fout = fanOutCount(info)
                        val fin = fanIn[info.internalName] ?: 0

                        Row(
                            fqcn = info.fqcn,
                            internal = info.internalName,
                            score = (pub * 2) + fout + fin,
                            depth = d,
                            methods = info.methods.size,
                            publicMethods = pub,
                            fields = info.fieldCount,
                            instructions = info.instructionCount,
                            fanOut = fout,
                            fanIn = fin,
                            reason = reason
                        )
                    }
                    .sortedByDescending { it.score }
                    .take(60)
                    .toList()
            }
        }

        header.text = "Hotspots: showing ${rows.size} rows (double-click to navigate)."
        model.items = rows.toMutableList()
        model.fireTableDataChanged()
        details.text = ""
    }

    private fun navigateToSource(internal: String) {
        val fqcn = internal.replace('/', '.')
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(fqcn, GlobalSearchScope.projectScope(project))
            ?: return

        val vf = psiClass.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, vf, psiClass.textOffset).navigate(true)
    }

    private fun <T> col(name: String, get: (Row) -> T) = object : ColumnInfo<Row, T>(name) {
        override fun valueOf(item: Row): T = get(item)
    }
}
