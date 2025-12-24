package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*

class PackageRootInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val file = psiClass.containingFile as? PsiJavaFile ?: return
            val pkg = file.packageName

            if (!pkg.startsWith("io.shamash")) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    "Shamash: All code must live under 'io.shamash'"
                )
            }
        }
    }
}
