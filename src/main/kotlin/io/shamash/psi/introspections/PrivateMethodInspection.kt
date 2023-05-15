package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.LayerRules
import io.shamash.psi.fixes.RemovePrivateModifierFix
import io.shamash.psi.util.ShamashMessages.msg

class PrivateMethodInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitMethod(method: PsiMethod) {
            if (!method.hasModifierProperty(PsiModifier.PRIVATE)) return

            val psiClass = method.containingClass ?: return
            val layer = LayerDetector.detect(psiClass)

            if (LayerRules.allowsPrivateMethods(layer)) return

            holder.registerProblem(
                method.nameIdentifier ?: return,
                msg("Private methods are not allowed in $layer layers"),
                RemovePrivateModifierFix()
            )
        }
    }
}
