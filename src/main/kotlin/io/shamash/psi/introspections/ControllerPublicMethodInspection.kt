package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.ControllerRules
import io.shamash.psi.fixes.RemovePublicModifierFix
import io.shamash.psi.util.ShamashMessages.msg

class ControllerPublicMethodInspection
    : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            ControllerRules.excessPublicMethods(psiClass)
                .forEach {
                    holder.registerProblem(
                        it.nameIdentifier ?: return,
                        msg("Controller must expose only one public endpoint"),
                        RemovePublicModifierFix()
                    )
                }
        }
    }
}
