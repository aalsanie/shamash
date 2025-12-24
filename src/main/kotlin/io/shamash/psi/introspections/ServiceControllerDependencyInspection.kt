package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.util.PsiDependencyUtil.dependsOnPackage

class ServiceControllerDependencyInspection
    : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val file = psiClass.containingFile as? PsiJavaFile ?: return
            if (!file.packageName.contains(".service.")) return

            if (psiClass.dependsOnPackage(".controller.")) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    "Shamash: Service must not depend on Controller"
                )
            }
        }
    }
}
