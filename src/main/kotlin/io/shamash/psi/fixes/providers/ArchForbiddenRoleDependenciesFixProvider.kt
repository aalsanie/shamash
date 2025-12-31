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
import com.intellij.psi.PsiDocumentManager
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixProvider
import io.shamash.psi.fixes.PsiResolver
import io.shamash.psi.fixes.ShamashFix

class ArchForbiddenRoleDependenciesFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == "arch.forbiddenRoleDependencies"

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val toTypeFqn = f.data["toTypeFqn"] ?: return emptyList()
        val simple = toTypeFqn.substringAfterLast('.')
        val file = PsiResolver.resolveFile(ctx.project, f.filePath) ?: return emptyList()
        return listOf(SuppressAtFirstReferenceFix(ctx.project, file, simple, f.ruleId))
    }

    private class SuppressAtFirstReferenceFix(
        private val project: com.intellij.openapi.project.Project,
        private val file: com.intellij.psi.PsiFile,
        private val typeSimpleName: String,
        private val ruleId: String,
    ) : ShamashFix {
        override val id: String = "arch.suppress.at.reference.$typeSimpleName"
        override val title: String = "Suppress this dependency (at first reference)"

        override fun isApplicable(): Boolean = file.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val text = doc.text
            val idx = text.indexOf(typeSimpleName)
            if (idx < 0) return

            val line = doc.getLineNumber(idx)
            val lineStart = doc.getLineStartOffset(line)
            val directive = "// shamash:ignore $ruleId\n"

            WriteCommandAction.runWriteCommandAction(project) {
                // avoid stacking duplicate directives
                val windowStart = maxOf(0, lineStart - 200)
                val windowEnd = minOf(doc.textLength, lineStart + 200)
                val window = doc.text.substring(windowStart, windowEnd)
                if (window.contains("shamash:ignore") && window.contains(ruleId)) return@runWriteCommandAction
                doc.insertString(lineStart, directive)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }
}
