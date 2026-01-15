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
package io.shamash.intellij.plugin.asm.ui.dashboard

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class DashboardToolbarPanel(
    private val project: Project,
    private val targetComponent: JComponent,
) : JBPanel<JBPanel<*>>(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0, 12, 0) // guarantees separation from anything below
        add(buildButtonsRow(), BorderLayout.CENTER)
    }

    private fun buildButtonsRow(): JComponent {
        val row =
            JPanel(WrapFlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(6))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(0)
            }

        row.add(actionButton("Run ASM Scan", "io.shamash.asm.runScan"))
        row.add(actionButton("Export ASM Reports", "io.shamash.asm.exportReports"))

        return row
    }

    private fun actionButton(
        text: String,
        actionId: String,
    ): JButton {
        val btn =
            JButton(text).apply {
                isFocusable = false
                font = UIUtil.getLabelFont()
                margin = JBUI.insets(4, 10)
                preferredSize = Dimension(preferredSize.width, JBUI.scale(28))
                minimumSize = Dimension(JBUI.scale(120), JBUI.scale(28))
            }

        btn.addActionListener {
            val action = ActionManager.getInstance().getAction(actionId) ?: return@addActionListener
            invokeAction(action)
        }
        return btn
    }

    private fun invokeAction(action: AnAction) {
        val dataContext: DataContext = DataManager.getInstance().getDataContext(targetComponent)
        val event =
            AnActionEvent.createFromAnAction(
                action,
                null,
                ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                dataContext,
            )
        action.actionPerformed(event)
    }

    /**
     * FlowLayout that wraps into multiple rows based on available width.
     */
    private class WrapFlowLayout(
        align: Int,
        hgap: Int,
        vgap: Int,
    ) : FlowLayout(align, hgap, vgap) {
        override fun preferredLayoutSize(target: java.awt.Container): Dimension = layoutSize(target, preferred = true)

        override fun minimumLayoutSize(target: java.awt.Container): Dimension {
            val d = layoutSize(target, preferred = false)
            d.width -= hgap + 1
            return d
        }

        private fun layoutSize(
            target: java.awt.Container,
            preferred: Boolean,
        ): Dimension {
            synchronized(target.treeLock) {
                val insets = target.insets
                val maxWidth = (target.width.takeIf { it > 0 } ?: Int.MAX_VALUE) - (insets.left + insets.right)

                var width = 0
                var height = insets.top + insets.bottom
                var rowWidth = 0
                var rowHeight = 0

                val nmembers = target.componentCount
                for (i in 0 until nmembers) {
                    val m = target.getComponent(i)
                    if (!m.isVisible) continue

                    val d = if (preferred) m.preferredSize else m.minimumSize
                    val compWidth = d.width
                    val compHeight = d.height

                    if (rowWidth == 0) {
                        rowWidth = compWidth
                        rowHeight = compHeight
                    } else {
                        val nextWidth = rowWidth + hgap + compWidth
                        if (nextWidth <= maxWidth) {
                            rowWidth = nextWidth
                            rowHeight = maxOf(rowHeight, compHeight)
                        } else {
                            width = maxOf(width, rowWidth)
                            height += rowHeight + vgap
                            rowWidth = compWidth
                            rowHeight = compHeight
                        }
                    }
                }

                width = maxOf(width, rowWidth)
                height += rowHeight
                width += insets.left + insets.right
                return Dimension(width, height)
            }
        }
    }
}
