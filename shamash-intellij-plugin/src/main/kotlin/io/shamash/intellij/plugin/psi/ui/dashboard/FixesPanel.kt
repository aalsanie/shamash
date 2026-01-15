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

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import io.shamash.artifacts.contract.Finding
import io.shamash.intellij.plugin.psi.ui.actions.PsiActionUtil
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixRegistry
import io.shamash.psi.core.fixes.ShamashFix
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

class FixesPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private val root =
        JPanel(VerticalLayout(6)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }

    private val title = JBLabel("<html><b>Fixes</b></html>")

    init {
        add(root, BorderLayout.NORTH)
        setFinding(null)
    }

    fun setFinding(f: Finding?) {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { setFinding(f) }
            return
        }

        root.removeAll()
        root.add(title)

        if (f == null) {
            root.add(JBLabel("Select a finding to see available fixes."))
            finishRefresh()
            return
        }

        val ctx = FixContext(project = project)

        val fixes =
            try {
                FixRegistry.fixesFor(f, ctx)
            } catch (t: Throwable) {
                PsiActionUtil.notify(
                    project,
                    "Shamash PSI",
                    "Fix provider failed: ${t.message}",
                    NotificationType.ERROR,
                )
                emptyList()
            }

        if (fixes.isEmpty()) {
            root.add(JBLabel("No fixes available for this finding."))
            finishRefresh()
            return
        }

        fixes.forEach { fix -> root.add(fixButton(fix)) }
        finishRefresh()
    }

    private fun finishRefresh() {
        revalidate()
        repaint()
    }

    private fun fixButton(fix: ShamashFix): JButton {
        val label = fix.title.takeIf { it.isNotBlank() } ?: fix.id

        return JButton(label).apply {
            toolTipText = "Apply fix: ${fix.id}"

            addActionListener {
                if (!fix.isApplicable()) {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Fix is no longer applicable (finding may be stale): $label",
                        NotificationType.WARNING,
                    )
                    return@addActionListener
                }

                try {
                    // Fix implementations own write-actions / command wrappers.
                    fix.apply()

                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Applied: $label",
                        NotificationType.INFORMATION,
                    )
                } catch (t: Throwable) {
                    PsiActionUtil.notify(
                        project,
                        "Shamash PSI",
                        "Fix failed: ${t.message}",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }
}
