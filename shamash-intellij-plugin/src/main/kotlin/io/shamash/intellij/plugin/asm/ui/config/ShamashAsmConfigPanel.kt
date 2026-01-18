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
package io.shamash.intellij.plugin.asm.ui.config

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.shamash.asm.core.config.ValidationError
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

class ShamashAsmConfigPanel(
    private val project: Project,
) : Disposable {
    private val root: JPanel = JBPanel<JBPanel<*>>(BorderLayout())

    private val pathLabel = JBLabel("", SwingConstants.LEFT)
    private val summaryLabel = JBLabel("", SwingConstants.LEFT)

    private val errorsTextArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
            background = JBColor.PanelBackground
        }

    private val errorsScroll =
        ScrollPaneFactory.createScrollPane(errorsTextArea, true).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }

    init {
        root.border = JBUI.Borders.empty(10)

        // Header stack: title -> buttons -> meta (path/summary)
        val headerStack =
            JBPanel<JBPanel<*>>().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
            }

        headerStack.add(Box.createVerticalStrut(JBUI.scale(8)))

        val actionsRow = buildHeaderActionsRow().apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        headerStack.add(actionsRow)

        // This is the key: real vertical strut, not just border math.
        headerStack.add(Box.createVerticalStrut(JBUI.scale(10)))

        val meta = buildMetaPanel().apply { alignmentX = JComponent.LEFT_ALIGNMENT }
        headerStack.add(meta)

        // Put header stack in NORTH, errors scroll fills the rest.
        root.add(headerStack, BorderLayout.NORTH)
        root.add(errorsScroll, BorderLayout.CENTER)

        ShamashAsmUiStateService.getInstance(project).addListener(this) { refresh() }
        refresh()
    }

    fun component(): JComponent = root

    fun refresh() {
        if (project.isDisposed) return

        val resolved = ShamashAsmConfigLocator.resolveConfigPath(project)
        val state = ShamashAsmUiStateService.getInstance(project).getState()
        val errs = state?.scanResult?.configErrors.orEmpty()

        pathLabel.text = "Resolved config: ${resolved?.toString() ?: "Not found"}"

        val (errCount, warnCount) = count(errs)
        summaryLabel.text =
            when {
                resolved == null ->
                    "No ASM config found. Create one from reference."
                errs.isEmpty() ->
                    "No validation results yet. Use Validate or Run Scan."
                errCount > 0 ->
                    "Invalid config. Errors: $errCount | Warnings: $warnCount"
                else ->
                    "Config valid. Warnings: $warnCount"
            }

        errorsTextArea.text = if (errs.isEmpty()) "" else formatErrors(errs)
        errorsTextArea.caretPosition = 0
    }

    override fun dispose() {
        // listener auto-unregistered by state service
    }

    private fun buildMetaPanel(): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 10, 0)
            add(pathLabel, BorderLayout.NORTH)
            add(summaryLabel.apply { foreground = JBColor.GRAY }, BorderLayout.SOUTH)
        }

    private fun buildHeaderActionsRow(): JComponent {
        val row =
            JPanel(WrapFlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(6))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(10)// keep 10 for panel not to overlap buttons
            }

        row.add(actionButton("Validate Config", "io.shamash.asm.validateConfig"))
        row.add(actionButton("Create from Reference", "io.shamash.asm.createConfigFromReference"))
        row.add(actionButton("Open Reference", "io.shamash.asm.openReferenceConfig"))

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
                minimumSize = Dimension(JBUI.scale(140), JBUI.scale(28))
            }

        btn.addActionListener {
            val action = ActionManager.getInstance().getAction(actionId) ?: return@addActionListener
            invokeAction(action)
        }
        return btn
    }

    private fun invokeAction(action: AnAction) {
        val dataContext: DataContext = DataManager.getInstance().getDataContext(root)
        val event =
            AnActionEvent.createFromAnAction(
                action,
                null,
                ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
                dataContext,
            )
        action.actionPerformed(event)
    }

    private fun count(errors: List<ValidationError>): Pair<Int, Int> {
        val err = errors.count { it.severity.name == "ERROR" }
        val warn = errors.size - err
        return err to warn
    }

    private fun formatErrors(errors: List<ValidationError>): String =
        buildString {
            for (e in errors) {
                append(e.severity.name)
                append(" | ")
                append(e.path)
                append(" | ")
                append(e.message)
                append("\n")
            }
        }.trimEnd()

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
