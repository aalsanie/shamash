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
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiModifier
import io.shamash.psi.fixes.MakeClassFinalFix
import io.shamash.psi.fixes.MakeFieldsStaticFix
import io.shamash.psi.fixes.MakeMethodsStaticFix
import io.shamash.psi.fixes.MakePrivateConstructorFix
import io.shamash.psi.fixes.RemoveSpringStereotypeFix
import io.shamash.psi.util.PsiUtil
import io.shamash.psi.util.ShamashMessages.msg
import io.shamash.psi.util.hasOnlyStaticFields
import io.shamash.psi.util.hasOnlyStaticMethods
import io.shamash.psi.util.isConcreteClass
import io.shamash.psi.util.isUtilityCandidate

class UtilityClassInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : JavaElementVisitor() {
            override fun visitClass(psiClass: PsiClass) {
                if (!psiClass.isConcreteClass()) return
                if (!psiClass.isUtilityCandidate()) return

                val id = psiClass.nameIdentifier ?: return

                if (!psiClass.hasModifierProperty(PsiModifier.FINAL)) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must be final"),
                        MakeClassFinalFix(),
                    )
                }

                if (!psiClass.hasOnlyStaticFields()) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must not have instance fields"),
                        MakeFieldsStaticFix(),
                    )
                }

                if (!psiClass.hasOnlyStaticMethods()) {
                    holder.registerProblem(
                        id,
                        msg("Utility class methods must be static"),
                        MakeMethodsStaticFix(),
                    )
                }

                if (!psiClass.hasOnlyPrivateConstructors()) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must have a private constructor"),
                        MakePrivateConstructorFix(),
                    )
                }

                if (PsiUtil.hasSpringStereotype(psiClass)) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must not be a managed component"),
                        RemoveSpringStereotypeFix(),
                    )
                }
            }
        }

    private fun PsiClass.hasOnlyPrivateConstructors(): Boolean {
        // If there are no explicit constructors, Java provides a public default ctor -> violation.
        val ctors = this.constructors
        if (ctors.isEmpty()) return false

        return ctors.all { it.hasModifierProperty(PsiModifier.PRIVATE) }
    }
}
