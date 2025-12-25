package io.shamash.psi.introspections

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import io.shamash.psi.architecture.DependencyQueries
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.fixes.ReplaceOrCreateServiceForDaoFix
import io.shamash.psi.util.ShamashMessages.msg

class ControllerDaoDependencyInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : JavaElementVisitor() {

            override fun visitClass(psiClass: PsiClass) {
                if (LayerDetector.detect(psiClass) != Layer.CONTROLLER) return

                val sources = DependencyQueries.layerDependencySources(psiClass, Layer.DAO)
                if (sources.isEmpty()) return

                sources.forEach { source ->
                    holder.registerProblem(
                        source,
                        msg("Controller must not depend on DAO"),
                        ReplaceOrCreateServiceForDaoFix()
                    )
                }
            }
        }
}
