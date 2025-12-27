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
package io.shamash.psi.introspections

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import io.shamash.psi.architecture.DeadCodeRules
import io.shamash.psi.fixes.DeleteUnusedElementFix
import io.shamash.psi.util.ShamashMessages.msg

class DeadCodeInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ) = object : JavaElementVisitor() {
        override fun visitClass(psiClass: PsiClass) {
            if (!DeadCodeRules.isUnusedClass(psiClass)) return
            holder.registerProblem(
                psiClass.nameIdentifier ?: return,
                msg("class appears unused"),
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                DeleteUnusedElementFix("Delete unused class"),
            )
        }

        override fun visitMethod(method: PsiMethod) {
            if (!DeadCodeRules.isUnusedMethod(method)) return
            holder.registerProblem(
                method.nameIdentifier ?: return,
                msg("method appears unused"),
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                DeleteUnusedElementFix("Delete unused method"),
            )
        }

        override fun visitField(field: PsiField) {
            if (!DeadCodeRules.isUnusedField(field)) return
            holder.registerProblem(
                field.nameIdentifier ?: return,
                msg("field appears unused"),
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                DeleteUnusedElementFix("Delete unused field"),
            )
        }
    }
}
