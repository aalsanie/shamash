package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiModifier

class MakePrivateConstructorFix : LocalQuickFix {

    override fun getName(): String = "Make constructor private"
    override fun getFamilyName(): String = "Shamash utility fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val psiClass =
            (descriptor.psiElement.parent as? PsiClass)
                ?: (descriptor.psiElement as? PsiClass)
                ?: return

        val className = psiClass.name ?: return

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Make utility constructor private")
            .run<RuntimeException> {

                val constructors = psiClass.constructors

                if (constructors.isEmpty()) {
                    // No explicit constructors -> add a private no-arg constructor.
                    val factory = PsiElementFactory.getInstance(project)
                    val ctor = factory.createConstructor(className)
                    ctor.modifierList?.setModifierProperty(PsiModifier.PRIVATE, true)

                    // Insert near top (after fields if you prefer; this is fine and stable)
                    psiClass.add(ctor)
                    return@run
                }

                // Make all existing constructors private.
                constructors.forEach { ctor ->
                    ctor.modifierList?.let { mods ->
                        // Clear other visibility flags then set private.
                        mods.setModifierProperty(PsiModifier.PUBLIC, false)
                        mods.setModifierProperty(PsiModifier.PROTECTED, false)
                        mods.setModifierProperty(PsiModifier.PRIVATE, true)
                    }
                }
            }
    }
}
