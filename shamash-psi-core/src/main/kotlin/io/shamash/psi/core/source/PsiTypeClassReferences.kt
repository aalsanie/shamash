/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiDisjunctionType
import com.intellij.psi.PsiIntersectionType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiWildcardType

/** Traverses generic arguments and variable bounds without losing inner types or recursing forever. */
object PsiTypeClassReferences {
    data class Result(
        val classes: List<PsiClass>,
        val truncated: Boolean,
    )

    fun collect(
        type: PsiType,
        maxDepth: Int = 128,
    ): Result {
        require(maxDepth > 0)
        val visited = HashSet<String>()
        val classes = LinkedHashSet<PsiClass>()
        var truncated = false

        fun visit(
            current: PsiType,
            depth: Int,
        ) {
            ProgressManager.checkCanceled()
            if (depth >= maxDepth) {
                truncated = true
                return
            }
            if (!visited.add(current.canonicalText)) return
            when (current) {
                is PsiCapturedWildcardType -> {
                    visit(current.wildcard, depth + 1)
                }

                is PsiWildcardType -> {
                    current.bound?.let { visit(it, depth + 1) }
                }

                is PsiArrayType -> {
                    visit(current.componentType, depth + 1)
                }

                is PsiIntersectionType -> {
                    current.conjuncts.forEach { visit(it, depth + 1) }
                }

                is PsiDisjunctionType -> {
                    current.disjunctions.forEach { visit(it, depth + 1) }
                }

                is PsiClassType -> {
                    val resolved = current.resolve()
                    if (resolved is PsiTypeParameter) {
                        resolved.extendsListTypes.forEach { visit(it, depth + 1) }
                    } else if (resolved != null) {
                        classes += resolved
                    }
                    current.parameters.forEach { visit(it, depth + 1) }
                }
            }
        }

        visit(type, 0)
        return Result(classes.toList(), truncated)
    }
}
