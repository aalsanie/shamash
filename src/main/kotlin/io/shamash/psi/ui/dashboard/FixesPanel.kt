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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixRegistry
import io.shamash.psi.fixes.ShamashFix
import io.shamash.psi.ui.actions.PsiActionUtil
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

class FixesPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val root =
        JPanel(VerticalLayout(6)).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

    private val title = JBLabel("<html><b>Fixes</b></html>")

    init {
        root.add(title)
        add(root, BorderLayout.NORTH)
        setFinding(null)
    }

    fun setFinding(f: Finding?) {
        // clear all but title
        root.removeAll()
        root.add(title)

        if (f == null) {
            root.add(JBLabel("Select a finding to see available fixes."))
            revalidate()
            repaint()
            return
        }

        val ctx = FixContext(project = project)
        val fixes =
            try {
                FixRegistry.fixesFor(f, ctx)
            } catch (t: Throwable) {
                PsiActionUtil.notify(project, "Shamash PSI", "Fix provider crashed: ${t.message}", NotificationType.ERROR)
                emptyList()
            }

        if (fixes.isEmpty()) {
            root.add(JBLabel("No fixes available for this finding."))
            revalidate()
            repaint()
            return
        }

        fixes.forEach { fix ->
            root.add(fixButton(f, fix))
        }

        revalidate()
        repaint()
    }

    private fun fixButton(
        finding: Finding,
        fix: ShamashFix,
    ): JButton {
        val label = (fixTitle(fix).ifBlank { fix.id })
        return JButton(label).apply {
            toolTipText = "Apply fix: ${fix.id}"

            addActionListener {
                try {
                    WriteCommandAction.runWriteCommandAction(
                        project,
                        "Shamash PSI: $label",
                        null,
                        Runnable { applyFixBestEffort(fix) },
                    )
                    PsiActionUtil.notify(project, "Shamash PSI", "Applied: $label", NotificationType.INFORMATION)
                } catch (t: Throwable) {
                    PsiActionUtil.notify(project, "Shamash PSI", "Fix failed: ${t.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun fixTitle(fix: ShamashFix): String =
        try {
            // common patterns: title/label/name
            val m =
                fix.javaClass.methods.firstOrNull { it.name == "getTitle" && it.parameterCount == 0 }
                    ?: fix.javaClass.methods.firstOrNull { it.name == "getLabel" && it.parameterCount == 0 }
                    ?: fix.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
            (m?.invoke(fix) as? String) ?: ""
        } catch (_: Throwable) {
            ""
        }

    private fun applyFixBestEffort(fix: ShamashFix) {
        // Try apply() with no args, or apply(Project)
        val m0 = fix.javaClass.methods.firstOrNull { it.name == "apply" && it.parameterCount == 0 }
        if (m0 != null) {
            m0.invoke(fix)
            return
        }
        val m1 = fix.javaClass.methods.firstOrNull { it.name == "apply" && it.parameterCount == 1 }
        if (m1 != null) {
            m1.invoke(fix, project)
            return
        }

        error("ShamashFix has no apply() method signature we recognize: ${fix.javaClass.name}")
    }
}
