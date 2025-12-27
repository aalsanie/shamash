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
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiTreeUtil

class MakeFieldsStaticFix : LocalQuickFix {
    override fun getName(): String = "Make fields static"

    override fun getFamilyName(): String = "Shamash utility class fixes"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val element = descriptor.psiElement ?: return
        if (!element.isValid) return

        val targetField = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        val targetClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

        WriteCommandAction
            .writeCommandAction(project)
            .withName("Shamash: Make fields static")
            .run<RuntimeException> {
                when {
                    targetField != null -> makeFieldStatic(targetField)
                    targetClass != null -> makeAllFieldsStatic(targetClass)
                }
            }
    }

    private fun makeAllFieldsStatic(psiClass: PsiClass) {
        psiClass.fields
            .asSequence()
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .forEach { makeFieldStatic(it) }
    }

    private fun makeFieldStatic(field: PsiField) {
        if (!field.isValid) return
        if (field.hasModifierProperty(PsiModifier.STATIC)) return
        field.modifierList?.setModifierProperty(PsiModifier.STATIC, true)
    }
}
