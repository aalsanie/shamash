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
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSuperExpression
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.util.PsiTreeUtil

class MakeMethodsStaticFix : LocalQuickFix {
    override fun getName(): String = "Make methods static"

    override fun getFamilyName(): String = "Shamash utility class fixes"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val element = descriptor.psiElement ?: return
        if (!element.isValid) return

        val targetMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        val targetClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

        WriteCommandAction
            .writeCommandAction(project)
            .withName("Shamash: make methods static")
            .run<RuntimeException> {
                when {
                    targetMethod != null -> makeMethodStaticIfSafe(targetMethod)
                    targetClass != null -> makeAllMethodsStaticIfSafe(targetClass)
                }
            }
    }

    private fun makeAllMethodsStaticIfSafe(psiClass: PsiClass) {
        psiClass.methods
            .asSequence()
            .filterNot { it.isConstructor }
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .filterNot { it.hasModifierProperty(PsiModifier.ABSTRACT) }
            .forEach { makeMethodStaticIfSafe(it) }
    }

    private fun makeMethodStaticIfSafe(method: PsiMethod) {
        if (!method.isValid) return
        if (method.isConstructor) return
        if (method.hasModifierProperty(PsiModifier.STATIC)) return
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) return

        // Conservative: only make static if it doesn't depend on instance state.
        if (!isSafeToMakeStatic(method)) return

        method.modifierList.setModifierProperty(PsiModifier.STATIC, true)
    }

    /**
     * Returns false if the method references:
     * - `this` / `super`
     * - non-static fields
     * - non-static methods (including implicit calls like foo() where foo is instance)
     */
    private fun isSafeToMakeStatic(method: PsiMethod): Boolean {
        var safe = true

        method.accept(
            object : JavaRecursiveElementWalkingVisitor() {
                override fun visitThisExpression(expression: PsiThisExpression) {
                    safe = false
                    stopWalking()
                }

                override fun visitSuperExpression(expression: PsiSuperExpression) {
                    safe = false
                    stopWalking()
                }

                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    if (!safe) return
                    val resolved = expression.resolve()

                    when (resolved) {
                        is PsiField -> {
                            if (!resolved.hasModifierProperty(PsiModifier.STATIC)) {
                                safe = false
                                stopWalking()
                            }
                        }

                        is PsiMethod -> {
                            // Ignore self-reference, and ignore constructors (not relevant)
                            if (resolved == method) return
                            if (resolved.isConstructor) return

                            if (!resolved.hasModifierProperty(PsiModifier.STATIC)) {
                                safe = false
                                stopWalking()
                            }
                        }
                    }

                    super.visitReferenceExpression(expression)
                }
            },
        )

        return safe
    }
}
