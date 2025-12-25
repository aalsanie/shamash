package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

class MakeFieldsStaticFix : LocalQuickFix {

    override fun getName(): String = "Make fields static"
    override fun getFamilyName(): String = "Shamash utility class fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        if (!element.isValid) return

        // Supports:
        // - problem registered on field identifier (rare, but supported)
        // - problem registered on class identifier (your current approach)
        val targetField = PsiTreeUtil.getParentOfType(element, PsiField::class.java, false)
        val targetClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Make fields static")
            .run<RuntimeException> {

                when {
                    targetField != null -> makeFieldStatic(targetField)
                    targetClass != null -> makeAllFieldsStatic(targetClass)
                }
            }
    }

    private fun makeAllFieldsStatic(psiClass: PsiClass) {
        psiClass.fields
            .asSequence()
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .forEach { makeFieldStatic(it) }
    }

    private fun makeFieldStatic(field: PsiField) {
        if (!field.isValid) return
        if (field.hasModifierProperty(PsiModifier.STATIC)) return
        field.modifierList?.setModifierProperty(PsiModifier.STATIC, true)
    }
}
