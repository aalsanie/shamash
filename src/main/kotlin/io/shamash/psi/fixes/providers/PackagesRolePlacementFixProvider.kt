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

class PackagesRolePlacementFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == "packages.rolePlacement"

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
        val patterns = allowedJoined.split('|').map { it.trim() }.filter { it.isNotBlank() }
        for (p in patterns) {
            val guess = guessPackageFromRegex(p)
            if (!guess.isNullOrBlank()) return guess
        }
        return null
    }

    /**
     * Heuristic extraction of a base package from a regex.
     * Examples:
     *  - ^com\\.acme\\.app(\\..*)?$ -> com.acme.app
     *  - com\\.acme\\.infra\\..* -> com.acme.infra
     */
    private fun guessPackageFromRegex(regex: String): String? {
        var s = regex.trim()
        s = s.removePrefix("^").removeSuffix("$")
        // common tails
        s = s.replace("(\\..*)?", "")
        s = s.replace("(\\..+)?", "")
        s = s.replace("\\..*", "")
        s = s.replace("\\..+", "")
        s = s.replace(".*", "")
        // unescape dots
        s = s.replace("\\\\.", ".")
        // drop remaining regex metacharacters
        s = s.replace(Regex("[\\[\\]\\(\\)\\?\\+\\*\\|]"), "")
        s = s.trim('.').trim()
        // package sanity check
        if (s.isBlank()) return null
        if (s.contains(' ')) return null
        return s
    }

    private class ChangePackageStatementFix(
        private val project: com.intellij.openapi.project.Project,
        private val file: com.intellij.psi.PsiFile,
        private val newPackage: String,
    ) : ShamashFix {
        override val id: String = "packages.changePackage.$newPackage"
        override val title: String = "Change package to '$newPackage'"

        override fun isApplicable(): Boolean = file.isValid && file.virtualFile != null

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val text = doc.text

            val isKotlin = file.virtualFile?.extension?.lowercase() == "kt"
            val pkgKeyword = if (isKotlin) "package " else "package "

            // Replace first package statement if found; else insert at file start.
            val pkgRegex = Regex("^\\s*package\\s+([a-zA-Z0-9_\\.]+)\\s*;?", setOf(RegexOption.MULTILINE))
            val m = pkgRegex.find(text)
            WriteCommandAction.runWriteCommandAction(project) {
                if (m != null) {
                    val range = m.range
                    val replacement = if (isKotlin) "${pkgKeyword}$newPackage" else "${pkgKeyword}$newPackage;"
                    doc.replaceString(range.first, range.last + 1, replacement)
                } else {
                    val insertion = if (isKotlin) "${pkgKeyword}${newPackage}\n\n" else "${pkgKeyword}$newPackage;\n\n"
                    doc.insertString(0, insertion)
                }
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }
    }
}
