package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.NamingRules
import io.shamash.psi.fixes.RenameClassFix
import io.shamash.psi.util.ShamashMessages.msg

class NamingConventionInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {

            NamingRules.bannedSuffix(psiClass)?.let {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("'$it' suffix is banned. Use explicit domain naming."),
                    RenameClassFix("Rename class")
                )
            }

            if (NamingRules.isAbbreviated(psiClass)) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("Abbreviated class names are not allowed"),
                    RenameClassFix("Rename class")
                )
            }
        }
    }
}
