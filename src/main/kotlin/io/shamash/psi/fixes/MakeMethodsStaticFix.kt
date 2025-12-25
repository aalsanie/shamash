package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

class MakeMethodsStaticFix : LocalQuickFix {

    override fun getName(): String = "Make methods static"
    override fun getFamilyName(): String = "Shamash utility class fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement ?: return
        if (!element.isValid) return

        // Supports:
        // 1) Inspection registered on a method identifier -> fix that method
        // 2) Inspection registered on a class identifier -> fix all methods in class
        val targetMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
        val targetClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

        WriteCommandAction.writeCommandAction(project)
            .withName("Shamash: Make methods static")
            .run<RuntimeException> {

                when {
                    targetMethod != null -> makeMethodStaticIfSafe(targetMethod)
                    targetClass != null -> makeAllMethodsStaticIfSafe(targetClass)
                }
            }
    }

    private fun makeAllMethodsStaticIfSafe(psiClass: PsiClass) {
        psiClass.methods
            .asSequence()
            .filterNot { it.isConstructor }
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .filterNot { it.hasModifierProperty(PsiModifier.ABSTRACT) }
            .forEach { makeMethodStaticIfSafe(it) }
    }

    private fun makeMethodStaticIfSafe(method: PsiMethod) {
        if (!method.isValid) return
        if (method.isConstructor) return
        if (method.hasModifierProperty(PsiModifier.STATIC)) return
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) return

        // Conservative: only make static if it doesn't depend on instance state.
        if (!isSafeToMakeStatic(method)) return

        method.modifierList.setModifierProperty(PsiModifier.STATIC, true)
    }

    /**
     * Returns false if the method references:
     * - `this` / `super`
     * - non-static fields
     * - non-static methods (including implicit calls like foo() where foo is instance)
     */
    private fun isSafeToMakeStatic(method: PsiMethod): Boolean {
        var safe = true

        method.accept(object : JavaRecursiveElementWalkingVisitor() {

            override fun visitThisExpression(expression: PsiThisExpression) {
                safe = false
                stopWalking()
            }

            override fun visitSuperExpression(expression: PsiSuperExpression) {
                safe = false
                stopWalking()
            }

            override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                if (!safe) return
                val resolved = expression.resolve()

                when (resolved) {
                    is PsiField -> {
                        if (!resolved.hasModifierProperty(PsiModifier.STATIC)) {
                            safe = false
                            stopWalking()
                        }
                    }

                    is PsiMethod -> {
                        // Ignore self-reference, and ignore constructors (not relevant)
                        if (resolved == method) return
                        if (resolved.isConstructor) return

                        if (!resolved.hasModifierProperty(PsiModifier.STATIC)) {
                            safe = false
                            stopWalking()
                        }
                    }
                }

                super.visitReferenceExpression(expression)
            }
        })

        return safe
    }
}
