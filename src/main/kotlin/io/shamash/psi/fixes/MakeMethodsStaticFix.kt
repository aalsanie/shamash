package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

class MakeMethodsStaticFix : LocalQuickFix {

    override fun getName(): String = "Make method static"

    override fun getFamilyName(): String = "Shamash utility class fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = descriptor.psiElement?.parent as? PsiMethod ?: return
        if (!method.isValid || method.isConstructor) return

        method.modifierList.setModifierProperty(PsiModifier.STATIC, true)
    }
}
