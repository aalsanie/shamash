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
package io.shamash.psi.fixes.providers

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixProvider
import io.shamash.psi.fixes.PsiResolver
import io.shamash.psi.fixes.ShamashFix

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

            // For comment suppression, current behavior is acceptable: it will suppress at the anchor line.
            val offset = element.textRange?.startOffset ?: return
            val lineStart = doc.getLineStartOffset(doc.getLineNumber(offset))
            val directive = "// shamash:ignore $ruleId\n"

            WriteCommandAction.runWriteCommandAction(project) {
                val tail = doc.text.substring(lineStart)
                if (tail.contains("shamash:ignore") && tail.contains(ruleId)) return@runWriteCommandAction

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
            val annLine =
                when (ext) {
                    "kt" -> "@Suppress(\"shamash:$ruleId\")\n"
                    else -> "@SuppressWarnings(\"shamash:$ruleId\")\n"
                }

            val insertOffset = computeInsertionLineStartOffset(doc) ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                // De-dup in a small window around insertion point
                val windowStart = maxOf(0, insertOffset - 512)
                val windowEnd = minOf(doc.textLength, insertOffset + 512)
                val window = doc.text.substring(windowStart, windowEnd)

                if (window.contains("shamash:$ruleId") || window.contains("shamash:all")) {
                    return@runWriteCommandAction
                }

                doc.insertString(insertOffset, annLine)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        /**
         * We want class/member suppression, so we insert ABOVE the declaration.
         *
         * If the finding has no class/member anchor (file-scoped rules like packages.rootPackage),
         * we fallback to the first top-level declaration (class/object/interface/fun/val/var),
         * never before the package.
         */
        private fun computeInsertionLineStartOffset(doc: com.intellij.openapi.editor.Document): Int? {
            val text = file.text ?: return null
            val ext = file.virtualFile?.extension?.lowercase() ?: ""

            // 1) Try to anchor to the best PSI element if the finding is declaration-scoped.
            val hasAnchorInfo = !finding.classFqn.isNullOrBlank() || !finding.memberName.isNullOrBlank()
            if (hasAnchorInfo) {
                val target = bestTargetElement(file, finding) ?: element
                val declOffset = safeDeclarationOffset(file, target, finding) ?: 0

                // If we got a non-header decl offset, use it.
                if (declOffset > 0 && !isInFileHeader(text, declOffset, ext)) {
                    val line = doc.getLineNumber(declOffset)
                    return doc.getLineStartOffset(line)
                }
            }

            // 2) Fallback: find first top-level declaration after package/imports.
            val firstDecl = firstTopLevelDeclarationOffset(text)
            if (firstDecl != null) {
                val line = doc.getLineNumber(firstDecl)
                return doc.getLineStartOffset(line)
            }

            // 3) Last resort: insert after header (package/imports). Never at 0.
            val headerEnd = headerEndOffset(text)
            val line = doc.getLineNumber(minOf(headerEnd, doc.textLength))
            return doc.getLineStartOffset(line)
        }

        private fun bestTargetElement(
            file: PsiFile,
            f: Finding,
        ): PsiElement? {
            val member = f.memberName?.trim().takeIf { !it.isNullOrBlank() }
            val cls =
                f.classFqn
                    ?.substringAfterLast('.')
                    ?.trim()
                    .takeIf { !it.isNullOrBlank() }

            val named: Collection<PsiNamedElement> =
                PsiTreeUtil.findChildrenOfType(file, PsiNamedElement::class.java)

            if (member != null) {
                named.firstOrNull { it.name == member }?.let { return it }
            }
            if (cls != null) {
                named.firstOrNull { it.name == cls }?.let { return it }
            }
            return null
        }

        private fun safeDeclarationOffset(
            file: PsiFile,
            target: PsiElement,
            f: Finding,
        ): Int? {
            val start = target.textRange?.startOffset

            // If PSI start is usable and not header-ish, take it.
            if (start != null && start > 0) return start

            // Otherwise, use text heuristics (works for Kotlin where PSI start can point at modifiers).
            val text = file.text ?: return start

            val cls =
                f.classFqn
                    ?.substringAfterLast('.')
                    ?.trim()
                    .takeIf { !it.isNullOrBlank() }
            val member = f.memberName?.trim().takeIf { !it.isNullOrBlank() }

            if (member != null) {
                findFirstOf(text, listOf("fun $member", "val $member", "var $member"))?.let { return it }
            }
            if (cls != null) {
                findFirstOf(
                    text,
                    listOf(
                        "class $cls",
                        "object $cls",
                        "interface $cls",
                        "enum class $cls",
                        "data class $cls",
                        "sealed class $cls",
                        "annotation class $cls",
                    ),
                )?.let { return it }
            }

            return start
        }

        private fun findFirstOf(
            text: String,
            patterns: List<String>,
        ): Int? {
            for (p in patterns) {
                val idx = text.indexOf(p)
                if (idx >= 0) return idx
            }
            return null
        }

        /**
         * Kotlin-safe header detection (doesn't rely on Java PSI types).
         */
        private fun isInFileHeader(
            text: String,
            offset: Int,
            ext: String,
        ): Boolean {
            // For both Java/Kotlin: use text-based header end (package + imports).
            // It's safer across PSI implementations.
            val headerEnd = headerEndOffset(text)
            return offset <= headerEnd
        }

        /**
         * Returns the offset right AFTER the last import line (or after package line if no imports).
         */
        private fun headerEndOffset(text: String): Int {
            var last = 0

            // end of package line
            run {
                val m = Regex("(?m)^\\s*package\\s+[^\\n]+\\n").find(text)
                if (m != null) last = maxOf(last, m.range.last + 1)
            }

            // end of last import line (scan all)
            Regex("(?m)^\\s*import\\s+[^\\n]+\\n")
                .findAll(text)
                .forEach { last = maxOf(last, it.range.last + 1) }

            return last
        }

        /**
         * Find the first top-level declaration after the header (package/imports).
         * This avoids inserting before package for file-scoped findings.
         */
        private fun firstTopLevelDeclarationOffset(text: String): Int? {
            val start = headerEndOffset(text)
            if (start >= text.length) return null

            // Looks for lines starting with optional annotations, then a decl keyword.
            val re =
                Regex(
                    pattern =
                        "(?m)^(?:\\s*@[^\\n]+\\n)*\\s*(class|object|interface|enum\\s+class|data\\s+class|sealed\\s+class|annotation\\s+class|fun|val|var)\\b",
                )

            val m = re.find(text, startIndex = start) ?: return null
            return m.range.first
        }
    }
}
