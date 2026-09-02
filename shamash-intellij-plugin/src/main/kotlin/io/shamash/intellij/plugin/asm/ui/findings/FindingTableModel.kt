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
package io.shamash.intellij.plugin.asm.ui.findings

import io.shamash.artifacts.contract.Finding
import javax.swing.table.AbstractTableModel

/** Keep filtering and sorting in JTable RowSorter so model indices remain stable. */
class FindingTableModel : AbstractTableModel() {
    companion object {
        const val COL_SEVERITY: Int = 0
        const val COL_RULE: Int = 1
        const val COL_FILE: Int = 2
        const val COL_MESSAGE: Int = 3
    }

    private val columns = arrayOf("Severity", "Rule", "File", "Message")

    private var rows: List<Finding> = emptyList()

    fun setFindings(findings: List<Finding>) {
        rows = findings
        fireTableDataChanged()
    }

    fun clear() {
        if (rows.isEmpty()) return
        rows = emptyList()
        fireTableDataChanged()
    }

    fun size(): Int = rows.size

    fun isEmpty(): Boolean = rows.isEmpty()

    fun getFindingAt(row: Int): Finding = rows[row]

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns[column]

    override fun isCellEditable(
        rowIndex: Int,
        columnIndex: Int,
    ): Boolean = false

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any {
        val f = rows[rowIndex]
        return when (columnIndex) {
            COL_SEVERITY -> f.severity.name
            COL_RULE -> f.ruleId
            COL_FILE -> f.filePath
            COL_MESSAGE -> f.message
            else -> ""
        }
    }
}
