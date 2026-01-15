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

import io.shamash.artifacts.contract.Finding
import javax.swing.table.AbstractTableModel

class FindingTableModel : AbstractTableModel() {
    private val columns = arrayOf("Severity", "Rule", "File", "Symbol", "Message")
    private var rows: List<Finding> = emptyList()

    fun setFindings(findings: List<Finding>) {
        // Defensive copy so UI never observes caller's mutable list.
        rows = findings.toList()
        fireTableDataChanged()
    }

    fun getFinding(row: Int): Finding? = if (row in rows.indices) rows[row] else null

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any {
        // Swing can call this during transitions; be defensive.
        val f = rows.getOrNull(rowIndex) ?: return ""
        return when (columnIndex) {
            0 -> f.severity.name
            1 -> f.ruleId
            2 -> f.filePath
            3 -> (f.memberName ?: f.classFqn ?: "")
            4 -> f.message
            else -> ""
        }
    }
}
