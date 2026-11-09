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
package io.shamash.intellij.plugin.e2e

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndWait
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.asm.ui.actions.OpenAsmDashboardAction
import io.shamash.intellij.plugin.asm.ui.actions.RunAsmScanAction
import io.shamash.intellij.plugin.psi.ui.actions.OpenPsiDashboardAction
import io.shamash.intellij.plugin.psi.ui.actions.RunPsiScanAction
import io.shamash.intellij.plugin.settings.ShamashSourceMigrationState
import io.shamash.intellij.plugin.ui.source.ShamashSourcePanel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals

class LegacyActionMigrationE2ETest : ShamashPluginE2eTestBase() {
    fun testRetainedShortcutsAreSafeWithoutAnOpenProject() {
        val emptyContext = DataContext { null }
        val actions =
            listOf(
                OpenAsmDashboardAction(),
                OpenPsiDashboardAction(),
                RunAsmScanAction(),
                RunPsiScanAction(),
            )
        for (action in actions) {
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, emptyContext)
            action.actionPerformed(event)
        }
    }

    fun testOldDashboardsOpenSourceWithoutStartingAnalysis() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val before = coordinator.state().requestGeneration
        fire(OpenAsmDashboardAction())
        fire(OpenPsiDashboardAction())
        assertEquals(before, coordinator.state().requestGeneration)
    }

    fun testOldBuildScanShortcutRunsSourceAnalysisWithoutCompilingOrExporting() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val java = myFixture.addFileToProject("src/main/java/sample/Entry.java", "package sample; class Entry {}")
        val before = Files.readAllBytes(Path.of(java.virtualFile.path))
        val output = Path.of(project.basePath!!).resolve(".shamash")
        assertFalse(Files.exists(output))
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        try {
            fire(RunAsmScanAction())
            assertTrue(coordinator.state().requestGeneration > 0)
            assertContentEquals(before, Files.readAllBytes(Path.of(java.virtualFile.path)))
            assertFalse(Files.exists(output))
        } finally {
            coordinator.cancel()
        }
    }

    fun testMigrationPreservesExistingConfigBaselinesReportsAndSource() {
        val base = Path.of(project.basePath!!)
        val files =
            mapOf(
                base.resolve("shamash/configs/psi.yml") to "version: 1\nproject: {}\n",
                base.resolve(".shamash/baseline.json") to "{\"version\":1,\"fingerprints\":[]}\n",
                base.resolve(".shamash/reports/old-report.json") to "{\"legacy\":true}\n",
                base.resolve("src/main/java/sample/Source.java") to "package sample; class Source {}\n",
            )
        files.forEach { (path, contents) -> writeFile(path, contents) }
        val before = files.keys.associateWith(Files::readAllBytes)
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val initial = coordinator.state().requestGeneration

        runInEdtAndWait {
            val migration = ShamashSourceMigrationState.getInstance(project)
            migration.loadState(ShamashSourceMigrationState.State(acknowledgedVersion = 0))
            val panel = ShamashSourcePanel(project)
            assertEquals(ShamashSourceMigrationState.CURRENT_VERSION, migration.getState().acknowledgedVersion)
            assertFalse(migration.acknowledge())
            try {
                assertEquals(2, panel.tabs.tabCount)
                assertEquals("Findings", panel.tabs.getTitleAt(0))
                assertEquals("Explore", panel.tabs.getTitleAt(1))
            } finally {
                Disposer.dispose(panel)
            }
        }
        assertEquals(initial, coordinator.state().requestGeneration)
        before.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
    }
}
