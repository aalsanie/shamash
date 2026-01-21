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

import io.shamash.asm.core.export.facts.FactsClassRecord
import io.shamash.asm.core.export.facts.FactsEdgeRecord
import javax.swing.table.AbstractTableModel

internal data class ClassRow(
    val rec: FactsClassRecord,
    val fanIn: Long,
    val fanOut: Long,
)

internal class FactsClassesTableModel : AbstractTableModel() {
    private val columns =
        arrayOf(
            "FQN",
            "Package",
            "Role",
            "Methods",
            "Fields",
            "Fan-in",
            "Fan-out",
        )

    private var rows: List<ClassRow> = emptyList()

    fun setRows(rows: List<ClassRow>) {
        this.rows = rows
        fireTableDataChanged()
    }

    fun getRowAt(row: Int): ClassRow? = rows.getOrNull(row)

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns.getOrNull(column) ?: super.getColumnName(column)

    override fun isCellEditable(
        rowIndex: Int,
        columnIndex: Int,
    ): Boolean = false

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            0 -> r.rec.fqName
            1 -> r.rec.packageName
            2 -> r.rec.role ?: ""
            3 -> r.rec.methodCount
            4 -> r.rec.fieldCount
            5 -> r.fanIn
            6 -> r.fanOut
            else -> ""
        }
    }
}

internal class FactsEdgesTableModel : AbstractTableModel() {
    private val columns =
        arrayOf(
            "From",
            "To",
            "Kind",
            "Detail",
        )

    private var rows: List<FactsEdgeRecord> = emptyList()

    fun setRows(rows: List<FactsEdgeRecord>) {
        this.rows = rows
        fireTableDataChanged()
    }

    fun getRowAt(row: Int): FactsEdgeRecord? = rows.getOrNull(row)

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = columns.size

    override fun getColumnName(column: Int): String = columns.getOrNull(column) ?: super.getColumnName(column)

    override fun isCellEditable(
        rowIndex: Int,
        columnIndex: Int,
    ): Boolean = false

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int,
    ): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            0 -> r.from
            1 -> r.to
            2 -> r.kind.name
            3 -> r.detail ?: ""
            else -> ""
        }
    }
}
