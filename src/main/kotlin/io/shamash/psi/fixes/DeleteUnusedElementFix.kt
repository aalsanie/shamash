package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.safeDelete.SafeDeleteHandler

class DeleteUnusedElementFix(
    private val label: String = "Delete unused element"
) : LocalQuickFix {

    override fun getName(): String = label

    override fun getFamilyName(): String = "Shamash dead code fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element: PsiElement = descriptor.psiElement ?: return
        if (!element.isValid) return

        SafeDeleteHandler.invoke(project, arrayOf(element), false)
    }
}
