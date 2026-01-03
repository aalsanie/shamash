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

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import io.shamash.psi.engine.Finding
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

class FindingDetailsPanel : JPanel(BorderLayout()) {
    private val header = JBLabel("<html><b>No selection</b></html>")
    private val body = JBTextArea()

    init {
        val root = JPanel(VerticalLayout(8))
        root.border = javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)

        root.add(header)

        body.isEditable = false
        body.lineWrap = true
        body.wrapStyleWord = true

        root.add(JBScrollPane(body).apply { preferredSize = Dimension(250, 220) })

        add(root, BorderLayout.CENTER)
    }

    fun setFinding(f: Finding?) {
        if (f == null) {
            header.text = "<html><b>No selection</b></html>"
            body.text = ""
            return
        }

        header.text =
            "<html><b>${f.severity.name}</b> — ${escape(f.ruleId)}</html>"

        body.text =
            buildString {
                appendLine(f.message)
                appendLine()
                appendLine("File: ${f.filePath}")
                if (!f.classFqn.isNullOrBlank()) appendLine("Class: ${f.classFqn}")
                if (!f.memberName.isNullOrBlank()) appendLine("Member: ${f.memberName}")

                if (f.data.isNotEmpty()) {
                    appendLine()
                    appendLine("Data:")
                    f.data.toSortedMap().forEach { (k, v) ->
                        appendLine("  - $k: $v")
                    }
                }
            }.trimEnd()
    }

    private fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
