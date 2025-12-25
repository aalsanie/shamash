package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

class RemovePublicModifierFix : LocalQuickFix {

    override fun getName(): String = "Remove public modifier"

    override fun getFamilyName(): String = "Shamash controller fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = descriptor.psiElement?.parent as? PsiMethod ?: return
        if (!method.isValid || method.isConstructor) return

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Remove public modifier")
            .run<RuntimeException> {
                method.modifierList.setModifierProperty(PsiModifier.PUBLIC, false)
            }
    }
}
