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
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.shamash.artifacts.contract.Finding
import java.awt.BorderLayout
import java.awt.Font

class FindingDetailsPanel(
    @Suppress("unused") private val project: Project,
) : JBPanel<JBPanel<*>>(BorderLayout()),
    Disposable {
    private val textArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }

    init {
        border = JBUI.Borders.empty(0)
        add(ScrollPaneFactory.createScrollPane(textArea), BorderLayout.CENTER)
    }

    fun setFinding(finding: Finding?) {
        textArea.text = format(finding)
        textArea.caretPosition = 0
    }

    override fun dispose() {
        // no-op
    }

    private fun format(f: Finding?): String {
        if (f == null) return ""
        return buildString {
            append("Severity: ").append(f.severity).append('\n')
            append("Rule: ").append(f.ruleId).append('\n')
            append("File: ").append(f.filePath).append('\n')
            if (!f.classFqn.isNullOrBlank()) append("Class: ").append(f.classFqn).append('\n')
            if (!f.memberName.isNullOrBlank()) append("Member: ").append(f.memberName).append('\n')
            if (f.startOffset != null || f.endOffset != null) {
                append("Offsets: ")
                    .append(f.startOffset ?: "?")
                    .append("..")
                    .append(f.endOffset ?: "?")
                    .append('\n')
            }
            append('\n')
            append("Message:\n").append(f.message).append('\n')

            if (f.data.isNotEmpty()) {
                append('\n')
                append("Data:\n")
                for ((k, v) in f.data.entries.sortedBy { it.key }) {
                    append("  - ")
                        .append(k)
                        .append(": ")
                        .append(v)
                        .append('\n')
                }
            }
        }.trimEnd()
    }
}
