package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier

class MakeClassFinalFix : LocalQuickFix {

    override fun getName(): String = "Make class final"

    override fun getFamilyName(): String = "Shamash utility class fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiClass = descriptor.psiElement?.parent as? PsiClass ?: return
        if (!psiClass.isValid) return

        psiClass.modifierList?.setModifierProperty(PsiModifier.FINAL, true)
    }
}
