package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.DependencyQueries
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.util.ShamashMessages.msg

class ControllerDaoDependencyInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            if (
                LayerDetector.detect(psiClass) == Layer.CONTROLLER &&
                DependencyQueries.dependsOnPackage(psiClass, ".dao")
            ) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("Controller must not depend on DAO"),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                )
            }
        }
    }
}
