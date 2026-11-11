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

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBTextArea
import io.shamash.intellij.plugin.ui.source.CurrentSourceLocation
import io.shamash.intellij.plugin.ui.source.SourceEvidence
import io.shamash.intellij.plugin.ui.source.SourceEvidencePanel
import io.shamash.intellij.plugin.ui.source.SourceEvidenceNavigator
import io.shamash.intellij.plugin.ui.source.SourceNavigationService
import io.shamash.psi.core.source.SourceDependencyExtractor
import io.shamash.psi.core.source.SourceDependencyKind
import io.shamash.psi.core.source.SourceEntityId
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceReference
import io.shamash.psi.core.source.SourceTarget
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JButton
import javax.swing.JPanel

class SourceNavigationE2ETest : ShamashPluginE2eTestBase() {
    private fun evidence(): SourceEvidence {
        val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, sourceRoot)
        myFixture.addFileToProject(
            "src/main/java/demo/Target.java",
            "package demo; public class Target {}",
        )
        val source =
            myFixture.addFileToProject(
                "src/main/java/demo/Sample.java",
                "package demo; public class Sample { Target value; }",
            )
        val location =
            SourceDependencyExtractor.SourceLocation(
                myModule.name,
                sourceRoot.url,
                source.virtualFile.url,
            )
        val index = ProjectFileIndex.getInstance(project)
        val reference =
            ReadAction.compute<io.shamash.psi.core.source.SourceDependency, RuntimeException> {
                SourceDependencyExtractor { psi ->
                    val file = psi.virtualFile ?: return@SourceDependencyExtractor null
                    val module = index.getModuleForFile(file) ?: return@SourceDependencyExtractor null
                    val root = index.getSourceRootForFile(file) ?: return@SourceDependencyExtractor null
                    SourceDependencyExtractor.SourceLocation(module.name, root.url, file.url)
                }.extract(source, location).references.first {
                    it.target is SourceTarget.Internal &&
                        (it.target as SourceTarget.Internal).entity.signature == "demo.Target"
                }
            }
        return SourceEvidence(reference.evidence, reference.from, "Sample → Target", reference.target, reference.kind)
    }

    fun testCancelledBeforeSubmissionNeverOpensSourceOrCallsBack() {
        val witness = evidence()
        var called = false
        runInEdtAndWait {
            SourceNavigationService(project).openAsync(witness, { true }) { called = true }
        }
        assertFalse(called)
    }

    fun testBackgroundNavigationOpensOnlyVerifiedEvidence() {
        val witness = evidence()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val complete = CountDownLatch(1)
        val opened = AtomicReference<Boolean>()
        runInEdtAndWait {
            SourceNavigationService(project).openAsync(witness, { false }) {
                opened.set(it)
                complete.countDown()
            }
        }
        repeat(500) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (complete.count == 0L) {
                assertTrue(opened.get())
                return
            }
            Thread.sleep(20)
        }
        fail("Background navigation did not complete")
    }

    fun testBackgroundNavigationRejectsStaleDocumentStamp() {
        val witness = evidence()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val complete = CountDownLatch(1)
        val opened = AtomicReference<Boolean>()
        runInEdtAndWait {
            SourceNavigationService(project).openAsync(
                witness.copy(reference = witness.reference.copy(documentStamp = witness.reference.documentStamp + 1)),
                { false },
            ) {
                opened.set(it)
                complete.countDown()
            }
        }
        repeat(500) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (complete.count == 0L) {
                assertFalse(opened.get())
                return
            }
            Thread.sleep(20)
        }
        fail("Stale background navigation did not complete")
    }

    fun testVerifiedReferenceNavigatesOnlyWhileSourceAndOwnershipAreCurrent() {
        val witness = evidence()
        assertTrue(SourceNavigationService(project).open(witness))
    }

    fun testChangedDocumentStampNeverUsesOldOffset() {
        val witness = evidence()
        assertFalse(
            SourceNavigationService(project).open(
                witness.copy(
                    reference = witness.reference.copy(documentStamp = witness.reference.documentStamp + 1),
                ),
            ),
        )
        val file =
            requireNotNull(
                com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(
                    witness.reference.fileUrl,
                ),
            )
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, " ") }
        assertFalse(SourceNavigationService(project).open(witness))
    }

    fun testChangedTargetCannotNavigateWithUnchangedOwnerDocument() {
        val witness = evidence()
        val target = (witness.target as SourceTarget.Internal).entity
        val ownerFile = requireNotNull(VirtualFileManager.getInstance().findFileByUrl(witness.reference.fileUrl))
        val targetFile = requireNotNull(VirtualFileManager.getInstance().findFileByUrl(target.fileUrl))
        val ownerDocument = requireNotNull(FileDocumentManager.getInstance().getDocument(ownerFile))
        val targetDocument = requireNotNull(FileDocumentManager.getInstance().getDocument(targetFile))
        val originalStamp = ownerDocument.modificationStamp

        WriteCommandAction.runWriteCommandAction(project) {
            targetDocument.setText("package demo; public class Renamed {}")
            PsiDocumentManager.getInstance(project).commitDocument(targetDocument)
        }

        assertEquals(originalStamp, ownerDocument.modificationStamp)
        assertFalse(SourceNavigationService(project).open(witness))
    }

    fun testMissingDeclarationIdentityCannotNavigateEvenWhenDocumentIsUnchanged() {
        val witness = evidence()
        val altered = witness.copy(owner = witness.owner.copy(signature = "demo.NotThisClass"))
        assertFalse(SourceNavigationService(project).open(altered))
        assertTrue(SourceNavigationService(project).open(witness))
    }

    fun testResolvedDependencyMustMatchExactTargetAndKind() {
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, root)
        myFixture.addFileToProject(
            "src/main/java/demo/Target.java",
            "package demo; public class Target {}",
        )
        val source =
            myFixture.addFileToProject(
                "src/main/java/demo/Owner.java",
                "package demo; public class Owner { Target value; }",
            )
        val index = ProjectFileIndex.getInstance(project)
        val location =
            SourceDependencyExtractor.SourceLocation(
                myModule.name,
                root.url,
                source.virtualFile.url,
            )
        val dependency =
            ReadAction.compute<io.shamash.psi.core.source.SourceDependency, RuntimeException> {
                val extractor =
                    SourceDependencyExtractor { psi ->
                        val file = psi.virtualFile ?: return@SourceDependencyExtractor null
                        val module = index.getModuleForFile(file) ?: return@SourceDependencyExtractor null
                        val sourceRoot = index.getSourceRootForFile(file) ?: return@SourceDependencyExtractor null
                        SourceDependencyExtractor.SourceLocation(module.name, sourceRoot.url, file.url)
                    }
                extractor.extract(source, location).references.first {
                    it.target is SourceTarget.Internal &&
                        (it.target as SourceTarget.Internal).entity.signature == "demo.Target"
                }
            }
        val evidence =
            SourceEvidence(
                dependency.evidence,
                dependency.from,
                "Owner → Target",
                dependency.target,
                dependency.kind,
            )
        val navigation = SourceNavigationService(project)
        assertTrue(navigation.open(evidence))
        val wrongTarget =
            evidence.copy(
                target =
                    SourceTarget.Internal(
                        (dependency.target as SourceTarget.Internal)
                            .entity
                            .copy(signature = "demo.Unrelated"),
                    ),
            )
        assertFalse(navigation.open(wrongTarget))
        assertFalse(
            navigation.open(
                evidence.copy(
                    kind =
                        if (dependency.kind == SourceDependencyKind.ANNOTATION) {
                            SourceDependencyKind.CALL
                        } else {
                            SourceDependencyKind.ANNOTATION
                        },
                ),
            ),
        )
    }

    fun testCorrectStampWithFabricatedLocationOrMissingDependencyIdentityCannotNavigate() {
        val witness = evidence()
        val navigation = SourceNavigationService(project)
        assertFalse(navigation.open(witness.copy(reference = witness.reference.copy(offset = witness.reference.offset + 1))))
        assertFalse(navigation.open(witness.copy(target = null)))
        assertFalse(navigation.open(witness.copy(kind = null)))
    }

    fun testEvidenceFromNonProductionOrTestRootCannotNavigate() {
        val witness = evidence()
        val outside = myFixture.addFileToProject("outside/Other.java", "public class Other {}")
        val testRoot = myFixture.tempDirFixture.findOrCreateDir("src/test/java")
        PsiTestUtil.addSourceRoot(myModule, testRoot, true)
        val testFile = myFixture.addFileToProject("src/test/java/demo/TestOnly.java", "package demo; class TestOnly {}")
        val navigation = SourceNavigationService(project)
        for (file in listOf(outside.virtualFile, testFile.virtualFile)) {
            val moved =
                witness.copy(
                    owner = witness.owner.copy(fileUrl = file.url),
                    reference = witness.reference.copy(fileUrl = file.url),
                )
            assertFalse(navigation.open(moved))
        }
    }

    fun testDirectoryAndOutOfDocumentRangeCannotNavigate() {
        val witness = evidence()
        val navigation = SourceNavigationService(project)
        val root = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        assertFalse(
            navigation.open(
                witness.copy(
                    owner = witness.owner.copy(fileUrl = root.url),
                    reference = witness.reference.copy(fileUrl = root.url),
                ),
            ),
        )
        assertFalse(
            navigation.open(witness.copy(reference = witness.reference.copy(length = Int.MAX_VALUE))),
        )
        assertFalse(
            navigation.open(witness.copy(reference = witness.reference.copy(offset = Int.MAX_VALUE))),
        )
        assertFalse(
            navigation.open(
                witness.copy(reference = witness.reference.copy(fileUrl = witness.owner.sourceRoot)),
            ),
        )
    }

    fun testEvidencePanelDropsPendingNavigationAfterSelectionChangesAndDisposal() {
        val witness = evidence()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val panel = SourceEvidencePanel(project)
        val controls = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JPanel
        val open = controls.components.filterIsInstance<JButton>().single()
        val outcome = controls.components.filterIsInstance<JBTextArea>().single()
        runInEdtAndWait {
            panel.showEvidence(listOf(witness), 1)
            panel.list.selectedIndex = 0
            open.doClick()
            assertFalse(open.isEnabled)
            panel.list.clearSelection()
            assertEquals("", outcome.text)
            open.isEnabled = true
            panel.list.actionMap
                .get("openSource")
                .actionPerformed(ActionEvent(panel.list, 0, "openSource"))
            assertEquals("", outcome.text)
            open.isEnabled = false
            panel.dispose()
            panel.list.actionMap
                .get("openSource")
                .actionPerformed(ActionEvent(panel.list, 0, "openSource"))
            assertFalse(open.isEnabled)
        }
        repeat(40) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertFalse(open.isEnabled)
        assertEquals("", outcome.text)
    }

    fun testEvidencePanelOpensOnlyTheCurrentVerifiedReference() {
        val witness = evidence()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val panel = SourceEvidencePanel(project)
        val controls = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JPanel
        val open = controls.components.filterIsInstance<JButton>().single()
        val outcome = controls.components.filterIsInstance<JBTextArea>().single()
        runInEdtAndWait {
            panel.showEvidence(listOf(witness), 1)
            panel.list.selectedIndex = 0
            open.doClick()
            assertFalse(open.isEnabled)
        }
        repeat(500) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (open.isEnabled &&
                outcome.text.isEmpty() &&
                FileEditorManager.getInstance(project).selectedFiles.any { it.url == witness.reference.fileUrl }
            ) {
                runInEdtAndWait { panel.dispose() }
                return
            }
            Thread.sleep(20)
        }
        runInEdtAndWait { panel.dispose() }
        fail("Verified source evidence did not open through the panel")
    }

    fun testNavigationRevalidationRejectsEveryChangedIdentityAndRevision() {
        val witness = evidence()
        val accepted =
            CurrentSourceLocation(
                sourceRevision = 17,
                rootRevision = 23,
                productionSource = true,
                testSource = false,
                moduleId = witness.owner.moduleId,
                sourceRoot = witness.owner.sourceRoot,
                documentStamp = witness.reference.documentStamp,
            )
        assertTrue(accepted.matches(witness, 17, 23))
        val invalid =
            listOf(
                accepted.copy(sourceRevision = 18),
                accepted.copy(rootRevision = 24),
                accepted.copy(productionSource = false),
                accepted.copy(testSource = true),
                accepted.copy(moduleId = "other"),
                accepted.copy(moduleId = null),
                accepted.copy(sourceRoot = "file:///other"),
                accepted.copy(sourceRoot = null),
                accepted.copy(documentStamp = witness.reference.documentStamp + 1),
                accepted.copy(documentStamp = null),
            )
        invalid.forEach { assertFalse(it.matches(witness, 17, 23)) }
        assertFalse(accepted.matches(witness, 18, 23))
        assertFalse(accepted.matches(witness, 17, 24))
        assertFalse(accepted.matches(witness.copy(owner = witness.owner.copy(moduleId = "other")), 17, 23))
        assertFalse(accepted.matches(witness.copy(owner = witness.owner.copy(sourceRoot = "file:///other")), 17, 23))
        assertFalse(
            accepted.matches(
                witness.copy(reference = witness.reference.copy(documentStamp = witness.reference.documentStamp + 1)),
                17,
                23,
            ),
        )
    }

    fun testMissingFileWithMatchingReferenceUrlDoesNotNavigate() {
        val witness = evidence()
        val missing = "file:///shamash-missing-source-never-created.java"
        val altered =
            witness.copy(
                owner = witness.owner.copy(fileUrl = missing),
                reference = witness.reference.copy(fileUrl = missing),
            )
        assertFalse(SourceNavigationService(project).open(altered))
    }

    fun testExternalSourceFileCannotBeTreatedAsProjectProductionSource() {
        val witness = evidence()
        val directory = Files.createTempDirectory("shamash-navigation-outside")
        try {
            val path = directory.resolve("External.java")
            Files.writeString(path, "public class External {}")
            val external = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
            val altered =
                witness.copy(
                    owner = witness.owner.copy(fileUrl = external.url),
                    reference = witness.reference.copy(fileUrl = external.url, offset = 0, length = 6),
                )
            assertFalse(SourceNavigationService(project).open(altered))
        } finally {
            Files.deleteIfExists(directory.resolve("External.java"))
            Files.deleteIfExists(directory)
        }
    }

    fun testUnselectedEvidencePanelIgnoresOpenActionWithoutLaunchingNavigation() {
        val witness = evidence()
        val panel = SourceEvidencePanel(project)
        runInEdtAndWait {
            panel.showEvidence(listOf(witness), 1L)
            val controls = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JPanel
            val open = controls.components.filterIsInstance<JButton>().single()
            val result = controls.components.filterIsInstance<JBTextArea>().single()
            assertFalse(open.isEnabled)
            panel.list.actionMap
                .get("openSource")
                .actionPerformed(ActionEvent(panel.list, 0, "openSource"))
            assertFalse(open.isEnabled)
            assertEquals("", result.text)
            panel.dispose()
        }
    }

    fun testEvidencePanelIgnoresLateCallbacksAfterSelectionOrDisposal() {
        val witness = evidence()
        lateinit var expired: () -> Boolean
        lateinit var complete: (Boolean) -> Unit
        val panel =
            SourceEvidencePanel(project, SourceEvidenceNavigator { _, isExpired, completed ->
                expired = isExpired
                complete = completed
            })
        val controls = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JPanel
        val open = controls.components.filterIsInstance<JButton>().single()
        val status = controls.components.filterIsInstance<JBTextArea>().single()
        runInEdtAndWait {
            panel.showEvidence(listOf(witness), 1)
            panel.list.selectedIndex = 0
            open.doClick()
            assertFalse(expired())
            panel.list.clearSelection()
            assertTrue(expired())
            complete(true)
            assertEquals("", status.text)
            assertFalse(open.isEnabled)

            panel.list.selectedIndex = 0
            open.doClick()
            assertFalse(expired())
            panel.dispose()
            assertTrue(expired())
            complete(false)
            assertFalse(open.isEnabled)
            assertEquals("Checking source reference…", status.text)
        }
    }

    fun testEvidencePanelHandlesCurrentSuccessAndFailure() {
        val witness = evidence()
        lateinit var expired: () -> Boolean
        lateinit var complete: (Boolean) -> Unit
        val panel =
            SourceEvidencePanel(project, SourceEvidenceNavigator { _, isExpired, completed ->
                expired = isExpired
                complete = completed
            })
        val controls = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.SOUTH) as JPanel
        val open = controls.components.filterIsInstance<JButton>().single()
        val status = controls.components.filterIsInstance<JBTextArea>().single()
        runInEdtAndWait {
            panel.showEvidence(listOf(witness), 1)
            panel.list.selectedIndex = 0
            open.doClick()
            assertFalse(expired())
            complete(false)
            assertTrue(status.text.contains("Analyze again"))
            assertTrue(open.isEnabled)

            open.doClick()
            assertFalse(expired())
            complete(true)
            assertEquals("", status.text)
            assertTrue(open.isEnabled)
            panel.dispose()
        }
    }

    fun testWrongModuleRootFileOrReferenceRangeCannotNavigate() {
        val witness = evidence()
        val navigation = SourceNavigationService(project)
        assertFalse(navigation.open(witness.copy(owner = witness.owner.copy(moduleId = "unrelated"))))
        assertFalse(navigation.open(witness.copy(owner = witness.owner.copy(sourceRoot = "file:///unrelated"))))
        assertFalse(navigation.open(witness.copy(reference = witness.reference.copy(fileUrl = "file:///missing.java"))))
        assertFalse(navigation.open(witness.copy(reference = witness.reference.copy(offset = -1))))
        assertFalse(navigation.open(witness.copy(reference = witness.reference.copy(offset = 999_999))))
        assertFalse(navigation.open(witness.copy(reference = witness.reference.copy(length = 0))))
    }
}
