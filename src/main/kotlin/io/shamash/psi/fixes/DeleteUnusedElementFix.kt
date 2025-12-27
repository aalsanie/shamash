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
package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteHandler

class DeleteUnusedElementFix(
    private val label: String = "Delete unused element",
) : LocalQuickFix {
    override fun getName(): String = label

    override fun getFamilyName(): String = "Shamash dead code fixes"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val leaf: PsiElement = descriptor.psiElement ?: return
        if (!leaf.isValid()) return

        val target: PsiElement =
            PsiTreeUtil.getParentOfType(
                leaf,
                PsiMethod::class.java,
                PsiField::class.java,
                PsiClass::class.java,
            ) ?: return

        if (!target.isValid()) return

        // Safe Delete performs final usage/conflict verification and supports preview/undo.
        SafeDeleteHandler.invoke(project, arrayOf(target), true)
    }
}
