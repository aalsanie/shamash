package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteHandler

class DeleteUnusedElementFix(
    private val label: String = "Delete unused element"
) : LocalQuickFix {

    override fun getName(): String = label
    override fun getFamilyName(): String = "Shamash dead code fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val leaf: PsiElement = descriptor.psiElement ?: return
        if (!leaf.isValid()) return

        val target: PsiElement =
            PsiTreeUtil.getParentOfType(
                leaf,
                PsiMethod::class.java,
                PsiField::class.java,
                PsiClass::class.java
            ) ?: return

        if (!target.isValid()) return

        // Safe Delete performs final usage/conflict verification and supports preview/undo.
        SafeDeleteHandler.invoke(project, arrayOf(target), /* showDialog = */ true)
    }
}
