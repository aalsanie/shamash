package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.LayerRules
import io.shamash.psi.util.ShamashMessages.msg

class MethodCountInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val layer = LayerDetector.detect(psiClass) ?: return
            val count = LayerRules.exceedsMethodLimit(psiClass, layer) ?: return

            holder.registerProblem(
                psiClass.nameIdentifier ?: return,
                msg("$layer has $count methods (exceeds allowed limit)")
            )
        }
    }
}
