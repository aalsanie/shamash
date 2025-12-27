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
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiModifier

class MakePrivateConstructorFix : LocalQuickFix {
    override fun getName(): String = "Make constructor private"

    override fun getFamilyName(): String = "Shamash utility fixes"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val psiClass =
            (descriptor.psiElement.parent as? PsiClass)
                ?: (descriptor.psiElement as? PsiClass)
                ?: return

        val className = psiClass.name ?: return

        WriteCommandAction
            .writeCommandAction(project)
            .withName("Shamash: Make utility constructor private")
            .run<RuntimeException> {
                val constructors = psiClass.constructors

                if (constructors.isEmpty()) {
                    // No explicit constructors -> add a private no-arg constructor.
                    val factory = PsiElementFactory.getInstance(project)
                    val ctor = factory.createConstructor(className)
                    ctor.modifierList?.setModifierProperty(PsiModifier.PRIVATE, true)

                    // Insert near top (after fields if you prefer; this is fine and stable)
                    psiClass.add(ctor)
                    return@run
                }

                // Make all existing constructors private.
                constructors.forEach { ctor ->
                    ctor.modifierList?.let { mods ->
                        // Clear other visibility flags then set private.
                        mods.setModifierProperty(PsiModifier.PUBLIC, false)
                        mods.setModifierProperty(PsiModifier.PROTECTED, false)
                        mods.setModifierProperty(PsiModifier.PRIVATE, true)
                    }
                }
            }
    }
}
