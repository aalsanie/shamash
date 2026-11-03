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
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.testFramework.PsiTestUtil
import io.shamash.intellij.plugin.ui.source.SourceEvidence
import io.shamash.intellij.plugin.ui.source.SourceNavigationService
import io.shamash.psi.core.source.SourceDependencyExtractor
import io.shamash.psi.core.source.SourceDependencyKind
import io.shamash.psi.core.source.SourceEntityId
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceReference
import io.shamash.psi.core.source.SourceTarget

class SourceNavigationE2ETest : ShamashPluginE2eTestBase() {
    private fun evidence(): SourceEvidence {
        val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src/main/java")
        PsiTestUtil.addSourceRoot(myModule, sourceRoot)
        val java = myFixture.addFileToProject(
            "src/main/java/demo/Sample.java",
            "package demo; public class Sample { void call() {} }",
        )
        val file = java.virtualFile
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        val index = document.text.indexOf("Sample")
        return SourceEvidence(
            SourceReference(file.url, index, "Sample".length, document.modificationStamp),
            SourceEntityId(myModule.name, sourceRoot.url, file.url, SourceEntityKind.CLASS, "demo.Sample"),
            "demo.Sample",
        )
    }

    fun testVerifiedReferenceNavigatesOnlyWhileSourceAndOwnershipAreCurrent() {
        val witness = evidence()
        assertTrue(SourceNavigationService(project).open(witness))
    }

    fun testChangedDocumentStampNeverUsesOldOffset() {
        val witness = evidence()
        assertFalse(SourceNavigationService(project).open(witness.copy(
            reference = witness.reference.copy(documentStamp = witness.reference.documentStamp + 1),
        )))
        val file = requireNotNull(com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(
            witness.reference.fileUrl,
        ))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteAction.run<RuntimeException> { document.insertString(0, " ") }
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
        myFixture.addFileToProject("src/main/java/demo/Target.java",
            "package demo; public class Target {}")
        val source = myFixture.addFileToProject("src/main/java/demo/Owner.java",
            "package demo; public class Owner { Target value; }")
        val index = ProjectFileIndex.getInstance(project)
        val location = SourceDependencyExtractor.SourceLocation(
            myModule.name, root.url, source.virtualFile.url,
        )
        val dependency = ReadAction.compute<io.shamash.psi.core.source.SourceDependency, RuntimeException> {
            val extractor = SourceDependencyExtractor { psi ->
                val file = psi.virtualFile ?: return@SourceDependencyExtractor null
                val module = index.getModuleForFile(file) ?: return@SourceDependencyExtractor null
                val sourceRoot = index.getSourceRootForFile(file) ?: return@SourceDependencyExtractor null
                SourceDependencyExtractor.SourceLocation(module.name, sourceRoot.url, file.url)
            }
            extractor.extract(source, location).references.first { it.target is SourceTarget.Internal &&
                (it.target as SourceTarget.Internal).entity.signature == "demo.Target"
            }
        }
        val evidence = SourceEvidence(
            dependency.evidence,
            dependency.from,
            "Owner → Target",
            dependency.target,
            dependency.kind,
        )
        val navigation = SourceNavigationService(project)
        assertTrue(navigation.open(evidence))
        val wrongTarget = evidence.copy(
            target = SourceTarget.Internal((dependency.target as SourceTarget.Internal)
                .entity.copy(signature = "demo.Unrelated")),
        )
        assertFalse(navigation.open(wrongTarget))
        assertFalse(navigation.open(evidence.copy(kind =
            if (dependency.kind == SourceDependencyKind.ANNOTATION) SourceDependencyKind.CALL
            else SourceDependencyKind.ANNOTATION)))
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
