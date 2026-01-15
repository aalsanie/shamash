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
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.PsiResolver
import io.shamash.psi.core.fixes.ShamashFix

/**
 * Fixes for packages.rolePlacement violations.
 *
 * This fix changes a file’s package declaration to
 * the first concrete package that can be safely inferred from
 * the role’s allowed package patterns.
 *
 * The engine emits "allowed" as a '|' separated list of package-regex patterns.
 * This provider uses a best-effort heuristic to extract a concrete base package from those patterns.
 *
 * IMPORTANT:
 * - This fix is intentionally conservative: if we can't produce a sane-looking package, we return no fixes.
 * - We also preserve file headers (license/comments) when inserting a missing package statement.
 */
class PackagesRolePlacementFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project
        val file = PsiResolver.resolveFile(project, f.filePath) ?: return emptyList()

        val allowed = f.data["allowed"].orEmpty()
        val guessed = guessPackageFromAllowed(allowed) ?: return emptyList()

        return listOf(ChangePackageStatementFix(project, file, guessed))
    }

    private fun guessPackageFromAllowed(allowedJoined: String): String? {
        val patterns =
            allowedJoined
                .split('|')
                .map { it.trim() }
                .filter { it.isNotBlank() }

        for (p in patterns) {
            val guess = guessPackageFromRegex(p)
            if (!guess.isNullOrBlank()) return guess
        }
        return null
    }

    /**
     * Heuristic extraction of a base package from a regex.
     *
     * Examples:
     *  - ^com\\.acme\\.app(\\..*)?$ -> com.acme.app
     *  - com\\.acme\\.infra\\..* -> com.acme.infra
     */
    private fun guessPackageFromRegex(regex: String): String? {
        var s = regex.trim()
        if (s.isBlank()) return null

        s = s.removePrefix("^").removeSuffix("$")

        // Common tails in our allowlist patterns.
        s = s.replace("(\\..*)?", "")
        s = s.replace("(\\..+)?", "")
        s = s.replace("\\..*", "")
        s = s.replace("\\..+", "")
        s = s.replace(".*", "")

        // Unescape literal dots
        s = s.replace("\\\\.", ".")

        // Drop remaining regex metacharacters that can appear in patterns.
        s = s.replace(Regex("[\\[\\]\\(\\)\\?\\+\\*\\|]"), "")

        s = s.trim().trim('.')

        // Conservative sanity checks (avoid emitting garbage).
        if (s.isBlank()) return null
        if (!PACKAGE_RE.matches(s)) return null

        return s
    }

    private class ChangePackageStatementFix(
        private val project: Project,
        private val file: PsiFile,
        private val newPackage: String,
    ) : ShamashFix {
        override val id: String = "packages.rolePlacement.changePackage.$newPackage"
        override val title: String = "Change package to '$newPackage'"

        override fun isApplicable(): Boolean = file.isValid && file.virtualFile != null

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val isKotlin = file.virtualFile?.extension?.lowercase() == "kt"

            WriteCommandAction.runWriteCommandAction(project) {
                val text = doc.text ?: return@runWriteCommandAction
                val m = PKG_STMT_RE.find(text)

                if (m != null) {
                    val replacement = if (isKotlin) "package $newPackage" else "package $newPackage;"
                    doc.replaceString(m.range.first, m.range.last + 1, replacement)
                } else {
                    val insertion = if (isKotlin) "package $newPackage\n\n" else "package $newPackage;\n\n"
                    val insertAt = safeHeaderInsertionOffset(text)
                    doc.insertString(insertAt, insertion)
                }

                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        /**
         * Insert package after any leading license/comment block and blank lines.
         * Never blindly insert at 0 (keeps file headers intact).
         */
        private fun safeHeaderInsertionOffset(text: String): Int {
            var i = 0
            if (text.startsWith("\uFEFF")) i = 1 // BOM

            while (i < text.length) {
                val rest = text.substring(i)

                val block = BLOCK_COMMENT_RE.find(rest)
                if (block != null) {
                    i += block.value.length
                    continue
                }

                val line = LINE_COMMENT_RE.find(rest)
                if (line != null) {
                    i += line.value.length
                    continue
                }

                break
            }

            while (i < text.length && (text[i] == '\n' || text[i] == '\r' || text[i] == ' ' || text[i] == '\t')) {
                i++
            }

            return i.coerceIn(0, text.length)
        }
    }

    companion object {
        private const val RULE_ID = "packages.rolePlacement"

        // capture: package statement used for replacement (no trailing newline)
        private val PKG_STMT_RE = Regex("(?m)^\\s*package\\s+[a-zA-Z0-9_\\.]+\\s*;?")

        // conservative package sanity: a.b.c where segments are [A-Za-z_][A-Za-z0-9_]*
        private val PACKAGE_RE = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

        private val BLOCK_COMMENT_RE = Regex("^\\s*/\\*.*?\\*/\\s*", setOf(RegexOption.DOT_MATCHES_ALL))
        private val LINE_COMMENT_RE = Regex("^\\s*//.*\\n\\s*")
    }
}
