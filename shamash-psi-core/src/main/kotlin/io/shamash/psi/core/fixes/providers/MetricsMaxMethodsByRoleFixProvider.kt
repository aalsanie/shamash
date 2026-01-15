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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.ShamashFix

/**
 * Fix provider for metrics.maxMethodsByRole.
 *
 * Engine contract:
 * - Finding.data["role"] is the role id
 * - Finding.data["actual"] is the computed methods count for that role (integer as string)
 *
 * Fix:
 * - Best-effort YAML update to set the configured max for the given role to the observed "actual" value.
 *
 * IMPORTANT:
 * - Config schema uses: rules: List<RuleDef>
 * - Rule identity is derived from (type,name,roles?) not user-defined ids.
 */
class MetricsMaxMethodsByRoleFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val role = f.data[ROLE_KEY]?.trim().takeIf { !it.isNullOrBlank() } ?: return emptyList()
        val actual = f.data[ACTUAL_KEY]?.toIntOrNull() ?: return emptyList()

        val cfgVf = ctx.configFile ?: return emptyList()
        return listOf(IncreaseMaxInConfigFix(ctx.project, cfgVf, role, actual))
    }

    private class IncreaseMaxInConfigFix(
        private val project: com.intellij.openapi.project.Project,
        private val cfg: VirtualFile,
        private val role: String,
        private val newMax: Int,
    ) : ShamashFix {
        override val id: String = "metrics.maxMethodsByRole.setMax.$role.$newMax"
        override val title: String = "Set max methods for role '$role' to $newMax (config)"

        override fun isApplicable(): Boolean = cfg.isValid

        override fun apply() {
            val psiFile = PsiManager.getInstance(project).findFile(cfg) ?: return
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

            WriteCommandAction.runWriteCommandAction(project) {
                val text = doc.text ?: return@runWriteCommandAction

                // 1) Prefer editing RuleDef in rules list:
                // rules:
                //   - type: metrics
                //     name: maxMethodsByRole
                //     roles: [ ... ] or null
                //     params:
                //       <roleKey>: <role>
                //       <maxKey>: <n>
                //
                // Since the exact param keys can vary, we use the engine-known keys:
                // - role
                // - max
                val updated = rewriteRulesListMaxMethodsByRole(text, role, newMax)
                if (updated == text) return@runWriteCommandAction

                doc.replaceString(0, doc.textLength, updated)
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
        }

        private fun rewriteRulesListMaxMethodsByRole(
            text: String,
            targetRole: String,
            maxValue: Int,
        ): String {
            val rulesStart = RULES_BLOCK_RE.find(text) ?: return text
            val rulesIndent = rulesStart.groupValues[1]
            val rulesFrom = rulesStart.range.last + 1
            val rulesEnd = findBlockEnd(text, rulesFrom, rulesIndent)

            val rulesBlock = text.substring(rulesFrom, rulesEnd)

            // Locate list item for (type=metrics, name=maxMethodsByRole)
            val item =
                findRuleListItemBlock(
                    rulesBlock = rulesBlock,
                    absoluteBlockStart = rulesFrom,
                    listIndent = "$rulesIndent  ",
                    type = "metrics",
                    name = "maxMethodsByRole",
                ) ?: return text

            val itemText = text.substring(item.start, item.end)

            // Must have params block
            val paramsStart = Regex("(?m)^\\s*params\\s*:\\s*$").find(itemText) ?: return text
            val paramsAbsFrom = item.start + paramsStart.range.last + 1

            val paramsIndent = itemText.substring(paramsStart.range.first).takeWhile { it == ' ' || it == '\t' }
            val paramsEnd = findBlockEnd(text, paramsAbsFrom, paramsIndent)

            // Ensure role matches inside params block
            val paramsBlock = text.substring(paramsAbsFrom, paramsEnd)
            val roleLine = Regex("(?m)^\\s*role\\s*:\\s*\\Q$targetRole\\E\\s*$").find(paramsBlock) ?: return text

            // Rewrite max inside the same params block.
            val maxLine = Regex("(?m)^\\s*max\\s*:\\s*(\\d+)\\s*$").find(paramsBlock) ?: return text
            if (maxLine.range.first < roleLine.range.first) {
                // "max" appears before the matching role line => ambiguous; bail.
                return text
            }

            val absStart = paramsAbsFrom + maxLine.range.first
            val absEnd = paramsAbsFrom + maxLine.range.last + 1

            val replacement = maxLine.value.replace(Regex("\\d+"), maxValue.toString())
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

                val itemText = rulesBlock.substring(itemStartInBlock, itemAbsEnd - absoluteBlockStart)

                val typeOk = Regex("(?m)^\\s*type\\s*:\\s*${Regex.escape(type)}\\s*$").containsMatchIn(itemText)
                val nameOk = Regex("(?m)^\\s*name\\s*:\\s*${Regex.escape(name)}\\s*$").containsMatchIn(itemText)
                if (typeOk && nameOk) return Block(itemAbsStart, itemAbsEnd)
            }

            return null
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

    private companion object {
        private const val RULE_ID = "metrics.maxMethodsByRole"
        private const val ROLE_KEY = "role"
        private const val ACTUAL_KEY = "actual"

        private val RULES_BLOCK_RE = Regex("(?m)^(\\s*)rules\\s*:\\s*$")
    }
}
