/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.intellij.plugin.asm.ui.analysis

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.shamash.asm.core.analysis.AnalysisResult
import io.shamash.asm.core.analysis.GraphAnalysisResult
import io.shamash.asm.core.analysis.HotspotEntry
import io.shamash.asm.core.analysis.HotspotKind
import io.shamash.asm.core.analysis.HotspotsResult
import io.shamash.asm.core.analysis.ScoringResult
import io.shamash.asm.core.analysis.SeverityBand
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class ShamashAsmAnalysisPanel(
    private val project: Project,
) : JBPanel<ShamashAsmAnalysisPanel>(BorderLayout()) {
    private val mapper = jacksonObjectMapper()

    private val header = JBLabel("Analysis")
    private val status = JBLabel("")

    private val graphsSummary = JBLabel("Graphs: (no data)")
    private val cyclesList = JBList<String>(emptyList())

    private val hotspotsModel = HotspotsTableModel()
    private val hotspotsTable = JBTable(hotspotsModel)

    private val classScoresModel = ClassScoresTableModel()
    private val classScoresTable = JBTable(classScoresModel)

    private val packageScoresModel = PackageScoresTableModel()
    private val packageScoresTable = JBTable(packageScoresModel)

    private val contentTabs = JBTabbedPane()

    init {
        border = JBUI.Borders.empty(10)

        header.font = header.font.deriveFont(header.font.size2D + 2f)
        status.foreground = UIUtil.getLabelDisabledForeground()

        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.isOpaque = false
        top.add(header)
        top.add(Box.createVerticalStrut(4))
        top.add(status)
        add(top, BorderLayout.NORTH)

        contentTabs.addTab("Graphs", buildGraphsPanel())
        contentTabs.addTab("Hotspots", buildHotspotsPanel())
        contentTabs.addTab("Scoring", buildScoringPanel())

        add(contentTabs, BorderLayout.CENTER)

        installNavigation()
        refresh()
    }

    fun refresh() {
        val state =
            ShamashAsmUiStateService.getInstance(project).getState() ?: run {
                renderEmpty("No scan yet")
                return
            }

        val scan = state.scanResult
        val engine = scan?.engine
        val export = engine?.export
        val inMemory = engine?.analysis

        val graphsPath = export?.analysisGraphsPath
        val hotspotsPath = export?.analysisHotspotsPath
        val scoresPath = export?.analysisScoresPath

        val anySidecar = graphsPath != null || hotspotsPath != null || scoresPath != null
        val anyInMemory = inMemory != null && !inMemory.isEmpty

        if (!anySidecar && anyInMemory) {
            status.text = "Showing in-memory analysis (exports are disabled)."
            render(inMemory?.graphs, inMemory?.hotspots, inMemory?.scoring)
            return
        }

        if (!anySidecar && !anyInMemory) {
            renderEmpty(
                "No analysis data found. Enable analysis (engine.analysis.*) or enable export.artifacts.analysis.enabled and re-run scan.",
            )
            return
        }

        loadInBackground(graphsPath, hotspotsPath, scoresPath, inMemory)
    }

    private fun loadInBackground(
        graphsPath: Path?,
        hotspotsPath: Path?,
        scoresPath: Path?,
        inMemory: AnalysisResult?,
    ) {
        status.text = "Loading analysis artifacts…"

        object : Task.Backgroundable(project, "Shamash: Loading analysis", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // Prefer exported sidecars when present, but fall back to the in-memory analysis
                // (engine already computed these even when export is disabled).
                val graphs = graphsPath?.let { readGraphs(it) } ?: inMemory?.graphs
                val hotspots = hotspotsPath?.let { readHotspots(it) } ?: inMemory?.hotspots
                val scoring = scoresPath?.let { readScoring(it) } ?: inMemory?.scoring

                ApplicationManager.getApplication().invokeLater {
                    val usedAnySidecar =
                        (graphsPath != null && graphs != null) ||
                            (hotspotsPath != null && hotspots != null) ||
                            (scoresPath != null && scoring != null)

                    status.text =
                        when {
                            usedAnySidecar -> ""
                            graphs != null || hotspots != null || scoring != null ->
                                "Showing in-memory analysis (analysis artifacts were not exported)."
                            else -> "No analysis data found."
                        }

                    render(graphs, hotspots, scoring)
                }
            }
        }.queue()
    }

    private fun readGraphs(path: Path): GraphAnalysisResult? = readSection(path, "graphs", GraphAnalysisResult::class.java)

    private fun readHotspots(path: Path): HotspotsResult? = readSection(path, "hotspots", HotspotsResult::class.java)

    private fun readScoring(path: Path): ScoringResult? = readSection(path, "scoring", ScoringResult::class.java)

    private fun <T> readSection(
        path: Path,
        field: String,
        clazz: Class<T>,
    ): T? {
        return try {
            if (!Files.exists(path)) return null
            val root = mapper.readTree(path.toFile()) ?: return null
            val node = root.get(field) ?: return null
            mapper.treeToValue(node, clazz)
        } catch (_: Throwable) {
            null
        }
    }

    private fun render(
        graphs: GraphAnalysisResult?,
        hotspots: HotspotsResult?,
        scoring: ScoringResult?,
    ) {
        // Graphs
        if (graphs == null) {
            graphsSummary.text = "Graphs: (no data)"
            cyclesList.setListData(emptyArray())
        } else {
            graphsSummary.text =
                "Graphs: nodes=${graphs.nodeCount}, edges=${graphs.edgeCount}, SCCs=${graphs.sccCount}, cyclicSCCs=${graphs.cyclicSccs.size}"
            val cycles =
                graphs.representativeCycles
                    .take(25)
                    .map { it.joinToString(" → ") }
                    .toTypedArray()
            cyclesList.setListData(cycles)
        }

        // Hotspots
        hotspotsModel.setData(hotspots)

        // Scoring
        classScoresModel.setData(scoring)
        packageScoresModel.setData(scoring)
    }

    private fun renderEmpty(msg: String) {
        status.text = msg
        graphsSummary.text = "Graphs: (no data)"
        cyclesList.setListData(emptyArray())
        hotspotsModel.setData(null)
        classScoresModel.setData(null)
        packageScoresModel.setData(null)
    }

    private fun buildGraphsPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)

        val top = JPanel()
        top.layout = BoxLayout(top, BoxLayout.Y_AXIS)
        top.isOpaque = false
        top.add(graphsSummary)
        top.add(Box.createVerticalStrut(8))

        val cyclesLabel = JBLabel("Top cycles")
        cyclesLabel.font = cyclesLabel.font.deriveFont(cyclesLabel.font.style or java.awt.Font.BOLD)
        top.add(cyclesLabel)

        root.add(top, BorderLayout.NORTH)

        cyclesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        cyclesList.visibleRowCount = 12
        root.add(JBScrollPane(cyclesList), BorderLayout.CENTER)

        return root
    }

    private fun buildHotspotsPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)

        hotspotsTable.setShowGrid(false)
        hotspotsTable.intercellSpacing = Dimension(0, 0)
        hotspotsTable.autoCreateRowSorter = true
        hotspotsTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        root.add(JBScrollPane(hotspotsTable), BorderLayout.CENTER)
        return root
    }

    private fun buildScoringPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)

        val tabs = JBTabbedPane()

        classScoresTable.setShowGrid(false)
        classScoresTable.intercellSpacing = Dimension(0, 0)
        classScoresTable.autoCreateRowSorter = true
        classScoresTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        packageScoresTable.setShowGrid(false)
        packageScoresTable.intercellSpacing = Dimension(0, 0)
        packageScoresTable.autoCreateRowSorter = true
        packageScoresTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        tabs.addTab("God classes", JBScrollPane(classScoresTable))
        tabs.addTab("Packages", JBScrollPane(packageScoresTable))
        root.add(tabs, BorderLayout.CENTER)

        return root
    }

    private fun installNavigation() {
        hotspotsTable.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val row = hotspotsTable.selectedRow
                    if (row < 0) return
                    val modelRow = hotspotsTable.convertRowIndexToModel(row)
                    val target = hotspotsModel.getTargetFqnOrNull(modelRow) ?: return
                    openClass(target)
                }
            },
        )

        classScoresTable.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val row = classScoresTable.selectedRow
                    if (row < 0) return
                    val modelRow = classScoresTable.convertRowIndexToModel(row)
                    val target = classScoresModel.getClassFqnOrNull(modelRow) ?: return
                    openClass(target)
                }
            },
        )

        cyclesList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount != 2) return
                    val sel = cyclesList.selectedValue ?: return
                    val first = sel.substringBefore(" → ").trim()
                    if (first.isBlank()) return
                    openClass(first)
                }
            },
        )
    }

    private fun openClass(classFqn: String) {
        ApplicationManager.getApplication().invokeLater {
            val psiClass =
                ApplicationManager.getApplication().runReadAction<com.intellij.psi.PsiClass?> {
                    JavaPsiFacade.getInstance(project).findClass(classFqn, GlobalSearchScope.projectScope(project))
                } ?: return@invokeLater

            val file = psiClass.containingFile?.virtualFile ?: return@invokeLater
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    private class HotspotsTableModel : AbstractTableModel() {
        private data class Row(
            val kind: HotspotKind,
            val id: String,
            val maxValue: Int,
            val reasons: String,
        )

        private var rows: List<Row> = emptyList()

        fun setData(result: HotspotsResult?) {
            rows = buildRows(result)
            fireTableDataChanged()
        }

        fun getTargetFqnOrNull(modelRow: Int): String? {
            val r = rows.getOrNull(modelRow) ?: return null
            return if (r.kind == HotspotKind.CLASS) r.id else null
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String =
            when (column) {
                0 -> "Kind"
                1 -> "Id"
                2 -> "Max"
                3 -> "Reasons"
                else -> ""
            }

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.kind.name
                1 -> r.id
                2 -> r.maxValue
                3 -> r.reasons
                else -> ""
            }
        }

        private fun buildRows(result: HotspotsResult?): List<Row> {
            if (result == null) return emptyList()

            val all = ArrayList<Row>(result.classHotspots.size + result.packageHotspots.size)

            fun addAll(entries: List<HotspotEntry>) {
                for (e in entries) {
                    val reasons =
                        e.reasons
                            .sortedBy { it.metric.name }
                            .joinToString(", ") { r -> "${r.metric.name}=${r.value} (#${r.rank})" }
                    all.add(Row(e.kind, e.id, e.maxMetricValue, reasons))
                }
            }

            addAll(result.classHotspots)
            addAll(result.packageHotspots)

            return all.sortedWith(compareByDescending<Row> { it.maxValue }.thenBy { it.id })
        }
    }

    private class ClassScoresTableModel : AbstractTableModel() {
        private data class Row(
            val classFqn: String,
            val packageName: String,
            val score: Double,
            val band: SeverityBand,
        )

        private var rows: List<Row> = emptyList()

        fun setData(result: ScoringResult?) {
            val god = result?.godClass?.rows.orEmpty()
            rows = god.map { Row(it.classFqn, it.packageName, it.score, it.band) }
            fireTableDataChanged()
        }

        fun getClassFqnOrNull(modelRow: Int): String? = rows.getOrNull(modelRow)?.classFqn

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String =
            when (column) {
                0 -> "Class"
                1 -> "Package"
                2 -> "Score"
                3 -> "Band"
                else -> ""
            }

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.classFqn
                1 -> r.packageName
                2 -> String.format("%.3f", r.score)
                3 -> r.band.name
                else -> ""
            }
        }
    }

    private class PackageScoresTableModel : AbstractTableModel() {
        private data class Row(
            val packageName: String,
            val score: Double,
            val band: SeverityBand,
        )

        private var rows: List<Row> = emptyList()

        fun setData(result: ScoringResult?) {
            val pkgs = result?.overall?.rows.orEmpty()
            rows = pkgs.map { Row(it.packageName, it.score, it.band) }
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 3

        override fun getColumnName(column: Int): String =
            when (column) {
                0 -> "Package"
                1 -> "Score"
                2 -> "Band"
                else -> ""
            }

        override fun getValueAt(
            rowIndex: Int,
            columnIndex: Int,
        ): Any {
            val r = rows[rowIndex]
            return when (columnIndex) {
                0 -> r.packageName
                1 -> String.format("%.3f", r.score)
                2 -> r.band.name
                else -> ""
            }
        }
    }
}
