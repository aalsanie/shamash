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

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
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
        val window = ToolWindowManager.getInstance(project).getToolWindow("Shamash")
        val wasVisible = window?.isVisible
        val wasActive = window?.isActive
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val completed = CountDownLatch(1)
        val request =
            coordinator.analyze { _, _ ->
                completed.countDown()
                AnalysisOutcome.Complete(
                    IdeAnalysisCoordinator.SourcePresentation(),
                    AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                )
            }
        kotlin.test.assertNotNull(request)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        assertEquals(AnalysisPhase.COMPLETED, coordinator.state().phase)
        assertEquals(ResultRelation.CURRENT, coordinator.state().resultRelation)
        assertEquals(wasVisible, window?.isVisible)
        assertEquals(wasActive, window?.isActive)
        coordinator.cancel()
    }

    fun testCancellationDuringGraphCannotPublishAfterwards() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val request =
            coordinator.analyze { req, _ ->
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
        kotlin.test.assertNotNull(request)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(started.await(10, TimeUnit.SECONDS))
        coordinator.cancel()
        release.countDown()
        assertEquals(AnalysisPhase.CANCELLED, coordinator.state().phase)
        assertTrue(coordinator.state().visibleResult?.request != request)
    }

    fun testEditingSourceMarksCompletedSnapshotStaleWithoutAutoRerun() {
        val folder = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, folder)
        val source = myFixture.addFileToProject("src/main/java/Sample.java", "class Sample {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val done = CountDownLatch(1)
        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                done.countDown()
                AnalysisOutcome.Complete(
                    IdeAnalysisCoordinator.SourcePresentation(),
                    AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                )
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        val originalGeneration =
            coordinator
                .state()
                .visibleResult
                ?.request
                ?.generation

        runInEdtAndWait {
            val doc = kotlin.test.assertNotNull(FileDocumentManager.getInstance().getDocument(source.virtualFile))
            WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, " // changed") }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(
            originalGeneration,
            coordinator
                .state()
                .visibleResult
                ?.request
                ?.generation,
        )
    }

    fun testRenamingSourceToNonSourceExtensionMarksPreviousResultStale() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val source = myFixture.addFileToProject("src/main/java/Original.java", "class Original {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val done = CountDownLatch(1)
        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                done.countDown()
                AnalysisOutcome.Complete(
                    IdeAnalysisCoordinator.SourcePresentation(),
                    AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                )
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        val previous = kotlin.test.assertNotNull(coordinator.state().visibleResult)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                source.virtualFile.rename(this, "Renamed.txt")
            }
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(previous, coordinator.state().visibleResult)
    }

    fun testDisposedContentSubscriptionCannotReceiveLateCallback() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val owner = Disposer.newDisposable()
        val delivered = AtomicInteger()
        coordinator.subscribe(owner) { delivered.incrementAndGet() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Disposer.dispose(owner)
        val before = delivered.get()

        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                AnalysisOutcome.Complete(IdeAnalysisCoordinator.SourcePresentation(), AnalysisCoverage())
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        awaitTerminal()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(before, delivered.get())
    }

    fun testSupersededAnalysisCannotPublishWhenTheOldBackendReturnsLate() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val a =
            kotlin.test.assertNotNull(
                coordinator.analyze { _, _ ->
                    started.countDown()
                    try {
                        release.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    AnalysisOutcome.Complete(
                        IdeAnalysisCoordinator.SourcePresentation(),
                        AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                    )
                },
            )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(started.await(10, TimeUnit.SECONDS))

        val done = CountDownLatch(1)
        val b =
            kotlin.test.assertNotNull(
                coordinator.analyze { _, _ ->
                    done.countDown()
                    AnalysisOutcome.Complete(
                        IdeAnalysisCoordinator.SourcePresentation(),
                        AnalysisCoverage(eligibleFiles = 2, analyzedFiles = 2),
                    )
                },
            )
        release.countDown()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        assertTrue(b.generation > a.generation)
        assertEquals(b, coordinator.state().visibleResult?.request)
        assertEquals(AnalysisPhase.COMPLETED, coordinator.state().phase)
    }

    fun testSupersededCancellationExceptionCannotCancelTheReplacement() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val firstStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val first =
            kotlin.test.assertNotNull(
                coordinator.analyze { _, _ ->
                    firstStarted.countDown()
                    try {
                        releaseOld.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        releaseOld.await(10, TimeUnit.SECONDS)
                    }
                    throw com.intellij.openapi.progress
                        .ProcessCanceledException()
                },
            )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(firstStarted.await(10, TimeUnit.SECONDS))

        val secondFinished = CountDownLatch(1)
        val second =
            kotlin.test.assertNotNull(
                coordinator.analyze { _, _ ->
                    secondFinished.countDown()
                    AnalysisOutcome.Complete(
                        IdeAnalysisCoordinator.SourcePresentation(),
                        AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                    )
                },
            )
        releaseOld.countDown()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(secondFinished.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        assertTrue(second.generation > first.generation)
        assertEquals(second, coordinator.state().visibleResult?.request)
        assertEquals(AnalysisPhase.COMPLETED, coordinator.state().phase)
    }

    fun testBackendFailureLeavesPriorResultAndDurableDiagnostic() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val good = CountDownLatch(1)
        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                good.countDown()
                AnalysisOutcome.Complete(
                    IdeAnalysisCoordinator.SourcePresentation(),
                    AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                )
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(good.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        val prior = kotlin.test.assertNotNull(coordinator.state().visibleResult)

        val failed = CountDownLatch(1)
        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                failed.countDown()
                error("injected extraction failure")
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(failed.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        assertEquals(AnalysisPhase.FAILED, coordinator.state().phase)
        assertEquals(prior, coordinator.state().visibleResult)
        assertEquals(ResultRelation.PREVIOUS, coordinator.state().resultRelation)
        assertTrue(coordinator.state().diagnostics.any { it.contains("injected extraction failure") })
    }

    fun testModuleRootModificationMakesPreviousResultStale() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val done = CountDownLatch(1)
        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                done.countDown()
                AnalysisOutcome.Complete(
                    IdeAnalysisCoordinator.SourcePresentation(),
                    AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                )
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        val prior = kotlin.test.assertNotNull(coordinator.state().visibleResult)

        runInEdtAndWait {
            val newSourceRoot = myFixture.tempDirFixture.findOrCreateDir("additional/java")
            PsiTestUtil.addSourceRoot(myModule, newSourceRoot)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(prior, coordinator.state().visibleResult)
        assertTrue(coordinator.state().rootRevision > prior.request.rootRevision)
    }

    fun testSourceActionCommitsUnsavedPSIWithoutSavingUnrelatedFiles() {
        val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, sourceRoot)
        val unrelated = myFixture.addFileToProject("src/main/java/Unsaved.java", "class Unsaved {}")
        val extra = myFixture.addFileToProject("notes.txt", "private working notes")
        val vf = unrelated.virtualFile
        val originalBytes = Files.readAllBytes(Path.of(vf.path)).toList()
        val extraBytes = Files.readAllBytes(Path.of(extra.virtualFile.path)).toList()
        val config = SchemaResources.openReferenceYaml().bufferedReader().use { it.readText() }
        val configPath = Path.of(project.basePath!!).resolve("shamash/configs/psi.yml")
        writeFile(configPath, config)
        ShamashPsiSettingsState.getInstance(project).state.configPath = configPath.toString()
        runInEdtAndWait {
            val doc = kotlin.test.assertNotNull(FileDocumentManager.getInstance().getDocument(vf))
            WriteCommandAction.runWriteCommandAction(project) { doc.insertString(doc.textLength, " // unsaved") }
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(doc))
            val extraDoc = kotlin.test.assertNotNull(FileDocumentManager.getInstance().getDocument(extra.virtualFile))
            WriteCommandAction.runWriteCommandAction(project) {
                extraDoc.insertString(extraDoc.textLength, " // unrelated unsaved")
            }
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(extraDoc))
        }
        fire(RunPsiScanAction())
        assertEquals(originalBytes, Files.readAllBytes(Path.of(vf.path)).toList())
        assertEquals(extraBytes, Files.readAllBytes(Path.of(extra.virtualFile.path)).toList())
        assertEquals(
            true,
            FileDocumentManager.getInstance().isDocumentUnsaved(
                kotlin.test.assertNotNull(FileDocumentManager.getInstance().getDocument(vf)),
            ),
        )
        assertTrue(
            FileDocumentManager.getInstance().isDocumentUnsaved(
                kotlin.test.assertNotNull(FileDocumentManager.getInstance().getDocument(extra.virtualFile)),
            ),
        )
        IdeAnalysisCoordinator.getInstance(project).cancel()
    }

    fun testNoConfigMixedSourceAnalysisPublishesResolvedFactsWithoutBuildOrSave() {
        val javaRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        val kotlinRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin")
        PsiTestUtil.addSourceRoot(myModule, javaRoot)
        PsiTestUtil.addSourceRoot(myModule, kotlinRoot)
        myFixture.addFileToProject("src/main/java/demo/Repository.java", "package demo; public class Repository {}")
        myFixture.addFileToProject(
            "src/main/kotlin/demo/Service.kt",
            "package demo\nclass Service { val repository: Repository = Repository() }",
        )
        val source = myFixture.addFileToProject("src/main/java/demo/Caller.java", "package demo; class Caller {}")
        val disk = Files.readAllBytes(Path.of(source.virtualFile.path)).toList()
        val document = kotlin.test.assertNotNull(FileDocumentManager.getInstance().getDocument(source.virtualFile))
        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText("package demo; class Caller { Repository repo; }")
            }
        }
        fire(RunPsiScanAction())
        awaitTerminal()
        val state = IdeAnalysisCoordinator.getInstance(project).state()
        val facts = kotlin.test.assertNotNull(state.visibleResult?.payload?.sourceFacts)
        assertTrue(facts.entities.any { it.id.signature == "demo.Repository" })
        assertTrue(facts.relationships.any { it.target.signature == "demo.Repository" })
        assertTrue(facts.entities.any { it.id.signature == "demo.Service" })
        assertEquals(disk, Files.readAllBytes(Path.of(source.virtualFile.path)).toList())
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertTrue(state.diagnostics.none { it.contains("No PSI configuration found") })
    }


    private fun awaitTerminal() {
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        repeat(500) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (!coordinator.state().active) return
            Thread.sleep(20)
        }
        fail("Analysis did not terminate: ${coordinator.state()}")
    }
}
