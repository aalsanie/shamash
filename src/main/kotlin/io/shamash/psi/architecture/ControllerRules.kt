package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

object ControllerRules {

    /**
     * Returns public methods that exceed the "one public endpoint" rule.
     *
     * Rule:
     * - Applies only to detected controller classes.
     * - Considers non-constructor public methods as "endpoints" (strict by design).
     * - Returns all public methods after the first one, so the inspection can report each.
     */
    fun excessPublicMethods(psiClass: PsiClass): List<PsiMethod> {
        if (!psiClass.isValid) return emptyList()
        if (LayerDetector.detect(psiClass) != Layer.CONTROLLER) return emptyList()

        val publicMethods =
            psiClass.methods
                .asSequence()
                .filter { !it.isConstructor }
                .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
                .toList()

        return if (publicMethods.size <= 1) emptyList() else publicMethods.drop(1)
    }
}
