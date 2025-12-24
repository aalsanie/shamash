package io.shamash.psi.introspections

import com.intellij.psi.util.PsiUtil

import com.intellij.codeInspection.*
import com.intellij.psi.*

class UtilityClassInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {

        return object : JavaElementVisitor() {

            override fun visitClass(psiClass: PsiClass) {
                if (psiClass.isInterface || psiClass.isEnum) return
                if (psiClass.name == null) return

                val isUtilityCandidate =
                    psiClass.name!!.endsWith("Util") ||
                            psiClass.name!!.endsWith("Helper") ||
                            io.shamash.psi.util.PsiUtil.isFinalWithPrivateConstructor(psiClass)

                if (!isUtilityCandidate) return

                // Rule 1: must be final
                if (!psiClass.hasModifierProperty(PsiModifier.FINAL)) {
                    holder.registerProblem(
                        psiClass.nameIdentifier ?: return,
                        "Shamash: Utility class must be final"
                    )
                }

                // Rule 2: no non-static fields
                psiClass.fields
                    .filter { !it.hasModifierProperty(PsiModifier.STATIC) }
                    .forEach {
                        holder.registerProblem(
                            it.nameIdentifier ?: return,
                            "Shamash: Utility class must not have instance fields"
                        )
                    }

                // Rule 3: all methods static
                psiClass.methods
                    .filter { !it.isConstructor }
                    .filter { !it.hasModifierProperty(PsiModifier.STATIC) }
                    .forEach {
                        holder.registerProblem(
                            it.nameIdentifier ?: return,
                            "Shamash: Utility class methods must be static"
                        )
                    }

                // Rule 4: no DI annotations
                if (io.shamash.psi.util.PsiUtil.hasSpringStereotype(psiClass)) {
                    holder.registerProblem(
                        psiClass.nameIdentifier ?: return,
                        "Shamash: Utility class must not be a managed component"
                    )
                }
            }
        }
    }
}
