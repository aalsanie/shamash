package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project

class DeleteElementFix(private val label: String) : LocalQuickFix {

    override fun getName() = label
    override fun getFamilyName() = "Shamash cleanup"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        descriptor.psiElement.parent.delete()
    }
}
