package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.fixes.MakeClassFinalFix
import io.shamash.psi.fixes.MakeMethodsStaticFix
import io.shamash.psi.fixes.RemoveSpringStereotypeFix
import io.shamash.psi.util.*
import io.shamash.psi.util.ShamashMessages.msg

class UtilityClassInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            if (!psiClass.isConcreteClass()) return
            if (!psiClass.isUtilityCandidate()) return

            psiClass.nameIdentifier?.let { id ->
                if (!psiClass.hasModifierProperty(PsiModifier.FINAL)) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must be final"),
                        MakeClassFinalFix()
                    )
                }

                if (!psiClass.hasOnlyStaticFields()) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must not have instance fields"),
                        MakeMethodsStaticFix()
                    )
                }

                if (!psiClass.hasOnlyStaticMethods()) {
                    holder.registerProblem(
                        id,
                        msg("Utility class methods must be static"),
                        MakeMethodsStaticFix()
                    )
                }

                if (PsiUtil.hasSpringStereotype(psiClass)) {
                    holder.registerProblem(
                        id,
                        msg("Utility class must not be a managed component"),
                        RemoveSpringStereotypeFix()
                    )
                }
            }
        }
    }
}
