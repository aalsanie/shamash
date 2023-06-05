package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.refactoring.RefactoringFactory

class RenameClassFix(
    private val reason: String
) : LocalQuickFix {

    override fun getName(): String =
        "Rename class ($reason)"

    override fun getFamilyName(): String =
        "Shamash naming fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiClass = descriptor.psiElement?.parent as? PsiClass ?: return
        if (!psiClass.isValid) return

        val factory = RefactoringFactory.getInstance(project)
        factory.createRename(psiClass, psiClass.name ?: return, false, false)
            .run()
    }
}
