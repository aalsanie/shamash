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
package io.shamash.intellij.plugin.e2e

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import io.shamash.artifacts.report.schema.v1.ExportedReport
import io.shamash.artifacts.report.schema.v1.ProjectMetadata
import io.shamash.artifacts.report.schema.v1.ToolMetadata
import io.shamash.asm.core.config.schema.v1.model.ExportFactsFormat
import io.shamash.asm.core.engine.EngineExportResult
import io.shamash.asm.core.engine.EngineResult
import io.shamash.asm.core.engine.EngineRunSummary
import io.shamash.asm.core.export.facts.FactsExporter
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ScanResult
import io.shamash.intellij.plugin.asm.ui.ShamashAsmToolWindowController
import io.shamash.intellij.plugin.asm.ui.actions.ShamashAsmUiStateService
import io.shamash.intellij.plugin.asm.ui.facts.ShamashAsmFactsPanel
import java.nio.file.Files

/**
 * End-to-end (plugin) coverage for Milestone 1 Facts tab:
 * - facts exported to JSONL_GZ (streamable)
 * - ASM toolwindow shows "Facts" tab
 * - panel prefers exported facts file (does not require in-memory facts)
 */
class AsmFactsTabE2ETest : ShamashPluginE2eTestBase() {
    fun testFactsTabLoadsFromExportedFactsFile() {
        val tmp = Files.createTempDirectory("shamash-facts-e2e-").toAbsolutePath().normalize()
        val factsPath = tmp.resolve("facts.jsonl.gz")

        val facts = sampleFactsIndex()
        FactsExporter.export(
            facts = facts,
            outputPath = factsPath,
            format = ExportFactsFormat.JSONL_GZ,
            toolName = "shamash",
            toolVersion = "test",
            projectName = "e2e",
            generatedAtEpochMillis = System.currentTimeMillis(),
        )

        val controller = ShamashAsmToolWindowController.getInstance(project)
        val tabs = JBTabbedPane()
        controller.init(tabs)

        // Provide a scan result where facts are ONLY available via export.factsPath.
        val now = System.currentTimeMillis()
        val summary =
            EngineRunSummary(
                projectName = "e2e",
                projectBasePath = tmp,
                toolName = "shamash",
                toolVersion = "test",
                startedAtEpochMillis = now,
                finishedAtEpochMillis = now + 1,
                factsStats =
                    EngineRunSummary.FactsStats(
                        classes = 2,
                        methods = 3,
                        fields = 1,
                        edges = 1,
                    ),
                ruleStats = EngineRunSummary.RuleStats(),
            )

        val report =
            ExportedReport(
                tool =
                    ToolMetadata(
                        name = "shamash",
                        version = "test",
                        schemaVersion = "v1",
                        generatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                project = ProjectMetadata(name = "e2e", basePath = tmp.toString()),
                findings = emptyList(),
            )

        val export = EngineExportResult(report = report, outputDir = tmp, baselineWritten = false, factsPath = factsPath)

        val engine = EngineResult.success(summary = summary, findings = emptyList(), export = export, facts = null)

        val scan =
            ScanResult(
                options =
                    ScanOptions(
                        projectBasePath = tmp,
                        projectName = "e2e",
                        configPath = null,
                        includeFactsInResult = false,
                    ),
                configPath = null,
                configErrors = emptyList(),
                engine = engine,
            )

        ShamashAsmUiStateService.getInstance(project).update(configPath = null, scanResult = scan)

        // Select Facts tab and wait for async background load to populate tables.
        runInEdtAndWait { controller.select(ShamashAsmToolWindowController.Tab.FACTS) }

        val panel = findFactsPanel(controller)
        waitUntil(10_000) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val tables = UIUtil.findComponentsOfType(panel, JBTable::class.java)
            if (tables.size < 2) return@waitUntil false
            val classesRows = tables[0].model.rowCount
            val edgesRows = tables[1].model.rowCount
            classesRows > 0 && edgesRows > 0
        }

        val tables = UIUtil.findComponentsOfType(panel, JBTable::class.java)
        assertTrue("Expected 2 tables in Facts UI", tables.size >= 2)
        assertTrue("Expected classes table to have rows", tables[0].model.rowCount > 0)
        assertTrue("Expected edges table to have rows", tables[1].model.rowCount > 0)
    }

    private fun findFactsPanel(controller: ShamashAsmToolWindowController): ShamashAsmFactsPanel {
        val root = controller.factsTab.component()
        val panels = UIUtil.findComponentsOfType(root, ShamashAsmFactsPanel::class.java)
        return panels.firstOrNull() ?: error("ShamashAsmFactsPanel not found in Facts tab component tree")
    }

    private fun waitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        error("Condition was not met within ${timeoutMs}ms")
    }

    private fun sampleFactsIndex(): FactIndex {
        val a = TypeRef.fromInternalName("com/example/A")
        val b = TypeRef.fromInternalName("com/example/B")
        val loc = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = "/tmp/A.class")

        val classes =
            listOf(
                ClassFact(
                    type = a,
                    access = ACC_PUBLIC,
                    superType = TypeRef.fromInternalName("java/lang/Object"),
                    interfaces = emptySet(),
                    annotationsFqns = emptySet(),
                    hasMainMethod = false,
                    location = loc,
                ),
                ClassFact(
                    type = b,
                    access = ACC_PUBLIC,
                    superType = TypeRef.fromInternalName("java/lang/Object"),
                    interfaces = emptySet(),
                    annotationsFqns = emptySet(),
                    hasMainMethod = false,
                    location = loc,
                ),
            )

        val methods =
            listOf(
                io.shamash.asm.core.facts.model.MethodRef(
                    owner = a,
                    name = "m1",
                    desc = "()V",
                    signature = null,
                    access = ACC_PUBLIC,
                    isConstructor = false,
                    returnType = null,
                    parameterTypes = emptyList(),
                    throwsTypes = emptyList(),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
                io.shamash.asm.core.facts.model.MethodRef(
                    owner = a,
                    name = "m2",
                    desc = "()V",
                    signature = null,
                    access = ACC_PUBLIC,
                    isConstructor = false,
                    returnType = null,
                    parameterTypes = emptyList(),
                    throwsTypes = emptyList(),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
                io.shamash.asm.core.facts.model.MethodRef(
                    owner = b,
                    name = "m",
                    desc = "()V",
                    signature = null,
                    access = ACC_PUBLIC,
                    isConstructor = false,
                    returnType = null,
                    parameterTypes = emptyList(),
                    throwsTypes = emptyList(),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
            )

        val fields =
            listOf(
                io.shamash.asm.core.facts.model.FieldRef(
                    owner = a,
                    name = "f",
                    desc = "Ljava/lang/String;",
                    signature = null,
                    access = ACC_PUBLIC,
                    fieldType = TypeRef.fromInternalName("java/lang/String"),
                    annotationsFqns = emptySet(),
                    location = loc,
                ),
            )

        val edges =
            listOf(
                DependencyEdge(
                    from = a,
                    to = b,
                    kind = DependencyKind.TYPE_INSTRUCTION,
                    location = loc,
                    detail = "e2e",
                ),
            )

        return FactIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            edges = edges,
            roles = emptyMap(),
            classToRole = mapOf(a.fqName to "roleA", b.fqName to "roleB"),
        )
    }

    private companion object {
        private const val ACC_PUBLIC: Int = 0x0001
    }
}
