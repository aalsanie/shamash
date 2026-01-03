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
package io.shamash.psi.ui.dashboard

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.table.JBTable
import com.intellij.util.PsiNavigateUtil
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.PsiResolver
import io.shamash.psi.ui.actions.PsiActionUtil
import io.shamash.psi.ui.actions.ShamashPsiUiStateService
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ShamashPsiDashboardPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val model = FindingTableModel()
    private val table = JBTable(model)

    private val details = FindingDetailsPanel()
    private val fixes = FixesPanel(project)

    private var selected: Finding? = null

    init {
        val toolbar = DashboardToolbarPanel(project) { refresh() }
        add(toolbar, BorderLayout.NORTH)

        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.setStriped(true)
        table.autoCreateRowSorter = true

        table.selectionModel.addListSelectionListener {
            val row = table.selectedRow
            val f = if (row >= 0) model.getFinding(table.convertRowIndexToModel(row)) else null
            selected = f
            details.setFinding(f)
            fixes.setFinding(f)
        }

        val left = JBScrollPane(table)

        val right =
            JPanel(VerticalLayout(10)).apply {
                border = javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)
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
        val findings = ShamashPsiUiStateService.getInstance(project).lastFindings
        model.setFindings(findings)

        // keep selection stable if possible
        details.setFinding(selected)
        fixes.setFinding(selected)
    }

    private fun goToSourceButton(): JButton =
        JButton("Go to source").apply {
            addActionListener {
                val f = selected
                if (f == null) return@addActionListener

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
                    PsiActionUtil.notify(project, "Shamash PSI", "Navigation failed: ${t.message}", NotificationType.ERROR)
                }
            }
        }
}
