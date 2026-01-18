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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.shamash.asm.core.config.ValidationError
import io.shamash.asm.core.engine.EngineRunSummary
import io.shamash.asm.core.scan.ScanResult
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.settings.ShamashAsmConfigLocator
import java.awt.BorderLayout
import java.awt.Font
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent

/**
 * Pure renderer for ASM dashboard state (NO toolbar here).
 *
 * Surfaces:
 * - config path
 * - EngineRunSummary stats (EngineResult.summary)
 * - scan/facts/engine errors (ScanResult)
 * - export dir + report info (EngineResult.export)
 */
class ShamashAsmDashboardPanel(
    private val project: Project,
) : Disposable {
    private val root = JBPanel<JBPanel<*>>(BorderLayout())

    private val statusLabel = JBLabel("Status: idle")

    private val overviewText = monoArea()
    private val errorsText = monoArea()

    init {
        root.border = JBUI.Borders.empty(10)

        val header =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 0, 8, 0)
                add(statusLabel, BorderLayout.SOUTH)
            }

        // Content panel (scrollable as one unit)
        val content =
            JBPanel<JBPanel<*>>().apply {
                layout =
                    com.intellij.ui.dsl.gridLayout
                        .GridLayout()
                border = JBUI.Borders.empty(0)

                // Header sits outside; content only contains sections
            }

        // If you don't want GridLayout DSL dependency, keep it simple:
        // We'll use a vertical BoxLayout-like stacking using BorderLayout + nested panels.
        val sections =
            JBPanel<JBPanel<*>>().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            }

        val overviewSection =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBUI.Borders.empty(0, 0, 10, 0)
                add(JBLabel("Overview").apply { border = JBUI.Borders.empty(0, 0, 6, 0) }, BorderLayout.NORTH)
                add(overviewText, BorderLayout.CENTER)
            }

        val errorsSection =
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JBLabel("Errors").apply { border = JBUI.Borders.empty(0, 0, 6, 0) }, BorderLayout.NORTH)
                add(errorsText, BorderLayout.CENTER)
            }

        sections.add(overviewSection)
        sections.add(errorsSection)

        val scroll =
            ScrollPaneFactory.createScrollPane(sections, true).apply {
                border = JBUI.Borders.empty()
            }

        root.add(header, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)

        ShamashAsmUiStateService.getInstance(project).addListener(this) { refresh() }
        refresh()
    }

    fun component(): JComponent = root

    fun refresh() {
        val app = ApplicationManager.getApplication()
        if (!app.isDispatchThread) {
            app.invokeLater { refresh() }
            return
        }

        val state = ShamashAsmUiStateService.getInstance(project).getState()
        val result = state?.scanResult

        val resolvedConfig =
            result?.configPath
                ?: ShamashAsmConfigLocator.resolveConfigPath(project)

        statusLabel.text = statusLine(result)

        overviewText.text = buildOverview(result, resolvedConfig)
        errorsText.text = buildErrors(result)

        // keep top visible
        overviewText.caretPosition = 0
        errorsText.caretPosition = 0
    }

    override fun dispose() {
        // listener auto-unregistered by state service
    }

    private fun statusLine(result: ScanResult?): String {
        if (result == null) return "Status: idle"
        return when {
            result.hasConfigErrors -> "Status: config invalid"
            !result.hasEngineResult -> "Status: scan did not reach engine"
            result.engine?.hasErrors == true -> "Status: engine completed with errors"
            else -> "Status: success"
        }
    }

    private fun buildOverview(
        result: ScanResult?,
        resolvedConfig: java.nio.file.Path?,
    ): String {
        if (result == null) {
            return """
                No ASM run yet.

                Config: ${resolvedConfig?.toString() ?: "Not found"}

                Use:
                - Build your project (ASM analysis depends on bytecode)
                - Navigate to Config panel
                - Create asm.yml Manually or From Reference
                - Validate ASM Config
                - Run ASM Scan
                """.trimIndent()
        }

        val engine = result.engine
        val summary: EngineRunSummary? = engine?.summary
        val export = engine?.export
        val report = export?.report

        return buildString {
            append("Project: ").append(result.options.projectName).append('\n')
            append("Base: ").append(result.options.projectBasePath).append('\n')
            append("Config: ").append(result.configPath?.toString() ?: resolvedConfig?.toString() ?: "Not found").append('\n')
            append('\n')

            append("Units scanned: ").append(result.classUnits).append('\n')
            append("Truncated: ").append(result.truncated).append('\n')
            append('\n')

            append("Config errors: ").append(result.configErrors.size).append('\n')
            append("Scan errors: ").append(result.scanErrors.size).append('\n')
            append("Facts errors: ").append(result.factsErrors.size).append('\n')
            append('\n')

            if (engine == null) {
                append("Engine: not executed\n")
            } else {
                append("Engine executed: true\n")
                append("Engine errors: ").append(engine.errors.size).append('\n')
                append("Findings: ").append(engine.findings.size).append('\n')

                if (summary != null) {
                    append('\n')
                    append("Run summary:\n")
                    append("  duration(ms): ").append(summary.durationMillis).append('\n')
                    append("  facts: classes=")
                        .append(summary.factsStats.classes)
                        .append(", methods=")
                        .append(summary.factsStats.methods)
                        .append(", fields=")
                        .append(summary.factsStats.fields)
                        .append(", edges=")
                        .append(summary.factsStats.edges)
                        .append('\n')

                    append("  rules:\n")
                    append("    configured: ").append(summary.ruleStats.configuredRules).append('\n')
                    append("    executed: ").append(summary.ruleStats.executedRules).append('\n')
                    append("    skipped: ").append(summary.ruleStats.skippedRules).append('\n')
                    append("    instances: executed=")
                        .append(summary.ruleStats.executedRuleInstances)
                        .append(", skipped=")
                        .append(summary.ruleStats.skippedRuleInstances)
                        .append(", notFound=")
                        .append(summary.ruleStats.notFoundRuleInstances)
                        .append(", failed=")
                        .append(summary.ruleStats.failedRuleInstances)
                        .append('\n')
                }

                append('\n')
                if (export == null) {
                    append("Exported: false\n")
                } else {
                    append("Exported: true\n")
                    append("Export dir: ").append(export.outputDir).append('\n')
                    append("Baseline written: ").append(export.baselineWritten).append('\n')
                    if (report != null) {
                        append("Report findings: ").append(report.findings.size).append('\n')
                        append("Report tool: ")
                            .append(report.tool.name)
                            .append(" ")
                            .append(report.tool.version)
                            .append('\n')
                    }
                }
            }
        }.trimEnd()
    }

    private fun buildErrors(result: ScanResult?): String {
        if (result == null) return ""

        val engine = result.engine
        val hasAny =
            result.configErrors.isNotEmpty() ||
                result.scanErrors.isNotEmpty() ||
                result.factsErrors.isNotEmpty() ||
                (engine?.errors?.isNotEmpty() == true)

        if (!hasAny) return "No errors."

        return buildString {
            if (result.configErrors.isNotEmpty()) {
                append("CONFIG ERRORS\n")
                append(formatValidationErrors(result.configErrors))
                append("\n\n")
            }
            if (result.scanErrors.isNotEmpty()) {
                append("SCAN ERRORS\n")
                append(formatAny(result.scanErrors))
                append("\n\n")
            }
            if (result.factsErrors.isNotEmpty()) {
                append("FACTS ERRORS\n")
                append(formatAny(result.factsErrors))
                append("\n\n")
            }
            if (engine != null && engine.errors.isNotEmpty()) {
                append("ENGINE ERRORS\n")
                append(formatAny(engine.errors))
                append("\n")
            }
        }.trimEnd()
    }

    private fun formatValidationErrors(errors: List<ValidationError>): String =
        buildString {
            for (e in errors) {
                append("- ").append(e.severity.name)
                append(" | ").append(e.path)
                append(" | ").append(e.message)
                append('\n')
            }
        }.trimEnd()

    private fun formatAny(items: List<Any?>): String =
        buildString {
            for (it in items) {
                append("- ").append(it?.toString() ?: "null").append('\n')
            }
        }.trimEnd()

    private fun monoArea(): JBTextArea =
        JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            border = JBUI.Borders.empty(8)
        }

    private fun formatInstant(i: Instant): String =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(i)
}
