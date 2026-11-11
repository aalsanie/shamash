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

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

internal fun escaped(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

internal fun wrappedText(value: String = ""): JBTextArea =
    JBTextArea(value).apply {
        isEditable = false
        isFocusable = true
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 6)
        accessibleContext.accessibleName = "Source analysis explanation"
    }

internal fun <T> sourceList(
    name: String,
    label: (T) -> String,
): JBList<T> =
    JBList<T>().apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        accessibleContext.accessibleName = name
        cellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component {
                    val component =
                        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                    @Suppress("UNCHECKED_CAST")
                    component.text = "<html>" + escaped(if (value == null) "" else label(value as T)) + "</html>"
                    return component
                }
            }
    }

internal class SourceEvidencePanel(
    project: com.intellij.openapi.project.Project,
) : JPanel(BorderLayout()) {
    private val navigation = SourceNavigationService(project)
    private val model = DefaultListModel<SourceEvidence>()
    val list = sourceList<SourceEvidence>("Verified source references") { it.label }
    private val count = wrappedText("Select a dependency to inspect source evidence")
    private val outcome = wrappedText("")
    private val open = JButton("Open source")

    @Volatile private var generation = 0L

    @Volatile private var disposed = false

    init {
        list.model = model
        val controls = JPanel(BorderLayout(6, 0))
        controls.add(open, BorderLayout.WEST)
        controls.add(outcome, BorderLayout.CENTER)
        val header = JPanel(BorderLayout())
        count.rows = 2
        outcome.rows = 2
        header.add(count, BorderLayout.CENTER)
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)
        open.isEnabled = false
        outcome.accessibleContext.accessibleName = "Source navigation result"
        open.addActionListener { openSelected() }
        list.addListSelectionListener {
            generation++
            outcome.text = ""
            open.isEnabled = list.selectedValue != null
        }
        list.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "openSource")
        list.actionMap.put(
            "openSource",
            object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = openSelected()
            },
        )
        list.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelected()
                }
            },
        )
        getAccessibleContext().accessibleName = "Source evidence"
    }

    fun showEvidence(
        references: List<SourceEvidence>,
        occurrences: Long,
    ) {
        generation++
        model.clear()
        references.take(SourceUiModel.MAX_WITNESSES).forEach(model::addElement)
        open.isEnabled = false
        outcome.text = ""
        count.text =
            if (references.isEmpty()) {
                "No retained source references"
            } else {
                "Showing " + model.size() + " of " + occurrences +
                    " reference occurrences (up to " + SourceUiModel.MAX_WITNESSES + " locations)"
            }
    }

    private fun openSelected() {
        if (disposed || !open.isEnabled) return
        val item = list.selectedValue ?: return
        val request = ++generation
        open.isEnabled = false
        outcome.text = "Checking source reference…"
        navigation.openAsync(item, { disposed || generation != request }) { opened ->
            if (disposed || generation != request) return@openAsync
            outcome.text = if (opened) "" else "This source location changed or is unavailable. Analyze again."
            open.isEnabled = list.selectedValue != null
        }
    }

    fun dispose() {
        disposed = true
        generation++
    }
}

internal class PageControls : JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)) {
    val previous = JButton("Previous")
    val next = JButton("Next")
    private val label = JBLabel("0 results")
    var page: Int = 0
        private set

    init {
        add(previous)
        add(next)
        add(label)
        getAccessibleContext().accessibleName = "Result pages"
    }

    fun update(total: Int) {
        val count = SourceUiModel.pageCount(total)
        page = page.coerceIn(0, count - 1)
        previous.isEnabled = page > 0
        next.isEnabled = page + 1 < count
        label.text =
            if (total == 0) {
                "0 results"
            } else {
                "Page " + (page + 1) + " of " + count + " · " + total + " total"
            }
    }

    fun change(
        delta: Int,
        total: Int,
    ) {
        page = (page + delta).coerceIn(0, SourceUiModel.pageCount(total) - 1)
        update(total)
    }

    fun reset() {
        page = 0
    }

    fun selectPage(
        index: Int,
        total: Int,
    ) {
        page = (index / SourceUiModel.PAGE_SIZE).coerceIn(0, SourceUiModel.pageCount(total) - 1)
        update(total)
    }
}
