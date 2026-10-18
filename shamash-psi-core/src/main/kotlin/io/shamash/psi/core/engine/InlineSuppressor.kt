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
package io.shamash.psi.core.engine

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.artifacts.contract.Finding
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtDeclaration

/**
 * `// shamash:ignore <ruleId>|all` suppresses the file on its first non-empty line,
 * otherwise the declaration on that line or within the next two lines.
 * Annotations use `shamash:<ruleId>` or `shamash:all`; Kotlin lookup scans three preceding lines.
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

        val commentDirectives = parseCommentDirectives(file, text)
        val fileWideSuppressed: Set<String> = commentDirectives.fileWide
        val hasFileWideAll = fileWideSuppressed.contains("all")

        val out = ArrayList<Finding>(findings.size)
        for (f in findings) {
            if (hasFileWideAll || fileWideSuppressed.contains(f.ruleId)) {
                continue
            }

            val anchorOffset =
                f.startOffset
                    ?: locateAnchorOffset(file, f)
                    ?: guessAnchorOffsetFromText(text, f)

            val anchorLine = anchorOffset?.let { commentDirectives.lineOfOffset(it) }
            if (anchorLine != null && commentDirectives.isSuppressedAtLine(anchorLine, f.ruleId)) {
                continue
            }

            if (isSuppressedByAnnotation(file, f, anchorOffset, commentDirectives)) {
                continue
            }

            out += f
        }

        return out
    }

    private fun isSuppressedByAnnotation(
        file: PsiFile,
        f: Finding,
        anchorOffset: Int?,
        commentDirectives: CommentDirectives,
    ): Boolean {
        val element = findTargetElement(file, f)

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

        if (file.virtualFile?.extension?.lowercase() != "kt") return false

        val declaration =
            (element as? KtDeclaration)
                ?: anchorOffset?.let { offset ->
                    file.findElementAt(offset)?.let { PsiTreeUtil.getParentOfType(it, KtDeclaration::class.java, false) }
                }
        return commentDirectives.isSuppressedByKotlinAnnotation(declaration, f.ruleId)
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
        val annotationRules: Map<KtDeclaration, Set<String>>,
        val lineStartOffsets: IntArray,
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

        fun isSuppressedByKotlinAnnotation(
            declaration: KtDeclaration?,
            ruleId: String,
        ): Boolean {
            val rules = annotationRules[declaration] ?: return false
            return "all" in rules || ruleId in rules
        }
    }

    private fun lineOfOffset(
        starts: IntArray,
        offset: Int,
    ): Int {
        val match = starts.binarySearch(offset)
        return if (match >= 0) match else (-match - 2).coerceAtLeast(0)
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

    private fun parseCommentDirectives(
        file: PsiFile,
        text: String,
    ): CommentDirectives {
        val starts = buildLineStartOffsets(text)
        val lineToRules = mutableMapOf<Int, Set<String>>()
        val fileWide = mutableSetOf<String>()
        val firstNonEmptyLine = text.lineSequence().indexOfFirst { it.trim().isNotEmpty() }.coerceAtLeast(0)

        for (comment in PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java)) {
            val commentText = comment.text
            val commentStart = comment.textRange.startOffset
            var offset = 0

            for (line in commentText.split('\n')) {
                val index = line.indexOf(COMMENT_PREFIX)
                if (index >= 0) {
                    val tail =
                        line
                            .substring(index + COMMENT_PREFIX.length)
                            .substringBefore("*/")
                            .trim()
                    val tokens =
                        tail
                            .split(',', ' ', '\t')
                            .asSequence()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .map { it.removePrefix(TOKEN_PREFIX) }
                            .toSet()

                    if (tokens.isNotEmpty()) {
                        val lineNumber = lineOfOffset(starts, commentStart + offset + index)
                        if (lineNumber == firstNonEmptyLine) {
                            fileWide.addAll(tokens)
                        } else {
                            lineToRules[lineNumber] = lineToRules[lineNumber].orEmpty() + tokens
                        }
                    }
                }
                offset += line.length + 1
            }
        }

        val annotationRules = mutableMapOf<KtDeclaration, Set<String>>()
        if (file.virtualFile?.extension?.lowercase() == "kt") {
            for (annotation in PsiTreeUtil.findChildrenOfType(file, KtAnnotationEntry::class.java)) {
                if (annotation.shortName?.asString() != "Suppress") continue

                val rules =
                    annotation.valueArguments
                        .mapNotNull { it.getArgumentExpression()?.text }
                        .filter { it.startsWith("\"${TOKEN_PREFIX}") && it.endsWith('"') }
                        .map { it.removeSurrounding("\"").removePrefix(TOKEN_PREFIX) }
                        .toSet()
                if (rules.isEmpty()) continue

                val declaration = PsiTreeUtil.getParentOfType(annotation, KtDeclaration::class.java, false) ?: continue
                annotationRules[declaration] = annotationRules[declaration].orEmpty() + rules
            }
        }

        return CommentDirectives(
            lineToRules = lineToRules,
            fileWide = fileWide,
            annotationRules = annotationRules,
            lineStartOffsets = starts,
        )
    }
}
