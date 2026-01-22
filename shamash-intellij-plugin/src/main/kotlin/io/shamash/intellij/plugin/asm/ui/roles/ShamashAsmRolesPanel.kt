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
package io.shamash.intellij.plugin.asm.ui.roles

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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.shamash.asm.core.export.roles.RolesJsonDocument
import io.shamash.asm.core.export.roles.RolesReader
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class ShamashAsmRolesPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val cards = CardLayout()
    private val emptyPanel = JPanel(BorderLayout())
    private val mainPanel = JPanel(BorderLayout())

    private val status = JBLabel("No scan yet")

    private val roleSearch = JBTextField()
    private val classSearch = JBTextField()

    private val rolesModel = DefaultListModel<RoleRow>()
    private val rolesList = JBList(rolesModel)

    private val classesModel = DefaultListModel<String>()
    private val classesList = JBList(classesModel)

    private val matcherText = JBTextArea()

    private var doc: RolesJsonDocument? = null
    private var filteredRoles: List<RoleRow> = emptyList()

    init {
        layout = cards

        emptyPanel.border = JBUI.Borders.empty(12)
        emptyPanel.add(status, BorderLayout.NORTH)
        add(emptyPanel, "empty")

        mainPanel.border = JBUI.Borders.empty(10)
        mainPanel.add(buildMain(), BorderLayout.CENTER)
        add(mainPanel, "main")

        rolesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rolesList.cellRenderer = RoleRowRenderer()
        rolesList.addListSelectionListener { refreshRoleSelection() }

        classesList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        classesList.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val v = classesList.selectedValue ?: return
                        openClassInEditor(v)
                    }
                }
            },
        )

        roleSearch.emptyText.text = "Filter roles…"
        classSearch.emptyText.text = "Filter classes…"

        roleSearch.document.addDocumentListener(SimpleDocListener { rebuildRoleFilter() })
        classSearch.document.addDocumentListener(SimpleDocListener { refreshRoleSelection() })

        matcherText.isEditable = false
        matcherText.lineWrap = false
        matcherText.font = JBUI.Fonts.smallFont()

        refreshFromState()

        ShamashAsmUiStateService.getInstance(project).addListener(
            disposable = project,
            listener = ShamashAsmUiStateService.Listener { refreshFromState() },
        )
    }

    fun refresh() {
        refreshFromState()
    }

    private fun refreshFromState() {
        val state = ShamashAsmUiStateService.getInstance(project).getState()
        val scan = state?.scanResult
        val rolesPath = scan?.engine?.export?.rolesPath

        if (rolesPath == null) {
            doc = null
            status.text = "roles.json not available (enable export.artifacts.roles.enabled=true)"
            cards.show(this, "empty")
            return
        }

        val title = "Loading roles…"
        status.text = title
        cards.show(this, "empty")

        object : Task.Backgroundable(project, title, false) {
            private var loaded: RolesJsonDocument? = null
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    loaded = RolesReader.read(rolesPath)
                } catch (t: Throwable) {
                    error = t.message ?: t::class.java.simpleName
                }
            }

            override fun onFinished() {
                if (project.isDisposed) return
                if (error != null) {
                    doc = null
                    status.text = "Failed to read roles.json: $error"
                    cards.show(this@ShamashAsmRolesPanel, "empty")
                    return
                }

                doc = loaded
                rebuildRoleFilter()
                cards.show(this@ShamashAsmRolesPanel, "main")
            }
        }.queue()
    }

    private fun rebuildRoleFilter() {
        val d = doc
        if (d == null) {
            rolesModel.clear()
            classesModel.clear()
            matcherText.text = ""
            return
        }

        val q = roleSearch.text.trim().lowercase()
        val rows =
            d.roles
                .asSequence()
                .map { RoleRow(it.id, it.priority, it.count) }
                .sortedWith(compareByDescending<RoleRow> { it.count }.thenBy { it.id })
                .filter { q.isEmpty() || it.id.lowercase().contains(q) }
                .toList()

        filteredRoles = rows
        rolesModel.clear()
        for (r in rows) rolesModel.addElement(r)

        if (rows.isNotEmpty() && rolesList.selectedIndex == -1) {
            rolesList.selectedIndex = 0
        }

        refreshRoleSelection()
    }

    private fun refreshRoleSelection() {
        val d = doc ?: return
        val idx = rolesList.selectedIndex
        if (idx !in filteredRoles.indices) {
            classesModel.clear()
            matcherText.text = ""
            return
        }

        val roleId = filteredRoles[idx].id
        val role = d.roles.firstOrNull { it.id == roleId } ?: return

        val cq = classSearch.text.trim().lowercase()
        val classes =
            role.classes
                .asSequence()
                .filter { cq.isEmpty() || it.lowercase().contains(cq) }
                .sorted()
                .toList()

        classesModel.clear()
        for (c in classes) classesModel.addElement(c)

        matcherText.text = prettyMatcher(role)
        matcherText.caretPosition = 0
    }

    private fun openClassInEditor(fqn: String) {
        ApplicationManager.getApplication().invokeLater {
            val psi = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))
            val file = psi?.containingFile?.virtualFile
            if (file != null) {
                FileEditorManager.getInstance(project).openFile(file, true)
                psi.navigate(true)
            } else {
                status.text = "Could not locate class in project: $fqn"
                cards.show(this@ShamashAsmRolesPanel, "empty")
                SwingUtilities.invokeLater { cards.show(this@ShamashAsmRolesPanel, "main") }
            }
        }
    }

    private fun prettyMatcher(role: RolesJsonDocument.RoleEntry): String {
        val mapper = jacksonObjectMapper()
        return buildString {
            append("role: ")
            append(role.id)
            append("\npriority: ")
            append(role.priority)
            append("\ncount: ")
            append(role.count)
            append("\n\nmatcher:\n")
            try {
                append(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(role.matcher))
            } catch (_: Throwable) {
                append(role.matcher.toString())
            }
        }
    }

    private fun buildMain(): Component {
        val left = JPanel(BorderLayout())
        left.border = JBUI.Borders.empty(0, 0, 0, 8)
        left.add(roleSearch, BorderLayout.NORTH)
        left.add(JBScrollPane(rolesList), BorderLayout.CENTER)
        left.preferredSize = Dimension(240, 200)

        val rightTop = JPanel(BorderLayout())
        rightTop.border = JBUI.Borders.empty(0, 0, 8, 0)
        rightTop.add(matcherTextHeader(), BorderLayout.NORTH)
        rightTop.add(JBScrollPane(matcherText), BorderLayout.CENTER)

        val rightBottom = JPanel(BorderLayout())
        rightBottom.add(classSearch, BorderLayout.NORTH)
        rightBottom.add(JBScrollPane(classesList), BorderLayout.CENTER)

        val right = JPanel(BorderLayout())
        right.add(rightTop, BorderLayout.CENTER)
        right.add(rightBottom, BorderLayout.SOUTH)
        rightBottom.preferredSize = Dimension(300, 220)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        split.resizeWeight = 0.32
        split.border = null
        return split
    }

    private fun matcherTextHeader(): Component {
        val header = JBLabel("Matcher")
        header.border = JBUI.Borders.empty(0, 0, 6, 0)
        return header
    }

    private class RoleRow(
        val id: String,
        val priority: Int,
        val count: Int,
    ) {
        override fun toString(): String = "$id ($count)"
    }

    private class RoleRowRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val row = value as? RoleRow
            if (row != null) {
                text = "${row.id}  (${row.count})"
                toolTipText = "priority=${row.priority}"
            }
            return c
        }
    }

    private class SimpleDocListener(
        private val onChange: () -> Unit,
    ) : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()

        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()

        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    }
}
