/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.psi.core.fixes.providers

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.PsiResolver
import io.shamash.psi.core.fixes.ShamashFix

/**
 * Provides suppression fixes for any finding.
 *
 * Supported formats (must match engine suppressors):
 *  - Line comment directive: // shamash:ignore <ruleId>|all
 *  - Kotlin: @Suppress("shamash:<ruleId>|all")
 *  - Java: @SuppressWarnings("shamash:<ruleId>|all")
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
        val annFix = SuppressAsAnnotationFix(project, file, element, f, f.ruleId)
        return listOf(commentFix, annFix)
    }

    private class SuppressAsCommentFix(
        private val project: Project,
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
            val line = doc.getLineNumber(offset)
            val lineStart = doc.getLineStartOffset(line)

            val indent = leadingWhitespaceAt(doc, lineStart)
            val directive = "$indent// shamash:ignore $ruleId\n"

            WriteCommandAction.runWriteCommandAction(project) {
                // De-dup where it matters: current line or the line directly above.
                if (hasTokenOnLineOrPrev(doc, line, "shamash:ignore $ruleId") ||
                    hasTokenOnLineOrPrev(doc, line, "shamash:ignore all")
                ) {
                    return@runWriteCommandAction
                }

                doc.insertString(lineStart, directive)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }

    private class SuppressAsAnnotationFix(
        private val project: Project,
        private val file: PsiFile,
        private val element: PsiElement,
        private val finding: Finding,
        private val ruleId: String,
    ) : ShamashFix {
        override val id: String = "suppress.annotation.$ruleId"
        override val title: String = "Suppress (annotation)"

        override fun isApplicable(): Boolean = file.isValid && element.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return

            val ext = file.virtualFile?.extension?.lowercase() ?: ""
            val annCore =
                when (ext) {
                    "kt" -> "@Suppress(\"shamash:$ruleId\")"
                    else -> "@SuppressWarnings(\"shamash:$ruleId\")"
                }

            val insertOffset = computeInsertionLineStartOffset(doc) ?: return
            val line = doc.getLineNumber(insertOffset)
            val lineStart = doc.getLineStartOffset(line)

            val indent = leadingWhitespaceAt(doc, lineStart)
            val annLine = "$indent$annCore\n"

            WriteCommandAction.runWriteCommandAction(project) {
                // De-dup locally (annotation can be above the declaration line).
                if (hasTokenOnLineOrPrev(doc, line, "shamash:$ruleId") ||
                    hasTokenOnLineOrPrev(doc, line, "shamash:all")
                ) {
                    return@runWriteCommandAction
                }

                doc.insertString(lineStart, annLine)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        /**
         * We want class/member suppression, so we insert ABOVE the declaration.
         *
         * If the finding has no class/member anchor (file-scoped rules),
         * we fallback to the first top-level declaration (class/object/interface/fun/val/var),
         * never before the package/imports.
         */
        private fun computeInsertionLineStartOffset(doc: Document): Int? {
            val text = file.text
            val headerEnd = headerEndOffset(text)

            // 1) If this is declaration-scoped, try to anchor directly to PSI element.
            val hasAnchorInfo = !finding.classFqn.isNullOrBlank() || !finding.memberName.isNullOrBlank()
            if (hasAnchorInfo) {
                val offset = element.textRange?.startOffset
                if (offset != null && offset > 0 && offset > headerEnd) {
                    val line = doc.getLineNumber(offset)
                    return doc.getLineStartOffset(line)
                }
            }

            // 2) Fallback: first top-level declaration after header.
            val firstDeclaration = firstTopLevelDeclarationOffset(text)
            if (firstDeclaration != null) {
                val line = doc.getLineNumber(firstDeclaration)
                return doc.getLineStartOffset(line)
            }

            // 3) Last resort: insert after header end (never at 0).
            val line = doc.getLineNumber(minOf(headerEnd, doc.textLength))
            return doc.getLineStartOffset(line)
        }

        /**
         * Returns the offset right AFTER the last import line (or after package line if no imports).
         * Text-based so it works for Kotlin + Java consistently.
         */
        private fun headerEndOffset(text: String): Int {
            var last = 0

            // end of package line
            Regex("(?m)^\\s*package\\s+[^\\n]+\\n").find(text)?.let {
                last = maxOf(last, it.range.last + 1)
            }

            // end of last import line
            Regex("(?m)^\\s*import\\s+[^\\n]+\\n")
                .findAll(text)
                .forEach { last = maxOf(last, it.range.last + 1) }

            return last
        }

        /**
         * Find the first top-level declaration after the header (package/imports).
         */
        private fun firstTopLevelDeclarationOffset(text: String): Int? {
            val start = headerEndOffset(text)
            if (start >= text.length) return null

            val re =
                Regex(
                    pattern =
                        "(?m)^(?:\\s*@[^\\n]+\\n)*\\s*(class|object|interface|enum\\s+class|data\\s+class|sealed\\s+class|annotation\\s+class|fun|val|var)\\b",
                )

            return re.find(text, startIndex = start)?.range?.first
        }
    }

    private companion object {
        private fun leadingWhitespaceAt(
            doc: Document,
            lineStart: Int,
        ): String {
            val text = doc.charsSequence
            var i = lineStart
            while (i < doc.textLength) {
                val c = text[i]
                if (c != ' ' && c != '\t') break
                i++
            }
            return text.subSequence(lineStart, i).toString()
        }

        private fun hasTokenOnLineOrPrev(
            doc: Document,
            line: Int,
            token: String,
        ): Boolean {
            fun lineText(l: Int): String {
                if (l < 0) return ""
                val start = doc.getLineStartOffset(l)
                val end = doc.getLineEndOffset(l)
                return doc.text.substring(start, end)
            }

            val cur = lineText(line)
            val prev = lineText(line - 1)
            return cur.contains(token) || prev.contains(token)
        }
    }
}
