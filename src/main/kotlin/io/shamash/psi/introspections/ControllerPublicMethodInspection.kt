package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*

class ControllerPublicMethodInspection
    : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val file = psiClass.containingFile as? PsiJavaFile ?: return
            if (!file.packageName.contains(".controller.")) return

            val publicMethods =
                psiClass.methods.filter {
                    it.hasModifierProperty(PsiModifier.PUBLIC) &&
                            !it.isConstructor
                }

            if (publicMethods.size > 1) {
                publicMethods.drop(1).forEach {
                    holder.registerProblem(
                        it.nameIdentifier ?: return,
                        "Shamash: Controller must expose only one public endpoint"
                    )
                }
            }
        }
    }
}
