package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.*
import io.shamash.psi.util.ShamashMessages.msg

class ServiceControllerDependencyInspection
    : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val layer = LayerDetector.detect(psiClass)
            if (layer != Layer.SERVICE) return

            if (!LayerRules.isDependencyAllowed(layer, Layer.CONTROLLER)
                && DependencyQueries.violatesLayerDependency(
                    psiClass,
                    layer,
                    Layer.CONTROLLER
                )
            ) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("Services must not depend on controllers")
                )
            }
        }
    }
}
