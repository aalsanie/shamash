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
package io.shamash.intellij.plugin.asm.ui.findings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.shamash.artifacts.contract.Finding
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ShamashAsmFindingsPanel(
    private val project: Project,
) : JPanel(BorderLayout()),
    Disposable {
    private val tableModel = FindingTableModel()
    private val table = JBTable(tableModel)

    private val detailsPanel = FindingDetailsPanel(project)

    private var selectedKey: String? = null

    init {
        border = JBUI.Borders.empty(10)

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.autoCreateRowSorter = true

        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val f = getSelectedFinding()
            selectedKey = f?.let(::keyOf)
            detailsPanel.setFinding(f)
        }

        val splitter =
            OnePixelSplitter(false, 0.62f).apply {
                firstComponent = ScrollPaneFactory.createScrollPane(table)
                secondComponent = detailsPanel // <-- direct component, no .component()
            }

        add(splitter, BorderLayout.CENTER)

        ShamashAsmUiStateService.getInstance(project).addListener(this) { _ ->
            refresh()
        }

        refresh()
    }

    fun ui(): JComponent = this

    fun refresh() {
        if (project.isDisposed) return

        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { refresh() }
            return
        }

        val state = ShamashAsmUiStateService.getInstance(project).getState()
        val findings =
            state
                ?.scanResult
                ?.engine
                ?.findings
                .orEmpty()

        tableModel.setFindings(findings)
        restoreSelection(findings)
        detailsPanel.setFinding(getSelectedFinding())
    }

    override fun dispose() {
        // listener auto-unregistered by state service
    }

    private fun getSelectedFinding(): Finding? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        if (modelRow !in 0 until tableModel.rowCount) return null
        return tableModel.getFindingAt(modelRow)
    }

    private fun restoreSelection(findings: List<Finding>) {
        val key = selectedKey
        if (key.isNullOrBlank()) {
            if (findings.isEmpty()) {
                table.clearSelection()
            } else if (table.rowCount > 0 && table.selectedRow < 0) {
                table.setRowSelectionInterval(0, 0)
            }
            return
        }

        val modelIndex = findings.indexOfFirst { keyOf(it) == key }
        if (modelIndex < 0) {
            table.clearSelection()
            return
        }

        val viewIndex = table.convertRowIndexToView(modelIndex)
        if (viewIndex in 0 until table.rowCount) {
            table.setRowSelectionInterval(viewIndex, viewIndex)
        } else {
            table.clearSelection()
        }
    }

    private fun keyOf(f: Finding): String =
        buildString {
            append(f.ruleId).append('|')
            append(f.filePath).append('|')
            append(f.classFqn.orEmpty()).append('|')
            append(f.memberName.orEmpty()).append('|')
            append(f.message)
        }
}
