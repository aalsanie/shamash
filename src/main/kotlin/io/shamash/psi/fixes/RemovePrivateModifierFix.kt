package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

class RemovePrivateModifierFix : LocalQuickFix {

    override fun getName(): String = "Remove private modifier"

    override fun getFamilyName(): String = "Shamash visibility fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = descriptor.psiElement?.parent as? PsiMethod ?: return
        if (!method.isValid) return

        method.modifierList.setModifierProperty(PsiModifier.PRIVATE, false)
    }
}
