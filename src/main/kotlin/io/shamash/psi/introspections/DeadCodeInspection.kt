package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.DeadCodeRules
import io.shamash.psi.fixes.DeleteUnusedElementFix
import io.shamash.psi.util.ShamashMessages.msg

class DeadCodeInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            if (!DeadCodeRules.isUnusedClass(psiClass)) return

            holder.registerProblem(
                psiClass.nameIdentifier ?: return,
                msg("class appears unused"),
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                DeleteUnusedElementFix("Delete unused class")
            )
        }

        override fun visitMethod(method: PsiMethod) {
            if (!DeadCodeRules.isUnusedMethod(method)) return

            holder.registerProblem(
                method.nameIdentifier ?: return,
                msg("method appears unused"),
                ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                DeleteUnusedElementFix("Delete unused method")
            )
        }
    }
}
