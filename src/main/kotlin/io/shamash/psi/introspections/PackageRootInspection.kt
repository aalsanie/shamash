package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.PackageRules
import io.shamash.psi.fixes.ConfigureRootPackageFix
import io.shamash.psi.util.ShamashMessages.msg

class PackageRootInspection : AbstractBaseJavaLocalInspectionTool() {

    @JvmField
    var rootPackage: String = ""

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val resolvedRoot =
                rootPackage.takeIf { it.isNotBlank() }
                    ?: detectRootPackage(psiClass)
                    ?: return

            if (!PackageRules.violatesRootPackage(psiClass, resolvedRoot)) return

            holder.registerProblem(
                psiClass.nameIdentifier ?: return,
                msg("Class is outside the configured root package '$resolvedRoot'"),
                ConfigureRootPackageFix()
            )
        }
    }

    private fun detectRootPackage(psiClass: PsiClass): String? {
        val file = psiClass.containingFile as? PsiJavaFile ?: return null
        return file.packageName.substringBefore('.', file.packageName)
    }
}
