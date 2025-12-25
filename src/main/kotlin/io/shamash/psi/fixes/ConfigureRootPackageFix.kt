package io.shamash.psi.fixes

import com.intellij.openapi.project.Project
import io.shamash.psi.refactor.SafeMoveRefactoring

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import io.shamash.psi.refactor.TargetPackageResolver

class ConfigureRootPackageFix : LocalQuickFix {

    override fun getName(): String = "Move class to correct package"
    override fun getFamilyName(): String = "Shamash architecture fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiClass = descriptor.psiElement.parent as? PsiClass
            ?: descriptor.psiElement as? PsiClass
            ?: return

        val file = psiClass.containingFile as? PsiJavaFile ?: return

        val root = TargetPackageResolver.resolveRoot(file) ?: return
        val targetPkg = TargetPackageResolver.resolveTargetPackage(root, psiClass) ?: return

        // Already correct package? do nothing.
        if (file.packageName == targetPkg) return

        SafeMoveRefactoring.moveToPackage(project, file, targetPkg)
    }
}
