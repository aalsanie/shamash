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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import io.shamash.psi.core.source.SourceDependencyExtractor

internal class SourceNavigationService(
    private val project: Project,
) {
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
            .nonBlocking<VirtualFile?> { verify(evidence) }
            .inSmartMode(project)
            .expireWith(project)
            .expireWhen { expired() }
            .finishOnUiThread(ModalityState.any()) { file ->
                if (!expired()) completed(file != null && navigate(evidence, file))
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun verify(evidence: SourceEvidence): VirtualFile? {
        if (project.isDisposed) return null
        val reference = evidence.reference
        val owner = evidence.owner
        val target = evidence.target ?: return null
        val kind = evidence.kind ?: return null
        if (reference.fileUrl != owner.fileUrl || reference.offset < 0 || reference.length <= 0) return null

        val file = VirtualFileManager.getInstance().findFileByUrl(reference.fileUrl) ?: return null
        if (!file.isValid || file.isDirectory) return null

        val valid =
            ReadAction.compute<Boolean, RuntimeException> {
                if (project.isDisposed || DumbService.isDumb(project) || !file.isValid) return@compute false
                val index = ProjectFileIndex.getInstance(project)
                if (!index.isInSourceContent(file) ||
                    index.isInTestSourceContent(file) ||
                    index.getModuleForFile(file)?.name != owner.moduleId ||
                    index.getSourceRootForFile(file)?.url != owner.sourceRoot
                ) {
                    return@compute false
                }

                val doc = FileDocumentManager.getInstance().getDocument(file) ?: return@compute false
                val end = reference.offset.toLong() + reference.length.toLong()
                if (doc.modificationStamp != reference.documentStamp ||
                    end > doc.textLength || end <= reference.offset
                ) {
                    return@compute false
                }

                val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute false
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
                if (extracted.entities.none { it.id == owner }) return@compute false
                extracted.references.any {
                    it.from == owner && it.evidence == reference &&
                        it.target == target && it.kind == kind
                }
            }
        return file.takeIf { valid }
    }

    private fun navigate(
        evidence: SourceEvidence,
        file: VirtualFile,
    ): Boolean {
        val current = ReadAction.compute<Boolean, RuntimeException> {
            val index = ProjectFileIndex.getInstance(project)
            !project.isDisposed && file.isValid && !file.isDirectory &&
                index.isInSourceContent(file) && !index.isInTestSourceContent(file) &&
                index.getModuleForFile(file)?.name == evidence.owner.moduleId &&
                index.getSourceRootForFile(file)?.url == evidence.owner.sourceRoot &&
                FileDocumentManager.getInstance().getDocument(file)?.modificationStamp == evidence.reference.documentStamp
        }
        if (!current) return false
        OpenFileDescriptor(project, file, evidence.reference.offset).navigate(true)
        return true
    }
}
