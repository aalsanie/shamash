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
package io.shamash.intellij.plugin.asm.ui.facts

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.shamash.asm.core.export.facts.FactsClassRecord
import io.shamash.asm.core.export.facts.FactsEdgeRecord
import io.shamash.asm.core.export.facts.FactsMetaRecord
import io.shamash.asm.core.export.facts.FactsReader
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.actions.AsmActionUtil
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ShamashAsmFactsPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    private val loadToken = AtomicInteger(0)

    private val layout = CardLayout()
    private val cards = JPanel(layout)

    private val emptyPanel = JPanel(BorderLayout())
    private val contentPanel = JPanel(BorderLayout())

    private val filterField = JBTextField()
    private val statusLabel = JBLabel()

    private val classesModel = FactsClassesTableModel()
    private val edgesModel = FactsEdgesTableModel()

    private val classesTable = JBTable(classesModel)
    private val edgesTable = JBTable(edgesModel)

    private val summaryCards = JPanel(GridLayout(1, 4, 10, 0))
    private val classesCard = metricCard("Classes", "-")
    private val methodsCard = metricCard("Methods", "-")
    private val fieldsCard = metricCard("Fields", "-")
    private val edgesCard = metricCard("Edges", "-")

    private var loaded: LoadedFacts? = null

    init {
        border = JBUI.Borders.empty(10)

        summaryCards.add(classesCard)
        summaryCards.add(methodsCard)
        summaryCards.add(fieldsCard)
        summaryCards.add(edgesCard)

        emptyPanel.border = JBUI.Borders.empty(10)
        emptyPanel.add(buildEmptyState(), BorderLayout.CENTER)

        contentPanel.border = JBUI.Borders.empty(0)
        contentPanel.add(buildContentUi(), BorderLayout.CENTER)

        cards.add(emptyPanel, CARD_EMPTY)
        cards.add(contentPanel, CARD_CONTENT)

        add(cards, BorderLayout.CENTER)

        ShamashAsmUiStateService.getInstance(project).addListener(this) { _ ->
            refresh()
        }

        refresh()
    }

    fun component(): JComponent = this

    fun refresh() {
        if (project.isDisposed) return

        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { refresh() }
            return
        }

        val state = ShamashAsmUiStateService.getInstance(project).getState()
        val scan = state?.scanResult
        val engine = scan?.engine

        // Prefer exported facts file (streamable).
        val exportFactsPath = engine?.export?.factsPath
        val filePath = exportFactsPath?.takeIf { Files.exists(it) }

        if (filePath != null) {
            showContent()
            loadFromFileIfNeeded(filePath)
            return
        }

        // Fallback to in-memory facts only if present.
        val facts = engine?.facts
        if (facts != null) {
            showContent()
            loadFromMemoryIfNeeded(facts)
            return
        }

        // Missing facts.
        loaded = null
        updateStatus(
            "Facts artifact not found. Enable export.artifacts.facts.enabled, or run a scan with CLI --export-facts.",
        )
        updateMetrics(null)
        classesModel.setRows(emptyList())
        edgesModel.setRows(emptyList())
        showEmpty()
    }

    override fun dispose() {
        // listener auto-unregistered
    }

    private fun buildEmptyState(): JComponent {
        val wrapper = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(20) }

        val msg =
            JBLabel(
                "No facts available for the latest run.\n" +
                    "Facts are exported as facts.jsonl.gz when export.artifacts.facts.enabled is true.",
            ).apply {
                border = JBUI.Borders.emptyBottom(12)
            }

        val enable =
            JButton("Enable facts export").apply {
                addActionListener {
                    ShamashAsmToolWindowController.getInstance(project).select(ShamashAsmToolWindowController.Tab.CONFIG)
                    AsmActionUtil.notify(
                        project,
                        "Enable facts export",
                        "Enable export.artifacts.facts.enabled in your ASM config (or use CLI --export-facts).",
                        com.intellij.notification.NotificationType.INFORMATION,
                    )
                }
            }

        wrapper.add(msg, BorderLayout.NORTH)
        wrapper.add(enable, BorderLayout.WEST)
        return wrapper
    }

    private fun buildContentUi(): JComponent {
        val root = JPanel(BorderLayout())

        // Toolbar
        val toolbar =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(10)
            }

        filterField.emptyText.text = "Filter (substring or: from->to)"
        filterField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilter()

                override fun removeUpdate(e: DocumentEvent) = applyFilter()

                override fun changedUpdate(e: DocumentEvent) = applyFilter()
            },
        )

        val openBtn =
            JButton("Open exported facts file").apply {
                addActionListener { openFactsFile() }
            }

        val reloadBtn =
            JButton("Reload").apply {
                addActionListener {
                    loaded = null
                    refresh()
                }
            }

        val buttons =
            JPanel().apply {
                add(openBtn)
                add(reloadBtn)
            }

        toolbar.add(filterField, BorderLayout.CENTER)
        toolbar.add(buttons, BorderLayout.EAST)

        // Summary + status
        val top =
            JPanel(BorderLayout()).apply {
                add(summaryCards, BorderLayout.NORTH)
                add(statusLabel, BorderLayout.SOUTH)
            }

        // Tables
        classesTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        classesTable.autoCreateRowSorter = true
        edgesTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        edgesTable.autoCreateRowSorter = true

        val splitter =
            OnePixelSplitter(true, 0.55f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(classesTable)
                secondComponent = ScrollPaneFactory.createScrollPane(edgesTable)
            }

        root.add(toolbar, BorderLayout.NORTH)
        root.add(top, BorderLayout.CENTER)
        root.add(splitter, BorderLayout.SOUTH)

        return root
    }

    private fun metricCard(
        title: String,
        value: String,
    ): JPanel {
        val p =
            JPanel(BorderLayout()).apply {
                border =
                    JBUI.Borders.compound(
                        JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                        JBUI.Borders.empty(10),
                    )
            }
        val t = JBLabel(title)
        val v = JBLabel(value)
        v.font = v.font.deriveFont(v.font.size2D + 6f)
        p.add(t, BorderLayout.NORTH)
        p.add(v, BorderLayout.CENTER)
        return p
    }

    private fun showEmpty() {
        layout.show(cards, CARD_EMPTY)
    }

    private fun showContent() {
        layout.show(cards, CARD_CONTENT)
    }

    private fun updateMetrics(stats: Stats?) {
        setCardValue(classesCard, stats?.classes?.toString() ?: "-")
        setCardValue(methodsCard, stats?.methods?.toString() ?: "-")
        setCardValue(fieldsCard, stats?.fields?.toString() ?: "-")
        setCardValue(edgesCard, stats?.edges?.toString() ?: "-")
    }

    private fun setCardValue(
        card: JPanel,
        value: String,
    ) {
        val label = card.components.filterIsInstance<JBLabel>().getOrNull(1) ?: return
        label.text = value
    }

    private fun updateStatus(text: String) {
        statusLabel.text = text
        statusLabel.border = JBUI.Borders.emptyTop(10)
    }

    private fun openFactsFile() {
        val p = loaded?.sourcePath ?: return
        val vfile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(p) ?: return
        FileEditorManager.getInstance(project).openFile(vfile, true)
    }

    private fun loadFromFileIfNeeded(path: Path) {
        val prev = loaded
        if (prev != null && prev.sourcePath == path) {
            applyFilter()
            return
        }

        val token = loadToken.incrementAndGet()
        updateStatus("Loading facts from: ${path.fileName} …")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Loading Shamash Facts", false) {
                override fun run(indicator: ProgressIndicator) {
                    val maxRowsClasses = MAX_SAMPLE_CLASSES
                    val maxRowsEdges = MAX_SAMPLE_EDGES

                    val classSample = ArrayList<FactsClassRecord>(minOf(512, maxRowsClasses))
                    val edgeSample = ArrayList<FactsEdgeRecord>(minOf(512, maxRowsEdges))

                    val fanIn = BoundedCounter<String>(MAX_KEYS)
                    val fanOut = BoundedCounter<String>(MAX_KEYS)
                    val pkgClasses = BoundedCounter<String>(MAX_KEYS)
                    val pkgEdgesFrom = BoundedCounter<String>(MAX_KEYS)

                    var meta: FactsMetaRecord? = null

                    val read =
                        FactsReader.read(
                            path = path,
                            onMeta = { m -> meta = m },
                            onClass = { c ->
                                if (classSample.size < maxRowsClasses) classSample.add(c)
                                pkgClasses.increment(c.packageName)
                            },
                            onEdge = { e ->
                                if (edgeSample.size < maxRowsEdges) edgeSample.add(e)
                                fanOut.increment(e.from)
                                fanIn.increment(e.to)
                                pkgEdgesFrom.increment(packageOf(e.from))
                            },
                        )

                    val engineStats = currentEngineStats()
                    val stats =
                        Stats(
                            classes = engineStats?.classes ?: read.classCount,
                            methods = engineStats?.methods ?: 0,
                            fields = engineStats?.fields ?: 0,
                            edges = engineStats?.edges ?: read.edgeCount,
                        )

                    val result =
                        LoadedFacts(
                            sourcePath = path,
                            meta = meta,
                            stats = stats,
                            classSample = classSample,
                            edgeSample = edgeSample,
                            fanIn = fanIn.snapshot(),
                            fanOut = fanOut.snapshot(),
                            droppedFanIn = fanIn.dropped(),
                            droppedFanOut = fanOut.dropped(),
                            topPkgClasses = pkgClasses.top(15),
                            droppedPkgClasses = pkgClasses.dropped(),
                            topPkgEdgesFrom = pkgEdgesFrom.top(15),
                            droppedPkgEdgesFrom = pkgEdgesFrom.dropped(),
                        )

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        if (token != loadToken.get()) return@invokeLater
                        loaded = result
                        updateMetrics(result.stats)
                        updateStatus(buildStatusLine(result))
                        applyFilter()
                    }
                }
            },
        )
    }

    private fun loadFromMemoryIfNeeded(facts: FactIndex) {
        val prev = loaded
        if (prev != null && prev.sourcePath == null) {
            applyFilter()
            return
        }

        val engineStats = currentEngineStats()
        val stats =
            Stats(
                classes = engineStats?.classes ?: facts.classes.size,
                methods = engineStats?.methods ?: facts.methods.size,
                fields = engineStats?.fields ?: facts.fields.size,
                edges = engineStats?.edges ?: facts.edges.size,
            )

        val maxRowsClasses = MAX_SAMPLE_CLASSES
        val maxRowsEdges = MAX_SAMPLE_EDGES

        // ClassFact does not include method/field counts; compute for the sampled classes only.
        val sampledClassFacts = facts.classes.take(maxRowsClasses)
        val sampledFqns = sampledClassFacts.asSequence().map { it.fqName }.toHashSet()

        val methodCounts = HashMap<String, Int>(minOf(sampledFqns.size, 4096))
        for (m in facts.methods) {
            val ownerFqn = m.owner.fqName
            if (ownerFqn in sampledFqns) {
                methodCounts[ownerFqn] = (methodCounts[ownerFqn] ?: 0) + 1
            }
        }

        val fieldCounts = HashMap<String, Int>(minOf(sampledFqns.size, 4096))
        for (f in facts.fields) {
            val ownerFqn = f.owner.fqName
            if (ownerFqn in sampledFqns) {
                fieldCounts[ownerFqn] = (fieldCounts[ownerFqn] ?: 0) + 1
            }
        }

        val classSample =
            sampledClassFacts.map { c ->
                FactsClassRecord(
                    fqName = c.fqName,
                    packageName = c.packageName,
                    simpleName = c.simpleName,
                    role = facts.classToRole[c.fqName],
                    visibility = c.visibility,
                    isInterface = c.isInterface,
                    isAbstract = c.isAbstract,
                    isEnum = c.isEnum,
                    hasMainMethod = c.hasMainMethod,
                    methodCount = methodCounts[c.fqName] ?: 0,
                    fieldCount = fieldCounts[c.fqName] ?: 0,
                    originKind = c.location.originKind,
                    originPath = c.location.originPath,
                    containerPath = c.location.containerPath,
                    entryPath = c.location.entryPath,
                    sourceFile = c.location.sourceFile,
                    line = c.location.line,
                )
            }

        val fanIn = BoundedCounter<String>(MAX_KEYS)
        val fanOut = BoundedCounter<String>(MAX_KEYS)
        val pkgClasses = BoundedCounter<String>(MAX_KEYS)
        val pkgEdgesFrom = BoundedCounter<String>(MAX_KEYS)

        classSample.forEach { pkgClasses.increment(it.packageName) }

        val edgeSample = ArrayList<FactsEdgeRecord>(minOf(512, maxRowsEdges))
        for (e in facts.edges) {
            val fromFqn = e.from.fqName
            val toFqn = e.to.fqName
            if (edgeSample.size < maxRowsEdges) {
                edgeSample.add(
                    FactsEdgeRecord(
                        from = fromFqn,
                        to = toFqn,
                        kind = e.kind,
                        detail = e.detail,
                        originKind = e.location.originKind,
                        originPath = e.location.originPath,
                        containerPath = e.location.containerPath,
                        entryPath = e.location.entryPath,
                        sourceFile = e.location.sourceFile,
                        line = e.location.line,
                    ),
                )
            }
            fanOut.increment(fromFqn)
            fanIn.increment(toFqn)
            pkgEdgesFrom.increment(packageOf(fromFqn))
        }

        loaded =
            LoadedFacts(
                sourcePath = null,
                meta = null,
                stats = stats,
                classSample = classSample,
                edgeSample = edgeSample,
                fanIn = fanIn.snapshot(),
                fanOut = fanOut.snapshot(),
                droppedFanIn = fanIn.dropped(),
                droppedFanOut = fanOut.dropped(),
                topPkgClasses = pkgClasses.top(15),
                droppedPkgClasses = pkgClasses.dropped(),
                topPkgEdgesFrom = pkgEdgesFrom.top(15),
                droppedPkgEdgesFrom = pkgEdgesFrom.dropped(),
            )

        updateMetrics(stats)
        updateStatus(buildStatusLine(loaded!!))
        applyFilter()
    }

    private fun currentEngineStats(): EngineFactsStats? {
        val scan = ShamashAsmUiStateService.getInstance(project).getScanResult()
        val s = scan?.engine?.summary?.factsStats ?: return null
        return EngineFactsStats(classes = s.classes, methods = s.methods, fields = s.fields, edges = s.edges)
    }

    private fun applyFilter() {
        val data = loaded ?: return

        val f = filterField.text?.trim().orEmpty()
        val (edgeFromFilter, edgeToFilter, substringFilter) = parseFilter(f)

        val classRows =
            data.classSample
                .asSequence()
                .filter { c ->
                    if (substringFilter.isBlank()) return@filter true
                    val s = substringFilter.lowercase()
                    c.fqName.lowercase().contains(s) ||
                        c.packageName.lowercase().contains(s) ||
                        (c.role?.lowercase()?.contains(s) == true)
                }.take(MAX_SAMPLE_CLASSES)
                .map { c ->
                    val fin = data.fanIn[c.fqName] ?: 0L
                    val fout = data.fanOut[c.fqName] ?: 0L
                    ClassRow(c, fin, fout)
                }.toList()

        val edgeRows =
            data.edgeSample
                .asSequence()
                .filter { e ->
                    if (!edgeFromFilter.isNullOrBlank() && !e.from.contains(edgeFromFilter, ignoreCase = true)) return@filter false
                    if (!edgeToFilter.isNullOrBlank() && !e.to.contains(edgeToFilter, ignoreCase = true)) return@filter false
                    if (substringFilter.isBlank()) return@filter true
                    val s = substringFilter.lowercase()
                    e.from.lowercase().contains(s) ||
                        e.to.lowercase().contains(s) ||
                        e.kind.name
                            .lowercase()
                            .contains(s) ||
                        (e.detail?.lowercase()?.contains(s) == true)
                }.take(MAX_SAMPLE_EDGES)
                .toList()

        classesModel.setRows(classRows)
        edgesModel.setRows(edgeRows)

        // keep open file enabled only when file exists
        val path = data.sourcePath
        // just update status; button enabling is handled by openFactsFile null-check
        if (path == null) {
            // nothing
        }
    }

    private fun parseFilter(raw: String): Triple<String?, String?, String> {
        val trimmed = raw.trim()
        if (trimmed.contains("->")) {
            val parts = trimmed.split("->", limit = 2)
            val left =
                parts
                    .getOrNull(0)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { null }
            val right =
                parts
                    .getOrNull(1)
                    ?.trim()
                    .orEmpty()
                    .ifBlank { null }
            return Triple(left, right, "")
        }
        return Triple(null, null, trimmed)
    }

    private fun buildStatusLine(data: LoadedFacts): String {
        val src = data.sourcePath?.fileName?.toString() ?: "in-memory facts"
        val parts = mutableListOf<String>()
        parts += "Source: $src"
        if (data.sourcePath != null) parts += "(streamed)" else parts += "(memory)"

        val trunc = mutableListOf<String>()
        if (data.classSample.size >= MAX_SAMPLE_CLASSES) trunc += "classes"
        if (data.edgeSample.size >= MAX_SAMPLE_EDGES) trunc += "edges"
        if (trunc.isNotEmpty()) {
            parts += "Showing first ${MAX_SAMPLE_CLASSES} ${trunc.joinToString("/")}. Use CLI 'facts' for full graph."
        }

        val drops = mutableListOf<String>()
        if (data.droppedFanIn > 0) drops += "fan-in:${data.droppedFanIn}"
        if (data.droppedFanOut > 0) drops += "fan-out:${data.droppedFanOut}"
        if (data.droppedPkgClasses > 0) drops += "pkg-classes:${data.droppedPkgClasses}"
        if (data.droppedPkgEdgesFrom > 0) drops += "pkg-edges:${data.droppedPkgEdgesFrom}"
        if (drops.isNotEmpty()) {
            parts += "Key cap reached; dropped increments (${drops.joinToString()})."
        }

        return parts.joinToString("  ")
    }

    private fun packageOf(fqName: String): String {
        val i = fqName.lastIndexOf('.')
        return if (i <= 0) "" else fqName.substring(0, i)
    }

    private data class EngineFactsStats(
        val classes: Int,
        val methods: Int,
        val fields: Int,
        val edges: Int,
    )

    private data class Stats(
        val classes: Int,
        val methods: Int,
        val fields: Int,
        val edges: Int,
    )

    private data class LoadedFacts(
        val sourcePath: Path?,
        val meta: FactsMetaRecord?,
        val stats: Stats,
        val classSample: List<FactsClassRecord>,
        val edgeSample: List<FactsEdgeRecord>,
        val fanIn: Map<String, Long>,
        val fanOut: Map<String, Long>,
        val droppedFanIn: Long,
        val droppedFanOut: Long,
        val topPkgClasses: List<Pair<String, Long>>,
        val droppedPkgClasses: Long,
        val topPkgEdgesFrom: List<Pair<String, Long>>,
        val droppedPkgEdgesFrom: Long,
    )

    private companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_CONTENT = "content"

        private const val MAX_SAMPLE_CLASSES = 5_000
        private const val MAX_SAMPLE_EDGES = 5_000
        private const val MAX_KEYS = 200_000
    }
}
