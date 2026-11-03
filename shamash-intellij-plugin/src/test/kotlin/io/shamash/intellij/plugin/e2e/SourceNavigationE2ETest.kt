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

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.PsiTestUtil
import io.shamash.intellij.plugin.ui.source.SourceEvidence
import io.shamash.intellij.plugin.ui.source.SourceNavigationService
import io.shamash.psi.core.source.SourceEntityId
import io.shamash.psi.core.source.SourceEntityKind
import io.shamash.psi.core.source.SourceReference

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
