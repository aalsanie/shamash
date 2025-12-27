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
package io.shamash.asm.ui.dashboard.tabs

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import io.shamash.asm.model.AsmIndex
import io.shamash.asm.model.Finding
import io.shamash.asm.model.Severity
import io.shamash.asm.ui.dashboard.export.ExportUtil
import io.shamash.asm.ui.dashboard.report.GraphHtmlBuilder
import io.shamash.asm.ui.dashboard.report.GraphModelBuilder
import io.shamash.asm.ui.dashboard.report.ShamashScoreCalculator
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Tab D — Report (Graph + Score)
 *
 * - JCEF graph (if supported)
 * - Fallback if JCEF unsupported: export-only panel
 * - Export full graph to standalone HTML (no CDN / no external assets)
 * - Score explanation (“Why this score?”)
 */
class AsmReportTabPanel(
    private val project: Project,
) : JPanel(BorderLayout()) {
    private var index: AsmIndex? = null
    private var allFindingsSnapshot: List<Finding> = emptyList()

    private val onlyViolToggle = JBCheckBox("Only violations", false)
    private val hideJdkToggle = JBCheckBox("Hide JDK", true)
    private val projectOnlyToggle = JBCheckBox("Project-only", true)

    private val exportHtmlBtn = JButton("Export Graph HTML")
    private val exportJsonBtn = JButton("Export Graph JSON")

    private val scoreLabel = JBLabel("Run scan to compute score.")
    private val explainArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
        }

    init {
        val top =
            JPanel(BorderLayout()).apply {
                val controls =
                    JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
                        add(onlyViolToggle)
                        add(hideJdkToggle)
                        add(projectOnlyToggle)
                        add(exportHtmlBtn)
                        add(exportJsonBtn)
                    }
                add(controls, BorderLayout.NORTH)
                add(scoreLabel, BorderLayout.SOUTH)
            }

        val right =
            BorderLayoutPanel().apply {
                addToTop(JBLabel("Why this score?"))
                addToCenter(ScrollPaneFactory.createScrollPane(explainArea))
            }

        add(top, BorderLayout.NORTH)
        add(right, BorderLayout.CENTER)

        onlyViolToggle.addActionListener { rebuildAndPush() }
        hideJdkToggle.addActionListener { rebuildAndPush() }
        projectOnlyToggle.addActionListener { rebuildAndPush() }

        exportHtmlBtn.addActionListener { exportGraphHtml() }
        exportJsonBtn.addActionListener { exportGraphJson() }
    }

    fun onIndexUpdated(newIndex: AsmIndex) {
        index = newIndex
        rebuildAndPush()
    }

    /**
     * Called by Findings tab after scans, so score + graph incorporate findings.
     */
    fun onAllFindingsUpdated(findings: List<Finding>) {
        allFindingsSnapshot = findings
        rebuildAndPush()
    }

    private fun rebuildAndPush() {
        val idx = index ?: return

        val score = computeScore(idx, allFindingsSnapshot)

        val graph =
            GraphModelBuilder.build(
                projectName = project.name,
                index = idx,
                allFindings = allFindingsSnapshot.map { it.asFindingLike() },
                score = score,
                projectOnly = projectOnlyToggle.isSelected,
                hideJdk = hideJdkToggle.isSelected,
            )

        // score UI
        scoreLabel.text =
            "Shamash Rate: overall=${graph.score.overall}% (structural=${100 - graph.score.structural}, layering=${100 - graph.score.layering}, coupling=${100 - graph.score.coupling}, complexity=${100 - graph.score.complexity})"

        explainArea.text =
            buildString {
                for (e in graph.score.explain) {
                    appendLine("${e.category}: ${e.summary}")
                }
            }.trim()
    }

    private fun computeScore(
        idx: AsmIndex,
        findings: List<Finding>,
    ): ShamashScoreCalculator.ScoreResult {
        // Hotspots snapshot: reuse cheap signals derived from bytecode counts
        val classes = idx.classes.values.toList()

        // “godScore” approximation: same weights as hotspots panel
        fun publicMethodCount(info: io.shamash.asm.model.AsmClassInfo): Int =
            info.methods.count { it.name != "<init>" && it.name != "<clinit>" && (it.access and 0x0001) != 0 }

        fun godScore(
            info: io.shamash.asm.model.AsmClassInfo,
            fin: Int,
            fout: Int,
            depth: Int,
        ): Int {
            val m = info.methods.size
            val pm = publicMethodCount(info)
            val f = info.fieldCount
            val ins = info.instructionCount / 50
            return (m * 3) + (pm * 2) + (f * 2) + ins + (fout * 2) + (fin * 2) + (depth - 1)
        }

        // fanIn within project
        val fanIn = HashMap<String, Int>(idx.classes.size)
        for ((from, tos) in idx.references) {
            if (from !in idx.classes) continue
            for (to in tos) {
                if (to !in idx.classes) continue
                fanIn[to] = (fanIn[to] ?: 0) + 1
            }
        }

        // depth
        val depthMemo = HashMap<String, Int>(idx.classes.size)

        fun depthOf(internal: String): Int {
            depthMemo[internal]?.let { return it }
            val sup =
                idx.classes[internal]?.superInternalName ?: run {
                    depthMemo[internal] = 1
                    return 1
                }
            val d = if (sup in idx.classes) 1 + depthOf(sup) else 2
            depthMemo[internal] = d
            return d
        }

        val godTop =
            classes
                .map { info ->
                    val fin = fanIn[info.internalName] ?: 0
                    val fout = info.referencedInternalNames.size
                    val d = depthOf(info.internalName)
                    godScore(info, fin, fout, d)
                }.sortedDescending()

        val fanOutTop = classes.map { it.referencedInternalNames.size }.sortedDescending()
        val depthTop = classes.map { depthOf(it.internalName) }.sortedDescending()

        val snapshot =
            ShamashScoreCalculator.HotspotsSnapshot(
                topGodScores = godTop,
                topFanOut = fanOutTop,
                topDepth = depthTop,
            )

        val findingLikes = findings.map { it.asScoreFindingLike() }

        return ShamashScoreCalculator.compute(
            classCount = idx.classes.size,
            findings = findingLikes,
            hotspots = snapshot,
        )
    }

    private fun exportGraphHtml() {
        val idx =
            index ?: run {
                ExportUtil.notify(project, "Nothing to export", "Run scan first.", NotificationType.WARNING)
                return
            }

        val score = computeScore(idx, allFindingsSnapshot)
        val graph =
            GraphModelBuilder.build(
                projectName = project.name,
                index = idx,
                allFindings = allFindingsSnapshot.map { it.asFindingLike() },
                score = score,
                projectOnly = projectOnlyToggle.isSelected,
                hideJdk = hideJdkToggle.isSelected,
            )

        val json = GraphModelBuilder.toJson(graph)
        val html = GraphHtmlBuilder.buildStandaloneHtml(project, json)

        ExportUtil.saveWithDialogExt(
            project = project,
            title = "Export Shamash Graph (Standalone HTML)",
            description = "Export full graph to a single HTML file (no external assets).",
            extension = "html",
            suggestedFileName = "shamash-graph.html",
            content = html,
        )
    }

    private fun exportGraphJson() {
        val idx =
            index ?: run {
                ExportUtil.notify(project, "Nothing to export", "Run scan first.", NotificationType.WARNING)
                return
            }

        val score = computeScore(idx, allFindingsSnapshot)
        val graph =
            GraphModelBuilder.build(
                projectName = project.name,
                index = idx,
                allFindings = allFindingsSnapshot.map { it.asFindingLike() },
                score = score,
                projectOnly = projectOnlyToggle.isSelected,
                hideJdk = hideJdkToggle.isSelected,
            )

        val json = GraphModelBuilder.toJson(graph)

        ExportUtil.saveWithDialog(
            project = project,
            title = "Export Shamash Graph JSON",
            description = "Export graph JSON (schema=shamash.graph.v1).",
            format = ExportUtil.Format.JSON,
            suggestedFileName = "shamash-graph.json",
            content = json,
        )
    }

    // adapters to keep Report tab independent
    private fun Finding.asFindingLike(): GraphModelBuilder.FindingLike =
        object : GraphModelBuilder.FindingLike {
            override val fqcn: String? get() = this@asFindingLike.fqcn
            override val severity: Severity get() = this@asFindingLike.severity
        }

    private fun Finding.asScoreFindingLike(): ShamashScoreCalculator.FindingLike =
        object : ShamashScoreCalculator.FindingLike {
            override val id: String get() = this@asScoreFindingLike.id
            override val severity: Severity get() = this@asScoreFindingLike.severity
            override val title: String get() = this@asScoreFindingLike.title
        }
}
