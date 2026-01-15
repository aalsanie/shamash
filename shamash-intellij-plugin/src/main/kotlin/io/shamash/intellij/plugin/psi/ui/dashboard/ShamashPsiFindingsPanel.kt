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
package io.shamash.intellij.plugin.psi.ui.dashboard

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.table.JBTable
import com.intellij.util.PsiNavigateUtil
import io.shamash.artifacts.contract.Finding
import io.shamash.intellij.plugin.psi.ui.actions.PsiActionUtil
import io.shamash.intellij.plugin.psi.ui.actions.ShamashPsiUiStateService
import io.shamash.psi.core.fixes.PsiResolver
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ShamashPsiFindingsPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val model = FindingTableModel()
    private val table = JBTable(model)

    private val details = FindingDetailsPanel()
    private val fixes = FixesPanel(project)

    // stable selection across refresh
    private var selectedKey: String? = null

    init {
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setStriped(true)
        table.autoCreateRowSorter = true

        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val f = selectedFromTable()
            selectedKey = f?.let(::keyOf)
            details.setFinding(f)
            fixes.setFinding(f)
        }

        val left = JBScrollPane(table)

        val right =
            JPanel(VerticalLayout(10)).apply {
                border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
                add(goToSourceButton())
                add(details)
                add(fixes)
            }

        val split =
            JBSplitter(false, 0.62f).apply {
                firstComponent = left
                secondComponent = right
            }

        add(split, BorderLayout.CENTER)

        refresh()
    }

    fun refresh() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { refresh() }
            return
        }

        val findings = ShamashPsiUiStateService.getInstance(project).lastFindings
        model.setFindings(findings)

        // restore selection by key
        val key = selectedKey
        if (key != null) {
            val modelRow = findModelRowByKey(findings, key)
            if (modelRow >= 0) {
                val viewRow = table.convertRowIndexToView(modelRow)
                if (viewRow in 0 until table.rowCount) {
                    table.selectionModel.setSelectionInterval(viewRow, viewRow)
                } else {
                    clearSelection()
                }
            } else {
                clearSelection()
            }
        } else {
            clearSelection()
        }

        val selected = selectedFromTable()
        details.setFinding(selected)
        fixes.setFinding(selected)
    }

    private fun clearSelection() {
        table.clearSelection()
        details.setFinding(null)
        fixes.setFinding(null)
    }

    private fun selectedFromTable(): Finding? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        val modelRow = table.convertRowIndexToModel(viewRow)
        return model.getFinding(modelRow)
    }

    private fun findModelRowByKey(
        findings: List<Finding>,
        key: String,
    ): Int {
        for (i in findings.indices) {
            if (keyOf(findings[i]) == key) return i
        }
        return -1
    }

    private fun goToSourceButton(): JButton =
        JButton("Go to source").apply {
            addActionListener {
                val f = selectedFromTable() ?: return@addActionListener
                try {
                    val element: PsiElement? = PsiResolver.resolveElement(project, f)
                    if (element == null) {
                        PsiActionUtil.notify(
                            project,
                            "Shamash PSI",
                            "Cannot resolve PSI element for this finding.",
                            NotificationType.WARNING,
                        )
                        return@addActionListener
                    }
                    PsiNavigateUtil.navigate(element)
                } catch (t: Throwable) {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Navigation failed: ${t.message}",
                        NotificationType.ERROR,
                    )
                }
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
