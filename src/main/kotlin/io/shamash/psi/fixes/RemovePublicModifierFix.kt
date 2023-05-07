package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

class RemovePublicModifierFix(
    private val label: String = "Remove public modifier"
) : LocalQuickFix {

    override fun getFamilyName(): String = label

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = descriptor.psiElement.parent as? PsiMethod ?: return
        method.modifierList.setModifierProperty(PsiModifier.PUBLIC, false)
    }
}
