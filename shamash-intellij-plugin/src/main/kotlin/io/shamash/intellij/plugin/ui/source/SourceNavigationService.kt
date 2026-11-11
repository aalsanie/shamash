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
package io.shamash.intellij.plugin.ui.source

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.concurrency.AppExecutorUtil
import io.shamash.psi.core.source.SourceDependencyExtractor
import io.shamash.psi.core.source.SourceReference

internal class SourceNavigationService(
    private val project: Project,
) {
    private data class VerifiedFile(
        val file: VirtualFile,
        val sourceRevision: Long,
        val rootRevision: Long,
    )

    fun open(evidence: SourceEvidence): Boolean {
        val file = verify(evidence) ?: return false
        return navigate(evidence, file)
    }

    fun openAsync(
        evidence: SourceEvidence,
        expired: () -> Boolean,
        completed: (Boolean) -> Unit,
    ) {
        if (project.isDisposed || expired()) return
        ReadAction
            .nonBlocking<VerifiedFile?> { verify(evidence) }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { expired() }
            .finishOnUiThread(ModalityState.any()) { verified ->
                if (!expired()) completed(verified != null && navigate(evidence, verified))
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun verify(evidence: SourceEvidence): VerifiedFile? {
        if (project.isDisposed) return null
        val reference = evidence.reference
        val owner = evidence.owner
        val target = evidence.target ?: return null
        val kind = evidence.kind ?: return null
        if (reference.fileUrl != owner.fileUrl || reference.offset < 0 || reference.length <= 0) return null

        val file = VirtualFileManager.getInstance().findFileByUrl(reference.fileUrl) ?: return null
        if (!file.isValid || file.isDirectory) return null

        return ReadAction.compute<VerifiedFile?, RuntimeException> {
            if (project.isDisposed || DumbService.isDumb(project) || !file.isValid) return@compute null
            val sourceRevision = PsiModificationTracker.getInstance(project).modificationCount
            val rootRevision = ProjectRootModificationTracker.getInstance(project).modificationCount
            val index = ProjectFileIndex.getInstance(project)
            if (!index.isInSourceContent(file) ||
                index.isInTestSourceContent(file) ||
                index.getModuleForFile(file)?.name != owner.moduleId ||
                index.getSourceRootForFile(file)?.url != owner.sourceRoot
            ) {
                return@compute null
            }

            val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            val end = reference.offset.toLong() + reference.length.toLong()
            if (doc.modificationStamp != reference.documentStamp ||
                end > doc.textLength || end <= reference.offset
            ) {
                return@compute null
            }

            val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute null
            val location =
                SourceDependencyExtractor.SourceLocation(
                    owner.moduleId,
                    owner.sourceRoot,
                    owner.fileUrl,
                )
            val extractor =
                SourceDependencyExtractor { targetFile ->
                    val v = targetFile.virtualFile ?: return@SourceDependencyExtractor null
                    if (!index.isInSourceContent(v) || index.isInTestSourceContent(v)) {
                        return@SourceDependencyExtractor null
                    }
                    val module = index.getModuleForFile(v) ?: return@SourceDependencyExtractor null
                    val root = index.getSourceRootForFile(v) ?: return@SourceDependencyExtractor null
                    SourceDependencyExtractor.SourceLocation(module.name, root.url, v.url)
                }
            val extracted = extractor.extract(psi, location)
            if (extracted.entities.none { it.id == owner }) return@compute null
            if (extracted.references.none {
                    it.from == owner && it.evidence == reference &&
                        it.target == target && it.kind == kind
                }
            ) {
                return@compute null
            }
            VerifiedFile(file, sourceRevision, rootRevision)
        }
    }

    private fun navigate(
        evidence: SourceEvidence,
        verified: VerifiedFile,
    ): Boolean {
        val file = verified.file
        val current =
            ReadAction.compute<Boolean, RuntimeException> {
                if (project.isDisposed || !file.isValid || file.isDirectory) return@compute false
                val index = ProjectFileIndex.getInstance(project)
                CurrentSourceLocation(
                    sourceRevision = PsiModificationTracker.getInstance(project).modificationCount,
                    rootRevision = ProjectRootModificationTracker.getInstance(project).modificationCount,
                    productionSource = index.isInSourceContent(file),
                    testSource = index.isInTestSourceContent(file),
                    moduleId = index.getModuleForFile(file)?.name,
                    sourceRoot = index.getSourceRootForFile(file)?.url,
                    documentStamp = FileDocumentManager.getInstance().getDocument(file)?.modificationStamp,
                ).matches(evidence, verified.sourceRevision, verified.rootRevision)
            }
        if (!current) return false
        OpenFileDescriptor(project, file, evidence.reference.offset).navigate(true)
        return true
    }
}

internal data class CurrentSourceLocation(
    val sourceRevision: Long,
    val rootRevision: Long,
    val productionSource: Boolean,
    val testSource: Boolean,
    val moduleId: String?,
    val sourceRoot: String?,
    val documentStamp: Long?,
) {
    fun matches(
        evidence: SourceEvidence,
        expectedSourceRevision: Long,
        expectedRootRevision: Long,
    ): Boolean =
        sourceRevision == expectedSourceRevision &&
            rootRevision == expectedRootRevision &&
            productionSource &&
            !testSource &&
            moduleId == evidence.owner.moduleId &&
            sourceRoot == evidence.owner.sourceRoot &&
            documentStamp == evidence.reference.documentStamp
}
