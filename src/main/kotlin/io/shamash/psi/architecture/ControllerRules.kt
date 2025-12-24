package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

object ControllerRules {

    /**
     * Returns public methods that exceed the single allowed controller endpoint.
     *
     * The first public method is considered the primary endpoint.
     * All additional public methods are considered violations.
     */
    fun excessPublicMethods(psiClass: PsiClass): List<PsiMethod> {
        if (!psiClass.isController()) return emptyList()

        val publicMethods =
            psiClass.methods.filter {
                it.hasModifierProperty(PsiModifier.PUBLIC) &&
                        !it.isConstructor
            }

        return if (publicMethods.size <= 1)
            emptyList()
        else
            publicMethods.drop(1)
    }

    private fun PsiClass.isController(): Boolean {
        val file = containingFile as? PsiJavaFile ?: return false
        return file.packageName.contains(".controller.")
    }
}
