package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.util.PsiDependencyUtil.dependsOnPackage

class ControllerDaoDependencyInspection
    : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val file = psiClass.containingFile as? PsiJavaFile ?: return
            if (!file.packageName.contains(".controller.")) return

            if (psiClass.dependsOnPackage(".dao.")) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    "Shamash: Controller must not depend on DAO"
                )
            }
        }
    }
}
