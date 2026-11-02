/*
 * Copyright © 2025-2026 | Shamash
 * SPDX-License-Identifier: Apache-2.0
 */
package io.shamash.intellij.plugin.e2e

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndWait
import io.shamash.intellij.plugin.analysis.AnalysisCoverage
import io.shamash.intellij.plugin.analysis.AnalysisOutcome
import io.shamash.intellij.plugin.analysis.AnalysisPhase
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.ResultRelation
import io.shamash.intellij.plugin.psi.ui.actions.RunPsiScanAction
import io.shamash.intellij.plugin.psi.ui.settings.ShamashPsiSettingsState
import io.shamash.psi.core.config.SchemaResources
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IdeAnalysisCoordinatorE2ETest : ShamashPluginE2eTestBase() {
    fun testInjectedBackendCompletesAndDoesNotActivateToolWindow() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val completed = CountDownLatch(1)
        val request = coordinator.analyze { _, _ ->
            completed.countDown()
            AnalysisOutcome.Complete(
                IdeAnalysisCoordinator.SourcePresentation(),
                AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
            )
        }
        assertNotNull(request)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        assertEquals(AnalysisPhase.COMPLETED, coordinator.state().phase)
        assertEquals(ResultRelation.CURRENT, coordinator.state().resultRelation)
        coordinator.cancel()
    }

    fun testCancellationDuringGraphCannotPublishAfterwards() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request = coordinator.analyze { req, _ ->
            coordinator.graphStarted(req)
            started.countDown()
            try {
                release.await(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            AnalysisOutcome.Complete(
                IdeAnalysisCoordinator.SourcePresentation(),
                AnalysisCoverage(eligibleFiles = 2, analyzedFiles = 2),
            )
        }
        assertNotNull(request)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        coordinator.cancel()
        release.countDown()
        assertEquals(AnalysisPhase.CANCELLED, coordinator.state().phase)
        assertNull(coordinator.state().visibleResult)
    }

    fun testEditingSourceMarksCompletedSnapshotStaleWithoutAutoRerun() {
        val folder = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, folder)
        val source = myFixture.addFileToProject("src/main/java/Sample.java", "class Sample {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val done = CountDownLatch(1)
        assertNotNull(coordinator.analyze { _, _ ->
            done.countDown()
            AnalysisOutcome.Complete(
                IdeAnalysisCoordinator.SourcePresentation(),
                AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
            )
        })
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        val originalGeneration = coordinator.state().visibleResult?.request?.generation

        runInEdtAndWait {
            val doc = assertNotNull(FileDocumentManager.getInstance().getDocument(source.virtualFile))
            WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, " // changed") }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(originalGeneration, coordinator.state().visibleResult?.request?.generation)
    }

    fun testDisposedContentSubscriptionCannotReceiveLateCallback() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val owner = Disposer.newDisposable()
        val delivered = AtomicInteger()
        coordinator.subscribe(owner) { delivered.incrementAndGet() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Disposer.dispose(owner)
        val before = delivered.get()

        assertNotNull(coordinator.analyze { _, _ ->
            AnalysisOutcome.Complete(IdeAnalysisCoordinator.SourcePresentation(), AnalysisCoverage())
        })
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        awaitTerminal()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(before, delivered.get())
    }

    fun testSourceActionCommitsUnsavedPSIWithoutSavingUnrelatedFiles() {
        val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, sourceRoot)
        val unrelated = myFixture.addFileToProject("src/main/java/Unsaved.java", "class Unsaved {}")
        val vf = unrelated.virtualFile
        val originalBytes = Files.readAllBytes(Path.of(vf.path)).toList()
        val config = SchemaResources.openReferenceYaml().bufferedReader().use { it.readText() }
        val configPath = Path.of(project.basePath!!).resolve("shamash/configs/psi.yml")
        writeFile(configPath, config)
        ShamashPsiSettingsState.getInstance(project).state.configPath = configPath.toString()
        runInEdtAndWait {
            val doc = assertNotNull(FileDocumentManager.getInstance().getDocument(vf))
            WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, " // unsaved") }
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(doc))
        }
        fire(RunPsiScanAction())
        assertEquals(originalBytes, Files.readAllBytes(Path.of(vf.path)).toList())
        assertEquals(true, FileDocumentManager.getInstance().isDocumentUnsaved(
            assertNotNull(FileDocumentManager.getInstance().getDocument(vf)),
        ))
        IdeAnalysisCoordinator.getInstance(project).cancel()
    }

    private fun awaitTerminal() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        repeat(100) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (!coordinator.state().active) return
            Thread.sleep(20)
        }
        fail("Analysis did not terminate: ${coordinator.state()}")
    }
}
