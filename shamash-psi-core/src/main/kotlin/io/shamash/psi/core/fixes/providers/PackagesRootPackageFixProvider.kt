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

/**
 * Fixes for packages.rootPackage violations.
 *
 * Provides two pragmatic fixes:
 *  1) Update the offending source file's package statement to be under the configured root package.
 *     Example: expected "com.myco", actual "a"  -> "com.myco.a"
 *
 *  2) Update the config YAML project.rootPackage.value to match the file's actual package (when configFile is available).
 *
 * NOTE:
 * - Prefer config.project.rootPackage when mode == EXPLICIT; otherwise fall back to finding data.
 * - YAML changes are best-effort, text-based, and scoped to the relevant block(s).
 */
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

        // Fix #1: change source file package (if not already under root)
        val newPkg =
            when {
                actualPkg == expectedRoot -> null
                actualPkg.startsWith("$expectedRoot.") -> null
                else -> "$expectedRoot.$actualPkg"
            }

        if (newPkg != null) {
            fixes += ChangeFilePackageFix(project, psiFile, newPkg)
        }

        // Fix #2: update config to match actual package (if we have configFile)
        ctx.configFile?.let { cfgVf ->
            fixes += SetRootPackageInConfigFix(project, cfgVf, actualPkg)
        }

        return fixes
    }

    private fun expectedRootPackage(
        ctx: FixContext,
        f: Finding,
    ): String? {
        // Prefer config.project.rootPackage when EXPLICIT.
        val cfg = ctx.config
        val rp = cfg?.project?.rootPackage
        if (rp != null && rp.mode == RootPackageModeV1.EXPLICIT && rp.value.isNotBlank()) {
            return rp.value
        }

        // Fallback: finding data (engine can provide expected root).
        val fromFinding =
            f.data["expectedRootPackage"]
                ?: f.data["rootPackage"]
                ?: f.data["expectedRoot"]
        if (!fromFinding.isNullOrBlank()) return fromFinding.trim()

        // Last fallback: wildcard rule params (type=name match with roles == null).
        val wildcard = cfg?.let { RuleDefLookup.findWildcardRuleDef(it, "packages", "rootPackage") }
        return wildcard?.params?.get("value") as? String
    }

    private fun readDeclaredPackage(file: PsiFile): String? {
        val text = file.text ?: return null
        val m = PKG_LINE_RE.find(text) ?: return null
        return m.groupValues.getOrNull(1)?.trim()
    }

    /**
     * Public top-level fix class (NOT private nested),
     * because FixesPanel previously invoked fixes via reflection.
     */
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

        /**
         * Insert package after any leading license/comment block and blank lines.
         * Never blindly insert at 0 (keeps file headers intact).
         */
        private fun safeHeaderInsertionOffset(text: String): Int {
            var i = 0
            if (text.startsWith("\uFEFF")) i = 1 // BOM

            // Skip initial block and line comments (license headers)
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

            // Skip blank-ish whitespace
            while (i < text.length && (text[i] == '\n' || text[i] == '\r' || text[i] == ' ' || text[i] == '\t')) {
                i++
            }

            return i.coerceIn(0, text.length)
        }
    }

    /**
     * Updates YAML (best-effort text rewrite):
     * - project.rootPackage.value
     * - AND (if present) the wildcard RuleDef in rules list:
     *     - type: packages
     *       name: rootPackage
     *       roles: null   (or omitted)
     *       params:
     *         value: "..."
     */
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

        /**
         * Rewrite rules list item for wildcard packages.rootPackage (roles == null / omitted).
         *
         * Expected YAML shape (list items):
         * rules:
         *   - type: packages
         *     name: rootPackage
         *     roles: null
         *     params:
         *       value: "..."
         */
        private fun rewriteRulesListWildcardRootPackageValue(
            text: String,
            value: String,
        ): String {
            val rulesStart = RULES_BLOCK_RE.find(text) ?: return text
            val rulesIndent = rulesStart.groupValues[1]
            val rulesFrom = rulesStart.range.last + 1
            val rulesEnd = findBlockEnd(text, rulesFrom, rulesIndent)

            val rulesBlock = text.substring(rulesFrom, rulesEnd)

            // Find the first list item whose (type,name) match.
            val item =
                findRuleListItemBlock(
                    rulesBlock = rulesBlock,
                    absoluteBlockStart = rulesFrom,
                    listIndent = "$rulesIndent  ",
                    type = "packages",
                    name = "rootPackage",
                ) ?: return text

            // Within that item, locate params block and rewrite first "value:" inside it.
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
            // Find all "- " items at the expected indent.
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

                // Ensure this is wildcard (roles null or roles omitted). If roles exists and is a list -> skip.
                val rolesLine = Regex("(?m)^\\s*roles\\s*:\\s*(.*)$").find(itemText)
                if (rolesLine != null) {
                    val rhs = rolesLine.groupValues[1].trim()
                    // treat "null", "~", "" as wildcard; list form indicates specific -> skip
                    val wildcard = rhs.isEmpty() || rhs == "null" || rhs == "~"
                    if (!wildcard) continue
                }

                return Block(itemAbsStart, itemAbsEnd)
            }

            return null
        }

        private fun ensureNewlineAfterPackage(text: String): String {
            // Match the first "package ..." line (Kotlin/Java)
            val m = Regex("""(?m)^\s*package\s+[A-Za-z0-9_.]+\s*""").find(text) ?: return text

            val end = m.range.last + 1
            // If immediately followed by "import" (possibly with whitespace), ensure there's a blank line.
            val rest = text.substring(end)

            // If there is no newline at all after package, add one
            if (!rest.startsWith("\n") && !rest.startsWith("\r\n")) {
                return text.substring(0, end) + "\n\n" + rest
            }

            // If there is exactly one newline and then import, make it two newlines.
            val afterOneNl =
                rest.removePrefix("\r\n").let { r ->
                    if (r !== rest) Pair("\r\n", r) else Pair("\n", rest.removePrefix("\n"))
                }

            val nl = afterOneNl.first
            val r1 = afterOneNl.second

            // If next token is "import" and we only have one newline, insert an extra newline.
            val nextNonWs = r1.dropWhile { it == ' ' || it == '\t' }
            if (nextNonWs.startsWith("import")) {
                // Ensure blank line between package and import
                return text.substring(0, end) + nl + nl + r1
            }

            return text
        }

        /**
         * Computes the end offset (exclusive) of an indented YAML block.
         * A block ends when we hit a non-empty line with indentation <= baseIndent.
         */
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
