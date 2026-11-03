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

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager

internal class SourceNavigationService(private val project: Project) {
    fun open(evidence: SourceEvidence): Boolean {
        if (project.isDisposed) return false
        val reference = evidence.reference
        val owner = evidence.owner
        if (reference.fileUrl != owner.fileUrl || reference.offset < 0 || reference.length <= 0) return false

        val file = VirtualFileManager.getInstance().findFileByUrl(reference.fileUrl) ?: return false
        if (!file.isValid || file.isDirectory) return false

        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInSourceContent(file) ||
            index.isInTestSourceContent(file) ||
            index.getModuleForFile(file)?.name != owner.moduleId ||
            index.getSourceRootForFile(file)?.url != owner.sourceRoot
        ) {
            return false
        }

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        val end = reference.offset.toLong() + reference.length.toLong()
        if (document.modificationStamp != reference.documentStamp ||
            end > document.textLength ||
            end <= reference.offset
        ) {
            return false
        }

        OpenFileDescriptor(project, file, reference.offset).navigate(true)
        return true
    }
}
