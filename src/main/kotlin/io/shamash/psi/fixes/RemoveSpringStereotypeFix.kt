package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass

class RemoveSpringStereotypeFix : LocalQuickFix {

    override fun getFamilyName(): String =
        "Remove Spring stereotype annotation"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor
    ) {
        val psiClass = descriptor.psiElement.parent as? PsiClass ?: return
        val modifierList = psiClass.modifierList ?: return

        modifierList.annotations
            .filter { it.isSpringStereotype() }
            .forEach(PsiAnnotation::delete)
    }

    private fun PsiAnnotation.isSpringStereotype(): Boolean {
        val qName = qualifiedName ?: return false
        return qName == "org.springframework.stereotype.Component" ||
                qName == "org.springframework.stereotype.Service" ||
                qName == "org.springframework.stereotype.Repository" ||
                qName == "org.springframework.stereotype.Controller" ||
                qName == "org.springframework.web.bind.annotation.RestController"
    }
}
