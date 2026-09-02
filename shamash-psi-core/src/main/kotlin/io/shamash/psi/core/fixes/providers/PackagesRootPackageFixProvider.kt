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
package io.shamash.psi.core.fixes.providers

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.config.schema.v1.model.RootPackageModeV1
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.PsiResolver
import io.shamash.psi.core.fixes.RuleDefLookup
import io.shamash.psi.core.fixes.ShamashFix

class PackagesRootPackageFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project
        val psiFile = PsiResolver.resolveFile(project, f.filePath) ?: return emptyList()

        val actualPkg = readDeclaredPackage(psiFile) ?: return emptyList()

        val expectedRoot =
            expectedRootPackage(ctx, f)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

        val fixes = ArrayList<ShamashFix>(2)

        val newPkg =
            when {
                actualPkg == expectedRoot -> null
                actualPkg.startsWith("$expectedRoot.") -> null
                else -> "$expectedRoot.$actualPkg"
            }

        if (newPkg != null) {
            fixes += ChangeFilePackageFix(project, psiFile, newPkg)
        }

        ctx.configFile?.let { cfgVf ->
            fixes += SetRootPackageInConfigFix(project, cfgVf, actualPkg)
        }

        return fixes
    }

    private fun expectedRootPackage(
        ctx: FixContext,
        f: Finding,
    ): String? {
        val cfg = ctx.config
        val rp = cfg?.project?.rootPackage
        if (rp != null && rp.mode == RootPackageModeV1.EXPLICIT && rp.value.isNotBlank()) {
            return rp.value
        }

        val fromFinding =
            f.data["expectedRootPackage"]
                ?: f.data["rootPackage"]
                ?: f.data["expectedRoot"]
        if (!fromFinding.isNullOrBlank()) return fromFinding.trim()

        val wildcard = cfg?.let { RuleDefLookup.findWildcardRuleDef(it, "packages", "rootPackage") }
        return wildcard?.params?.get("value") as? String
    }

    private fun readDeclaredPackage(file: PsiFile): String? {
        val text = file.text ?: return null
        val m = PKG_LINE_RE.find(text) ?: return null
        return m.groupValues.getOrNull(1)?.trim()
    }

    class ChangeFilePackageFix(
        private val project: Project,
        private val file: PsiFile,
        private val newPackage: String,
    ) : ShamashFix {
        override val id: String = "packages.rootPackage.changeFile.$newPackage"
        override val title: String = "Move file package to: $newPackage"

        override fun isApplicable(): Boolean = file.isValid

        override fun apply() {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
            val isKotlin = file.virtualFile?.extension?.lowercase() == "kt"

            WriteCommandAction.runWriteCommandAction(project) {
                val currentText = doc.text ?: return@runWriteCommandAction
                val currentMatch = PKG_STMT_RE.find(currentText)

                if (currentMatch != null) {
                    val replacement = if (isKotlin) "package $newPackage" else "package $newPackage;"
                    doc.replaceString(currentMatch.range.first, currentMatch.range.last + 1, replacement)
                } else {
                    val insertion = if (isKotlin) "package $newPackage\n\n" else "package $newPackage;\n\n"
                    val insertAt = safeHeaderInsertionOffset(currentText)
                    doc.insertString(insertAt, insertion)
                }

                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        /** Insert after leading comments and blank lines to preserve license headers. */
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

    /** Updates project.rootPackage.value and the wildcard rootPackage rule, if present. */
    class SetRootPackageInConfigFix(
        private val project: Project,
        private val cfg: VirtualFile,
        private val newRootPackage: String,
    ) : ShamashFix {
        override val id: String = "packages.rootPackage.setConfig.$newRootPackage"
        override val title: String = "Set config rootPackage to: $newRootPackage"

        override fun isApplicable(): Boolean = cfg.isValid

        override fun apply() {
            val psiFile = PsiManager.getInstance(project).findFile(cfg) ?: return
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                val text = doc.text ?: return@runWriteCommandAction

                val updated1 = rewriteProjectRootPackageValue(text, newRootPackage)
                val updated2 = rewriteRulesListWildcardRootPackageValue(updated1, newRootPackage)

                if (updated2 == text) return@runWriteCommandAction

                val normalized = ensureNewlineAfterPackage(updated2)

                doc.replaceString(0, doc.textLength, normalized)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        private fun rewriteProjectRootPackageValue(
            text: String,
            value: String,
        ): String {
            val projectStart = PROJECT_BLOCK_RE.find(text) ?: return text
            val projectIndent = projectStart.groupValues[1]
            val projectFrom = projectStart.range.last + 1

            val projectEnd = findBlockEnd(text, projectFrom, projectIndent)
            val projectBlock = text.substring(projectFrom, projectEnd)

            val rootStart = Regex("(?m)^(${projectIndent}\\s{2})rootPackage\\s*:\\s*$").find(projectBlock) ?: return text
            val rootIndent = rootStart.groupValues[1]
            val rootAbsFrom = projectFrom + rootStart.range.last + 1

            val rootEnd = findBlockEnd(text, rootAbsFrom, rootIndent)
            val rootBlock = text.substring(rootAbsFrom, rootEnd)

            val valueMatch = Regex("(?m)^\\s*value\\s*:\\s*.*$").find(rootBlock) ?: return text

            val absStart = rootAbsFrom + valueMatch.range.first
            val absEnd = rootAbsFrom + valueMatch.range.last + 1

            val replacement =
                valueMatch.value.replace(
                    Regex("(?m)(^\\s*value\\s*:\\s*).*$"),
                    "$1\"$value\"",
                )

            return text.substring(0, absStart) + replacement + text.substring(absEnd)
        }

        private fun rewriteRulesListWildcardRootPackageValue(
            text: String,
            value: String,
        ): String {
            val rulesStart = RULES_BLOCK_RE.find(text) ?: return text
            val rulesIndent = rulesStart.groupValues[1]
            val rulesFrom = rulesStart.range.last + 1
            val rulesEnd = findBlockEnd(text, rulesFrom, rulesIndent)

            val rulesBlock = text.substring(rulesFrom, rulesEnd)

            val item =
                findRuleListItemBlock(
                    rulesBlock = rulesBlock,
                    absoluteBlockStart = rulesFrom,
                    listIndent = "$rulesIndent  ",
                    type = "packages",
                    name = "rootPackage",
                ) ?: return text

            val itemText = text.substring(item.start, item.end)

            val paramsStart = Regex("(?m)^\\s*params\\s*:\\s*$").find(itemText) ?: return text
            val paramsAbsFrom = item.start + paramsStart.range.last + 1

            val paramsIndent = itemText.substring(paramsStart.range.first).takeWhile { it == ' ' || it == '\t' }
            val paramsEnd = findBlockEnd(text, paramsAbsFrom, paramsIndent)

            val paramsBlock = text.substring(paramsAbsFrom, paramsEnd)
            val valueMatch = Regex("(?m)^\\s*value\\s*:\\s*.*$").find(paramsBlock) ?: return text

            val absStart = paramsAbsFrom + valueMatch.range.first
            val absEnd = paramsAbsFrom + valueMatch.range.last + 1

            val replacement =
                valueMatch.value.replace(
                    Regex("(?m)(^\\s*value\\s*:\\s*).*$"),
                    "$1\"$value\"",
                )

            return text.substring(0, absStart) + replacement + text.substring(absEnd)
        }

        private data class Block(
            val start: Int,
            val end: Int,
        )

        private fun findRuleListItemBlock(
            rulesBlock: String,
            absoluteBlockStart: Int,
            listIndent: String,
            type: String,
            name: String,
        ): Block? {
            val itemRe = Regex("(?m)^${Regex.escape(listIndent)}-\\s+.*$")
            val items = itemRe.findAll(rulesBlock).toList()
            if (items.isEmpty()) return null

            for (idx in items.indices) {
                val itemStartInBlock = items[idx].range.first
                val itemAbsStart = absoluteBlockStart + itemStartInBlock

                val itemAbsEnd =
                    if (idx == items.lastIndex) {
                        absoluteBlockStart + rulesBlock.length
                    } else {
                        absoluteBlockStart + items[idx + 1].range.first
                    }

                val itemText = rulesBlock.substring(itemStartInBlock, (itemAbsEnd - absoluteBlockStart))

                val typeOk = Regex("(?m)^\\s*type\\s*:\\s*${Regex.escape(type)}\\s*$").containsMatchIn(itemText)
                val nameOk = Regex("(?m)^\\s*name\\s*:\\s*${Regex.escape(name)}\\s*$").containsMatchIn(itemText)
                if (!typeOk || !nameOk) continue

                // Only update wildcard rules; a roles list targets a specific instance.
                val rolesLine = Regex("(?m)^\\s*roles\\s*:\\s*(.*)$").find(itemText)
                if (rolesLine != null) {
                    val rhs = rolesLine.groupValues[1].trim()
                    val wildcard = rhs.isEmpty() || rhs == "null" || rhs == "~"
                    if (!wildcard) continue
                }

                return Block(itemAbsStart, itemAbsEnd)
            }

            return null
        }

        private fun ensureNewlineAfterPackage(text: String): String {
            val m = Regex("""(?m)^\s*package\s+[A-Za-z0-9_.]+\s*""").find(text) ?: return text

            val end = m.range.last + 1
            val rest = text.substring(end)

            if (!rest.startsWith("\n") && !rest.startsWith("\r\n")) {
                return text.substring(0, end) + "\n\n" + rest
            }

            val afterOneNl =
                rest.removePrefix("\r\n").let { r ->
                    if (r !== rest) Pair("\r\n", r) else Pair("\n", rest.removePrefix("\n"))
                }

            val nl = afterOneNl.first
            val r1 = afterOneNl.second

            val nextNonWs = r1.dropWhile { it == ' ' || it == '\t' }
            if (nextNonWs.startsWith("import")) {
                return text.substring(0, end) + nl + nl + r1
            }

            return text
        }

        /** Exclusive block end: first non-empty line with indentation <= baseIndent. */
        private fun findBlockEnd(
            text: String,
            from: Int,
            baseIndent: String,
        ): Int {
            val tail = text.substring(from)
            var offset = from

            for (line in tail.splitToSequence('\n')) {
                val trimmed = line.trim()
                val nextOffset = offset + line.length + 1

                if (trimmed.isNotEmpty()) {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    if (indent.length <= baseIndent.length && offset != from) {
                        return offset
                    }
                }

                offset = nextOffset
                if (offset >= text.length) return text.length
            }

            return text.length
        }
    }

    companion object {
        private const val RULE_ID = "packages.rootPackage"

        private val PKG_LINE_RE = Regex("(?m)^\\s*package\\s+([a-zA-Z0-9_\\.]+)\\s*;?\\s*$")
        private val PKG_STMT_RE = Regex("(?m)^\\s*package\\s+[a-zA-Z0-9_\\.]+\\s*;?")

        private val PROJECT_BLOCK_RE = Regex("(?m)^(\\s*)project\\s*:\\s*$")
        private val RULES_BLOCK_RE = Regex("(?m)^(\\s*)rules\\s*:\\s*$")

        private val BLOCK_COMMENT_RE = Regex("^\\s*/\\*.*?\\*/\\s*", setOf(RegexOption.DOT_MATCHES_ALL))
        private val LINE_COMMENT_RE = Regex("^\\s*//.*\\n\\s*")
    }
}
