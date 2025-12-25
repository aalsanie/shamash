package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.refactor.SafeMoveRefactoring
import io.shamash.psi.refactor.TargetPackageResolver

class ConfigureRootPackageFix : LocalQuickFix {

    override fun getName(): String = "Move class to correct package"
    override fun getFamilyName(): String = "Shamash architecture fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiClass =
            PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiClass::class.java, false)
                ?: return

        val file = psiClass.containingFile as? PsiJavaFile ?: return

        val root = TargetPackageResolver.resolveRoot(file) ?: return
        val targetPkg = TargetPackageResolver.resolveTargetPackage(root, psiClass) ?: return

        if (file.packageName == targetPkg) return

        // Creates the package if missing (asks user), then runs a safe refactoring move.
        SafeMoveRefactoring.moveToPackage(
            project = project,
            file = file,
            targetPackageFqn = targetPkg,
            askUserToCreate = true
        )
    }
}
