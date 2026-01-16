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
import com.intellij.psi.PsiFile
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.PsiResolver
import io.shamash.psi.core.fixes.ShamashFix

/**
 * Fix provider for arch.forbiddenRoleDependencies.
 *
 * Provides a pragmatic suppression fix:
 * - Inserts a `// shamash:ignore <ruleId>` directive on the line of the first textual reference
 *   to the forbidden dependency's simple type name in the source file.
 *
 * NOTE:
 * - This does not refactor code or remove the dependency; it only suppresses the finding locally.
 * - We keep insertion indentation consistent with the target line.
 */
class ArchForbiddenRoleDependenciesFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val toTypeFqn = f.data[TO_TYPE_FQN_KEY]?.trim().takeIf { !it.isNullOrBlank() } ?: return emptyList()
        val simple = toTypeFqn.substringAfterLast('.').trim().takeIf { it.isNotBlank() } ?: return emptyList()

        val file = PsiResolver.resolveFile(ctx.project, f.filePath) ?: return emptyList()
        return listOf(SuppressAtFirstReferenceFix(ctx.project, file, simple, f.ruleId))
    }

    private class SuppressAtFirstReferenceFix(
        private val project: Project,
        private val file: PsiFile,
        private val typeSimpleName: String,
        private val ruleId: String,
    ) : ShamashFix {
        override val id: String = "arch.forbiddenRoleDependencies.suppress.at.reference.$typeSimpleName"
        override val title: String = "Suppress this dependency (at first reference)"

        override fun isApplicable(): Boolean = file.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val text = doc.text

            // Best-effort: first textual occurrence of the type simple name.
            val idx = text.indexOf(typeSimpleName)
            if (idx < 0) return

            val line = doc.getLineNumber(idx)
            val lineStart = doc.getLineStartOffset(line)
            val indent = leadingWhitespaceAt(doc, lineStart)

            val directive = "$indent// shamash:ignore $ruleId\n"

            WriteCommandAction.runWriteCommandAction(project) {
                // Avoid stacking duplicate directives on current/previous line.
                if (hasTokenOnLineOrPrev(doc, line, "shamash:ignore $ruleId")) return@runWriteCommandAction

                doc.insertString(lineStart, directive)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }

    private companion object {
        private const val RULE_ID = "arch.forbiddenRoleDependencies"
        private const val TO_TYPE_FQN_KEY = "toTypeFqn"

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
