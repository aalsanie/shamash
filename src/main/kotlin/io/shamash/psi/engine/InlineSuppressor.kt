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

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * Inline suppression support.
 *
 * Supported formats:
 *  - Line comment directive: // shamash:ignore <ruleId>|all
 *    - Directive on the first non-empty line is treated as file-wide suppression.
 *    - Directive can appear on the same line as the declaration or up to 2 lines above it.
 *
 *  - Kotlin annotation: @Suppress("shamash:<ruleId>") or @Suppress("shamash:all")
 *    - Works without Kotlin PSI dependency: scans up to 3 lines above the declaration.
 *
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

        val text = file.text ?: return findings

        val commentDirectives = parseCommentDirectives(text)
        val fileWideSuppressed: Set<String> = commentDirectives.fileWide
        val hasFileWideAll = fileWideSuppressed.contains("all")

        val out = ArrayList<Finding>(findings.size)
        for (f in findings) {
            // 1) file-wide comment suppression
            if (hasFileWideAll || fileWideSuppressed.contains(f.ruleId)) {
                continue
            }

            // 2) find an anchor offset
            val anchorOffset =
                f.startOffset
                    ?: locateAnchorOffset(file, f)
                    ?: guessAnchorOffsetFromText(text, f)

            // 3) line-comment suppression near anchor
            val anchorLine = anchorOffset?.let { commentDirectives.lineOfOffset(it) }
            if (anchorLine != null && commentDirectives.isSuppressedAtLine(anchorLine, f.ruleId)) {
                continue
            }

            // 4) annotation suppression (Java PSI annotations, Kotlin text window)
            if (isSuppressedByAnnotation(file, text, f, anchorOffset, commentDirectives)) {
                continue
            }

            out += f
        }

        return out
    }

    private fun isSuppressedByAnnotation(
        file: PsiFile,
        fileText: String,
        f: Finding,
        anchorOffset: Int?,
        commentDirectives: CommentDirectives,
    ): Boolean {
        val element = findTargetElement(file, f)

        // --- Java-style: PSI annotations when available (also works for Kotlin light classes sometimes) ---
        val owner = element as? PsiModifierListOwner
        if (owner != null) {
            val annotations = owner.modifierList?.annotations.orEmpty()
            for (ann in annotations) {
                val qName = ann.qualifiedName ?: continue
                if (!qName.endsWith("Suppress") && !qName.endsWith("SuppressWarnings")) continue

                val annText = ann.parameterList.text ?: ann.text ?: continue
                if (annText.contains("\"${TOKEN_PREFIX}all\"") || annText.contains("\"${TOKEN_PREFIX}${f.ruleId}\"")) {
                    return true
                }
            }
        }

        // --- Kotlin-style: do NOT rely on Document; use file text window (works in tests + non-physical PSI) ---
        val isKotlin = file.virtualFile?.extension?.lowercase() == "kt"
        if (!isKotlin) return false

        /**
         * IMPORTANT:
         * Kotlin PSI elements (e.g., KtClass) frequently report textRange.startOffset at the *first modifier*,
         * which can be the '@Suppress' line. Our window extraction is [fromLine, toLine) (exclusive of toLine),
         * so if declLine is the annotation line, the window will NOT include the annotation itself.
         *
         * Therefore, for Kotlin we must prefer an anchor that points at the *declaration keyword* line
         * (e.g., "class BadService"), which is exactly what [anchorOffset] / [guessAnchorOffsetFromText] provides.
         */
        val declOffset =
            // For Kotlin PSI, element/textRange often starts at the first modifier/annotation.
            // We want the *declaration* line (e.g., "class BadService") so the window includes @Suppress above it.
            guessAnchorOffsetFromText(fileText, f)
                ?: anchorOffset
                ?: element?.textRange?.startOffset
                ?: return false

        val declLine = commentDirectives.lineOfOffset(declOffset)
        val fromLine = maxOf(0, declLine - 3)
        val window = commentDirectives.linesWindow(fromLine, declLine)

        // Accept both @Suppress("shamash:...") and @kotlin.Suppress("shamash:...")
        return window.contains("@Suppress(\"${TOKEN_PREFIX}all\"") ||
            window.contains("@Suppress(\"${TOKEN_PREFIX}${f.ruleId}\"") ||
            window.contains("@kotlin.Suppress(\"${TOKEN_PREFIX}all\"") ||
            window.contains("@kotlin.Suppress(\"${TOKEN_PREFIX}${f.ruleId}\"")
    }

    private fun locateAnchorOffset(
        file: PsiFile,
        f: Finding,
    ): Int? {
        val element = findTargetElement(file, f) ?: return null
        if (element === file) return null
        return element.textRange?.startOffset
    }

    private fun findTargetElement(
        file: PsiFile,
        f: Finding,
    ): PsiElement? {
        val clsFqn = f.classFqn
        val member = f.memberName

        if (clsFqn.isNullOrBlank() && member.isNullOrBlank()) return null

        val simpleClassName = clsFqn?.substringAfterLast('.')?.takeIf { it.isNotBlank() }

        val named: Collection<PsiNamedElement> =
            PsiTreeUtil.findChildrenOfType(file, PsiNamedElement::class.java)

        val classElement =
            if (simpleClassName == null) null else named.firstOrNull { it.name == simpleClassName }

        if (member.isNullOrBlank()) return classElement

        val memberElement = named.firstOrNull { it.name == member }
        return memberElement ?: classElement
    }

    private fun guessAnchorOffsetFromText(
        text: String,
        f: Finding,
    ): Int? {
        val cls =
            f.classFqn
                ?.substringAfterLast('.')
                ?.trim()
                .takeIf { !it.isNullOrBlank() }

        if (cls != null) {
            val patterns =
                listOf(
                    "class $cls",
                    "object $cls",
                    "interface $cls",
                    "enum class $cls",
                    "data class $cls",
                    "sealed class $cls",
                    "annotation class $cls",
                )
            for (p in patterns) {
                val idx = text.indexOf(p)
                if (idx >= 0) return idx
            }
        }

        val member = f.memberName?.trim().takeIf { !it.isNullOrBlank() }
        if (member != null) {
            val patterns = listOf("fun $member", "val $member", "var $member")
            for (p in patterns) {
                val idx = text.indexOf(p)
                if (idx >= 0) return idx
            }
        }

        return null
    }

    private data class CommentDirectives(
        val lineToRules: Map<Int, Set<String>>,
        val fileWide: Set<String>,
        val lineStartOffsets: IntArray,
        val text: String,
    ) {
        fun lineOfOffset(offset: Int): Int {
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
            for (l in line downTo maxOf(0, line - 2)) {
                val rules = lineToRules[l] ?: continue
                if (rules.contains("all") || rules.contains(ruleId)) return true
            }
            return false
        }

        /** Inclusive [fromLine], exclusive [toLine]. */
        fun linesWindow(
            fromLine: Int,
            toLine: Int,
        ): String {
            if (fromLine >= toLine) return ""
            if (lineStartOffsets.isEmpty()) return ""

            val safeFrom = fromLine.coerceIn(0, lineStartOffsets.size - 1)
            val safeTo = toLine.coerceIn(0, lineStartOffsets.size)

            if (safeFrom >= safeTo) return ""

            val start = lineStartOffsets.getOrElse(safeFrom) { 0 }
            val end = if (safeTo < lineStartOffsets.size) lineStartOffsets[safeTo] else text.length
            if (start >= end) return ""
            return text.substring(start, minOf(end, text.length))
        }
    }

    private fun buildLineStartOffsets(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf(0)

        var lines = 1
        for (i in text.indices) {
            if (text[i] == '\n') lines++
        }

        val starts = IntArray(lines)
        starts[0] = 0

        var lineIdx = 1
        for (i in text.indices) {
            if (text[i] == '\n') {
                val next = i + 1
                if (lineIdx < starts.size) {
                    starts[lineIdx] = next
                    lineIdx++
                }
            }
        }

        return starts
    }

    private fun parseCommentDirectives(text: String): CommentDirectives {
        val lines = text.split('\n')
        val starts = buildLineStartOffsets(text)

        val lineToRules = mutableMapOf<Int, Set<String>>()
        val fileWide = mutableSetOf<String>()

        var firstNonEmptyLine = 0
        run {
            for (i in lines.indices) {
                if (lines[i].trim().isNotEmpty()) {
                    firstNonEmptyLine = i
                    return@run
                }
            }
        }

        for (i in lines.indices) {
            val raw = lines[i].trimEnd('\r')

            val idx = raw.indexOf(COMMENT_PREFIX)
            if (idx < 0) continue

            val tail = raw.substring(idx + COMMENT_PREFIX.length).trim()
            if (tail.isBlank()) continue

            val tokens =
                tail
                    .split(',', ' ', '\t')
                    .asSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { it.removePrefix(TOKEN_PREFIX) }
                    .toSet()

            if (tokens.isEmpty()) continue

            if (i == firstNonEmptyLine) {
                fileWide.addAll(tokens)
            } else {
                lineToRules[i] = tokens
            }
        }

        return CommentDirectives(
            lineToRules = lineToRules,
            fileWide = fileWide,
            lineStartOffsets = starts,
            text = text,
        )
    }
}
