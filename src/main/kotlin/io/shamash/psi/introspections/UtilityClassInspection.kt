package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.fixes.*
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

            val id = psiClass.nameIdentifier ?: return

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
                    MakeFieldsStaticFix()
                )
            }

            if (!psiClass.hasOnlyStaticMethods()) {
                holder.registerProblem(
                    id,
                    msg("Utility class methods must be static"),
                    MakeMethodsStaticFix()
                )
            }

            // NEW: private constructor requirement.
            if (!psiClass.hasOnlyPrivateConstructors()) {
                holder.registerProblem(
                    id,
                    msg("Utility class must have a private constructor"),
                    MakePrivateConstructorFix()
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

    private fun PsiClass.hasOnlyPrivateConstructors(): Boolean {
        // If there are no explicit constructors, Java provides a public default ctor -> violation.
        val ctors = this.constructors
        if (ctors.isEmpty()) return false

        return ctors.all { it.hasModifierProperty(PsiModifier.PRIVATE) }
    }
}
