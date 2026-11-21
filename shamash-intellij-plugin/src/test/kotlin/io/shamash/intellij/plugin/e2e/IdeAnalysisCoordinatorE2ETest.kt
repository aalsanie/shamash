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
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndWait
import io.shamash.intellij.plugin.analysis.AnalysisCoverage
import io.shamash.intellij.plugin.analysis.AnalysisOutcome
import io.shamash.intellij.plugin.analysis.AnalysisPhase
import io.shamash.intellij.plugin.analysis.IdeAnalysisCoordinator
import io.shamash.intellij.plugin.analysis.ResultRelation
import io.shamash.intellij.plugin.analysis.SourceVfsScope
import io.shamash.intellij.plugin.psi.ui.actions.RunPsiScanAction
import io.shamash.psi.core.config.SchemaResources
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.jetbrains.jps.model.java.JavaSourceRootProperties

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
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        fire(RunPsiScanAction())
        awaitTerminal()
        val state = IdeAnalysisCoordinator.getInstance(project).state()
        val facts =
            kotlin.test.assertNotNull(
                state.visibleResult?.payload?.sourceFacts,
                "Source analysis produced no facts: phase=${state.phase}, relation=${state.resultRelation}, diagnostics=${state.diagnostics}",
            )
        assertTrue(facts.entities.any { it.id.signature == "demo.Repository" })
        assertTrue(facts.relationships.any { it.target.signature == "demo.Repository" })
        assertTrue(facts.entities.any { it.id.signature == "demo.Service" })
        val graph = kotlin.test.assertNotNull(state.visibleResult.payload.sourceGraph)
        assertEquals(facts.profile.id, graph.profileId)
        assertTrue(graph.entityRelationships.any { it.target.signature == "demo.Repository" })
        assertEquals(facts.coverage, graph.coverage)
        assertEquals(disk, Files.readAllBytes(Path.of(source.virtualFile.path)).toList())
        assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        assertTrue(state.diagnostics.none { it.contains("No PSI configuration found") })
    }

    fun testJavaAndKotlinPackagesWithMutualReferencesProduceOneVerifiedCycle() {
        val javaRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        val kotlinRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/kotlin")
        PsiTestUtil.addSourceRoot(myModule, javaRoot)
        PsiTestUtil.addSourceRoot(myModule, kotlinRoot)
        myFixture.addFileToProject(
            "src/main/java/left/A.java",
            "package left; public class A { public right.B dependency; }",
        )
        myFixture.addFileToProject(
            "src/main/kotlin/right/B.kt",
            "package right\nclass B(val dependency: left.A)",
        )

        fire(RunPsiScanAction())
        awaitTerminal()
        val state = IdeAnalysisCoordinator.getInstance(project).state()
        val graph = kotlin.test.assertNotNull(state.visibleResult?.payload?.sourceGraph)
        val cycle = graph.cycles.single()
        assertEquals(setOf("left", "right"), cycle.members.map { it.packageName }.toSet())
        assertEquals(2, cycle.witness.size)
        assertTrue(cycle.witness.all { it.witnesses.isNotEmpty() })
        assertTrue(
            cycle.witness.all { edge ->
                edge.witnesses.all { it.reference.fileUrl == it.from.fileUrl }
            },
        )
    }

    fun testDirectoryRenameStalesCompletedSnapshotWithoutStartingAnotherScan() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val directory = myFixture.tempDirFixture.findOrCreateDir("src/main/java/feature")
        myFixture.addFileToProject("src/main/java/feature/Original.java", "package feature; class Original {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val prior = completeInjected(coordinator)
        val before = coordinator.state().sourceRevision

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { directory.rename(this, "renamed") }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(prior, coordinator.state().visibleResult)
        assertEquals(before + 1, coordinator.state().sourceRevision)
    }

    fun testDeletingContainingDirectoryStalesPreviousResult() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val directory = myFixture.tempDirFixture.findOrCreateDir("src/main/java/feature")
        myFixture.addFileToProject("src/main/java/feature/Original.java", "package feature; class Original {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val prior = completeInjected(coordinator)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { directory.delete(this) }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(prior, coordinator.state().visibleResult)
    }

    fun testMovingDirectoryOutAndBackIntoSourceRootStalesBothGenerations() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val directory = myFixture.tempDirFixture.findOrCreateDir("src/main/java/feature")
        val outside = myFixture.tempDirFixture.findOrCreateDir("elsewhere")
        myFixture.addFileToProject("src/main/java/feature/Original.java", "package feature; class Original {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val first = completeInjected(coordinator)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { directory.move(this, outside) }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(first, coordinator.state().visibleResult)

        val second = completeInjected(coordinator)
        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { directory.move(this, root) }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(second, coordinator.state().visibleResult)
    }

    fun testRenamingAncestorOfSourceRootInvalidatesSnapshot() {
        val ancestor = myFixture.tempDirFixture.findOrCreateDir("src/main")
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        myFixture.addFileToProject("src/main/java/feature/Original.java", "package feature; class Original {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val prior = completeInjected(coordinator)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { ancestor.rename(this, "renamed") }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(prior, coordinator.state().visibleResult)
    }

    fun testUnrelatedReportAndTestDirectoryChangesRemainCurrent() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        val testRoot = myFixture.tempDirFixture.findOrCreateDir("src/test/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        PsiTestUtil.addSourceRoot(myModule, testRoot, true)
        myFixture.addFileToProject("src/main/java/Main.java", "class Main {}")
        val testDirectory = myFixture.tempDirFixture.findOrCreateDir("src/test/java/sample")
        val reportRoot = myFixture.tempDirFixture.findOrCreateDir(".shamash")
        PsiTestUtil.addExcludedRoot(myModule, reportRoot)
        val reportDirectory = myFixture.tempDirFixture.findOrCreateDir(".shamash/reports")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val scope = SourceVfsScope.capture(project)
        assertFalse("Test root incorrectly classified as production source", scope.affects(testDirectory.path, directory = true))
        assertFalse("Report directory incorrectly classified as production source", scope.affects(reportDirectory.path, directory = true))
        val prior = completeInjected(coordinator)
        val revision = coordinator.state().sourceRevision

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { testDirectory.rename(this, "renamed") }
        }
        assertEquals(
            "Test-directory rename: ${coordinator.state()}",
            AnalysisPhase.COMPLETED,
            coordinator.state().phase,
        )
        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { reportDirectory.rename(this, "archived") }
        }
        assertEquals(
            "Report-directory rename: ${coordinator.state()}",
            AnalysisPhase.COMPLETED,
            coordinator.state().phase,
        )
        assertEquals(ResultRelation.CURRENT, coordinator.state().resultRelation)
        assertEquals(prior, coordinator.state().visibleResult)
        assertEquals(revision, coordinator.state().sourceRevision)
    }

    fun testCreatingDirectoryWithinSourceRootInvalidatesCompletedResult() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val previous = completeInjected(coordinator)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { root.createChildDirectory(this, "added") }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(previous, coordinator.state().visibleResult)
    }

    fun testCopyingDirectoryIntoSourceRootInvalidatesCompletedResult() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val outside = myFixture.tempDirFixture.findOrCreateDir("elsewhere/copied")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val previous = completeInjected(coordinator)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { outside.copy(this, root, "copied") }
        }
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
        assertEquals(previous, coordinator.state().visibleResult)
    }

    fun testExcludedDirectoryChangeDoesNotInvalidateSourceResults() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val excluded = myFixture.tempDirFixture.findOrCreateDir("src/main/java/excluded")
        PsiTestUtil.addExcludedRoot(myModule, excluded)
        val child = myFixture.tempDirFixture.findOrCreateDir("src/main/java/excluded/child")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        assertFalse(
            "Excluded child incorrectly classified as production source",
            SourceVfsScope.capture(project).affects(child.path, directory = true),
        )
        val previous = completeInjected(coordinator)
        val revision = coordinator.state().sourceRevision

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { child.rename(this, "renamed") }
        }
        assertEquals(
            "Nested excluded-directory rename: ${coordinator.state()}",
            AnalysisPhase.COMPLETED,
            coordinator.state().phase,
        )
        assertEquals(ResultRelation.CURRENT, coordinator.state().resultRelation)
        assertEquals(previous, coordinator.state().visibleResult)
        assertEquals(revision, coordinator.state().sourceRevision)
    }

    fun testDirectoryRenameCancelsBlockedGenerationAndRejectsLateCompletion() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val directory = myFixture.tempDirFixture.findOrCreateDir("src/main/java/feature")
        myFixture.addFileToProject("src/main/java/feature/Original.java", "package feature; class Original {}")
        val coordinator = IdeAnalysisCoordinator.getInstance(project)
        val previous = completeInjected(coordinator)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val request =
            kotlin.test.assertNotNull(
                coordinator.analyze { attempt, _ ->
                    coordinator.graphStarted(attempt)
                    started.countDown()
                    try {
                        release.await(10, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        returned.countDown()
                    }
                    AnalysisOutcome.Complete(
                        IdeAnalysisCoordinator.SourcePresentation(),
                        AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                    )
                },
            )
        try {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(started.await(10, TimeUnit.SECONDS))
            runInEdtAndWait {
                WriteCommandAction.runWriteCommandAction(project) { directory.rename(this, "renamed") }
            }
            assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
            assertEquals(ResultRelation.STALE, coordinator.state().resultRelation)
            assertEquals(previous, coordinator.state().visibleResult)
            assertTrue(!coordinator.state().active)
            assertTrue(!coordinator.state().currentComplete)
            assertTrue(coordinator.state().requestGeneration > request.generation)
        } finally {
            release.countDown()
        }
        assertTrue(returned.await(10, TimeUnit.SECONDS))
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals(AnalysisPhase.STALE, coordinator.state().phase)
        assertEquals(previous, coordinator.state().visibleResult)
    }

    fun testDisposedCoordinatorIgnoresLaterDirectoryChanges() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val directory = myFixture.tempDirFixture.findOrCreateDir("src/main/java/feature")
        val coordinator = IdeAnalysisCoordinator(project)
        Disposer.dispose(coordinator)

        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) { directory.rename(this, "renamed") }
        }
        assertEquals(AnalysisPhase.CANCELLED, coordinator.state().phase)
        assertEquals(null, coordinator.analyze { _, _ -> error("Disposed coordinator started work") })
    }

    fun testSourceEventMatchingHandlesMovesInBothDirections() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val outside = myFixture.tempDirFixture.findOrCreateDir("elsewhere")
        val otherOutside = myFixture.tempDirFixture.findOrCreateDir("other")
        val insideFile = myFixture.addFileToProject("src/main/java/Inside.java", "class Inside {}").virtualFile
        val outsideFile = myFixture.addFileToProject("elsewhere/Outside.java", "class Outside {}").virtualFile
        PsiTestUtil.addExcludedRoot(myModule, outside)
        PsiTestUtil.addExcludedRoot(myModule, otherOutside)
        val scope = SourceVfsScope.capture(project)
        assertFalse(scope.affects(outsideFile.path, directory = false))
        assertFalse(scope.affects(otherOutside.path, directory = true))

        assertTrue(scope.affects(listOf(VFileMoveEvent(this, outsideFile, root))))
        assertTrue(scope.affects(listOf(VFileMoveEvent(this, insideFile, outside))))
        val unrelated = VFileMoveEvent(this, outsideFile, otherOutside)
        assertFalse(scope.affects(listOf(unrelated)))
        assertTrue(scope.affects(listOf(unrelated, VFileMoveEvent(this, outsideFile, root))))
        assertFalse(scope.affects(emptyList()))
    }

    fun testSourceEventMatchingHandlesRenamesAndMetadataChanges() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val javaFile = myFixture.addFileToProject("src/main/java/Inside.java", "class Inside {}").virtualFile
        val textFile = myFixture.addFileToProject("src/main/java/notes.txt", "notes").virtualFile
        val outsideFile = myFixture.addFileToProject("other/Outside.java", "class Outside {}").virtualFile
        PsiTestUtil.addExcludedRoot(myModule, outsideFile.parent)
        val scope = SourceVfsScope.capture(project)
        assertFalse(scope.affects(outsideFile.path, directory = false))

        assertTrue(
            scope.affects(
                listOf(VFilePropertyChangeEvent(this, javaFile, VirtualFile.PROP_NAME, "Inside.java", "Inside.txt", false)),
            ),
        )
        assertTrue(
            scope.affects(
                listOf(VFilePropertyChangeEvent(this, textFile, VirtualFile.PROP_NAME, "notes.txt", "Added.kt", false)),
            ),
        )
        assertFalse(
            scope.affects(
                listOf(VFilePropertyChangeEvent(this, textFile, VirtualFile.PROP_NAME, "notes.txt", "notes.md", false)),
            ),
        )
        assertFalse(
            scope.affects(
                listOf(VFilePropertyChangeEvent(this, outsideFile, VirtualFile.PROP_NAME, "Outside.java", "Next.java", false)),
            ),
        )
        assertFalse(
            scope.affects(
                listOf(VFilePropertyChangeEvent(this, javaFile, VirtualFile.PROP_WRITABLE, true, false, false)),
            ),
        )
        assertFalse(
            scope.affects(
                listOf(VFilePropertyChangeEvent(this, root, VirtualFile.PROP_WRITABLE, true, false, false)),
            ),
        )
    }

    fun testGeneratedJavaSourceRootIsExcludedFromProductionScope() {
        val generated = myFixture.tempDirFixture.findOrCreateDir("src/main/generated")
        PsiTestUtil.addSourceRoot(myModule, generated)
        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val model = ModuleRootManager.getInstance(myModule).modifiableModel
                val folder =
                    model.contentEntries
                        .flatMap { it.sourceFolders.asList() }
                        .single { it.file == generated }
                val properties = folder.jpsElement.properties as JavaSourceRootProperties
                properties.isForGeneratedSources = true
                model.commit()
            }
        }
        val scope = SourceVfsScope.capture(project)
        assertFalse(scope.affects(generated.path, directory = true))
        assertFalse(scope.affects(generated.path + "/Generated.java", directory = false))
    }

    fun testAbsentExcludedFolderStillMasksItsConfiguredPath() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val missingUrl = root.url + "/not-created"
        runInEdtAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val model = ModuleRootManager.getInstance(myModule).modifiableModel
                model.contentEntries.first().addExcludeFolder(missingUrl)
                model.commit()
            }
        }
        val scope = SourceVfsScope.capture(project)
        assertFalse(scope.affects(root.path + "/not-created", directory = true))
        assertFalse(scope.affects(root.path + "/not-created/Excluded.java", directory = false))
        assertTrue(scope.affects(root.path + "/Other.java", directory = false))
    }

    fun testSourceEventMatchingHandlesDeletionAndContentChanges() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        val javaFile = myFixture.addFileToProject("src/main/java/Inside.java", "class Inside {}").virtualFile
        val outside = myFixture.addFileToProject("elsewhere/Outside.java", "class Outside {}").virtualFile
        PsiTestUtil.addExcludedRoot(myModule, outside.parent)
        val scope = SourceVfsScope.capture(project)
        assertFalse(scope.affects(outside.path, directory = false))

        assertTrue(scope.affects(listOf(VFileDeleteEvent(this, javaFile, false))))
        assertFalse(scope.affects(listOf(VFileDeleteEvent(this, outside, false))))
        assertTrue(scope.affects(listOf(VFileContentChangeEvent(this, javaFile, 1L, 2L, false))))
        assertFalse(scope.affects(listOf(VFileContentChangeEvent(this, outside, 1L, 2L, false))))
    }

    private fun completeInjected(
        coordinator: IdeAnalysisCoordinator,
    ): io.shamash.intellij.plugin.analysis.AnalysisSnapshot<IdeAnalysisCoordinator.SourcePresentation> {
        val finished = CountDownLatch(1)
        kotlin.test.assertNotNull(
            coordinator.analyze { _, _ ->
                finished.countDown()
                AnalysisOutcome.Complete(
                    IdeAnalysisCoordinator.SourcePresentation(),
                    AnalysisCoverage(eligibleFiles = 1, analyzedFiles = 1),
                )
            },
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        awaitTerminal()
        assertEquals(AnalysisPhase.COMPLETED, coordinator.state().phase)
        return kotlin.test.assertNotNull(coordinator.state().visibleResult)
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
