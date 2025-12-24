package io.shamash.psi.util

import com.intellij.psi.*

object PsiMethodUtil {

    fun isSafeToDelete(method: PsiMethod): Boolean {
        if (method.isConstructor) return false
        if (method.hasOverride()) return false
        return true
    }

    private fun PsiMethod.hasOverride(): Boolean {
        return modifierList.findAnnotation("java.lang.Override") != null
    }
}
