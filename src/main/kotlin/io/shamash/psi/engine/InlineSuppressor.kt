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
package io.shamash.psi.engine

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil

/**
 * Inline suppression support.
 *
 * Supported formats:
 *  - Line comment directive: // shamash:ignore <ruleId>|all
 *  - Kotlin annotation: @Suppress("shamash:<ruleId>") or @Suppress("shamash:all")
 *  - Java annotation: @SuppressWarnings("shamash:<ruleId>") or @SuppressWarnings("shamash:all")
 */
internal object InlineSuppressor {
    private const val COMMENT_PREFIX = "shamash:ignore"
    private const val TOKEN_PREFIX = "shamash:"

    fun apply(
        findings: List<Finding>,
        file: PsiFile,
    ): List<Finding> {
        if (findings.isEmpty()) return findings

        // Fast file-level suppression via scanning text once.
        val commentDirectives = parseCommentDirectives(file.text)
        val fileWideSuppressed: Set<String> = commentDirectives.fileWide
        val hasFileWideAll = fileWideSuppressed.contains("all")

        val out = ArrayList<Finding>(findings.size)
        for (f in findings) {
            if (hasFileWideAll || fileWideSuppressed.contains(f.ruleId)) continue

            val anchorOffset = f.startOffset ?: locateAnchorOffset(file, f)
            val anchorLine = anchorOffset?.let { commentDirectives.lineOfOffset(it) }

            if (anchorLine != null) {
                if (commentDirectives.isSuppressedAtLine(anchorLine, f.ruleId)) continue
            }

            if (isSuppressedByAnnotation(file, f)) continue

            out += f
        }

        return out
    }

    private fun isSuppressedByAnnotation(
        file: PsiFile,
        f: Finding,
    ): Boolean {
        val element = findTargetElement(file, f) ?: return false

        // Java-style annotations (PsiModifierListOwner)
        val owner = element as? PsiModifierListOwner
        if (owner != null) {
            val modifierList = owner.modifierList
            val annotations = modifierList?.annotations.orEmpty()
            for (ann in annotations) {
                val qName = ann.qualifiedName ?: continue
                if (qName.endsWith("Suppress") || qName.endsWith("SuppressWarnings")) {
                    val text = ann.parameterList.text ?: ann.text
                    if (text.contains("\"${TOKEN_PREFIX}all\"") || text.contains("\"${TOKEN_PREFIX}${f.ruleId}\"")) {
                        return true
                    }
                }
            }
        }

        // Kotlin-style annotations without depending on Kotlin PSI:
        // scan up to 3 lines above the declaration for @Suppress("shamash:...")
        val vf = file.virtualFile
        val isKotlin = vf?.extension?.lowercase() == "kt"
        if (isKotlin) {
            val doc =
                com.intellij.psi.PsiDocumentManager
                    .getInstance(file.project)
                    .getDocument(file) ?: return false
            val off = element.textRange?.startOffset ?: return false
            val line = doc.getLineNumber(off)
            val fromLine = maxOf(0, line - 3)
            val start = doc.getLineStartOffset(fromLine)
            val end = doc.getLineStartOffset(line)
            val window = doc.text.substring(start, end)
            if (window.contains("@Suppress(\"${TOKEN_PREFIX}all\"") || window.contains("@Suppress(\"${TOKEN_PREFIX}${f.ruleId}\"")) {
                return true
            }
        }

        return false
    }

    private fun locateAnchorOffset(
        file: PsiFile,
        f: Finding,
    ): Int? {
        val element = findTargetElement(file, f) ?: return null
        return element.textRange?.startOffset
    }

    private fun findTargetElement(
        file: PsiFile,
        f: Finding,
    ): com.intellij.psi.PsiElement? {
        val member = f.memberName
        val clsFqn = f.classFqn

        if (clsFqn.isNullOrBlank() && member.isNullOrBlank()) return file

        val simpleClassName = clsFqn?.substringAfterLast('.')
        val named = PsiTreeUtil.findChildrenOfType(file, com.intellij.psi.PsiNamedElement::class.java)

        val classCandidates =
            if (simpleClassName.isNullOrBlank()) {
                emptyList()
            } else {
                named.filter { it.name == simpleClassName }
            }

        val classElement = classCandidates.firstOrNull() ?: file

        if (member.isNullOrBlank()) return classElement

        // Find member by name under the class element (or file if class not found)
        val scopeRoot = classElement
        val members = PsiTreeUtil.findChildrenOfType(scopeRoot, com.intellij.psi.PsiNamedElement::class.java)
        return members.firstOrNull { it.name == member } ?: classElement
    }

    private data class CommentDirectives(
        val lineToRules: Map<Int, Set<String>>,
        val fileWide: Set<String>,
        val lineStartOffsets: IntArray,
    ) {
        fun lineOfOffset(offset: Int): Int {
            // Binary search into lineStartOffsets
            var lo = 0
            var hi = lineStartOffsets.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val start = lineStartOffsets[mid]
                val nextStart = if (mid + 1 < lineStartOffsets.size) lineStartOffsets[mid + 1] else Int.MAX_VALUE
                if (offset < start) {
                    hi = mid - 1
                } else if (offset >= nextStart) {
                    lo = mid + 1
                } else {
                    return mid
                }
            }
            return 0
        }

        fun isSuppressedAtLine(
            line: Int,
            ruleId: String,
        ): Boolean {
            // allow directive on same line or up to 2 lines above
            for (l in line downTo maxOf(0, line - 2)) {
                val rules = lineToRules[l] ?: continue
                if (rules.contains("all") || rules.contains(ruleId)) return true
            }
            return false
        }
    }

    private fun parseCommentDirectives(text: String): CommentDirectives {
        val lines = text.split('\n')
        val lineToRules = mutableMapOf<Int, Set<String>>()
        val fileWide = mutableSetOf<String>()

        // precompute line start offsets
        val starts = IntArray(lines.size.coerceAtLeast(1))
        var off = 0
        for (i in lines.indices) {
            starts[i] = off
            off += lines[i].length + 1
        }

        for (i in lines.indices) {
            val raw = lines[i]
            val idx = raw.indexOf(COMMENT_PREFIX)
            if (idx < 0) continue
            val tail = raw.substring(idx + COMMENT_PREFIX.length).trim()
            if (tail.isBlank()) continue

            val tokens =
                tail
                    .split(',', ' ', '\t')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { it.removePrefix(TOKEN_PREFIX) }
                    .toSet()

            if (tokens.isEmpty()) continue

            // If directive is on the very first non-empty line, treat it as file-wide.
            // Users can still do element-scoped suppression by placing directive near declaration.
            if (i == 0) {
                fileWide.addAll(tokens)
            } else {
                lineToRules[i] = tokens
            }
        }

        return CommentDirectives(lineToRules, fileWide, starts)
    }
}
