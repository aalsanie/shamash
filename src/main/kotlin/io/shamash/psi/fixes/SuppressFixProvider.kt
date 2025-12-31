/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
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
package io.shamash.psi.fixes

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.shamash.psi.engine.Finding

/**
 * Provides suppression fixes for any finding.
 */
class SuppressFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = true

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project
        val file = PsiResolver.resolveFile(project, f.filePath) ?: return emptyList()
        val element = PsiResolver.resolveElement(project, f) ?: file
        val commentFix = SuppressAsCommentFix(project, file, element, f.ruleId)
        val annFix = SuppressAsAnnotationFix(project, file, element, f.ruleId)
        return listOf(commentFix, annFix)
    }

    private class SuppressAsCommentFix(
        private val project: com.intellij.openapi.project.Project,
        private val file: PsiFile,
        private val element: PsiElement,
        private val ruleId: String,
    ) : ShamashFix {
        override val id: String = "suppress.comment.$ruleId"
        override val title: String = "Suppress (comment)"

        override fun isApplicable(): Boolean = file.isValid && element.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val offset = element.textRange?.startOffset ?: return
            val lineStart = doc.getLineStartOffset(doc.getLineNumber(offset))
            val directive = "// shamash:ignore $ruleId\n"
            WriteCommandAction.runWriteCommandAction(project) {
                if (doc.text.substring(lineStart).contains("shamash:ignore") && doc.text.substring(lineStart).contains(ruleId)) {
                    return@runWriteCommandAction
                }
                doc.insertString(lineStart, directive)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }

    private class SuppressAsAnnotationFix(
        private val project: com.intellij.openapi.project.Project,
        private val file: PsiFile,
        private val element: PsiElement,
        private val ruleId: String,
    ) : ShamashFix {
        override val id: String = "suppress.annotation.$ruleId"
        override val title: String = "Suppress (annotation)"

        override fun isApplicable(): Boolean = file.isValid && element.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val offset = element.textRange?.startOffset ?: return
            val line = doc.getLineNumber(offset)
            val lineStart = doc.getLineStartOffset(line)

            val ext = file.virtualFile?.extension?.lowercase() ?: ""
            val annLine =
                when (ext) {
                    "kt" -> "@Suppress(\"shamash:$ruleId\")\n"
                    else -> "@SuppressWarnings(\"shamash:$ruleId\")\n"
                }

            WriteCommandAction.runWriteCommandAction(project) {
                // naive de-dup: if line already has shamash suppression nearby, don't add
                val existingWindowStart = maxOf(0, lineStart - 256)
                val existingWindowEnd = minOf(doc.textLength, lineStart + 256)
                val window = doc.text.substring(existingWindowStart, existingWindowEnd)
                if (window.contains("shamash:$ruleId") || window.contains("shamash:all")) {
                    return@runWriteCommandAction
                }
                doc.insertString(lineStart, annLine)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }
}
