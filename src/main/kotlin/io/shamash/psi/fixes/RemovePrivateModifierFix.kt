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
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

class RemovePrivateModifierFix : LocalQuickFix {
    override fun getName(): String = "Remove private modifier"

    override fun getFamilyName(): String = "Shamash visibility fixes"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val method = descriptor.psiElement?.parent as? PsiMethod ?: return
        if (!method.isValid) return

        method.modifierList.setModifierProperty(PsiModifier.PRIVATE, false)
    }
}
