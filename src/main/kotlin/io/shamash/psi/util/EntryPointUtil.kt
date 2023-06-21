package io.shamash.psi.util

import com.intellij.psi.*

object EntryPointUtil {

    fun isEntryPoint(element: PsiElement): Boolean {
        return when (element) {
            is PsiMethod -> isEntryPointMethod(element)
            is PsiClass -> false
            else -> false
        }
    }

    private fun isEntryPointMethod(method: PsiMethod): Boolean {
        return method.name == "main" ||
                method.hasAnnotation("org.junit.Test") ||
                method.hasAnnotation("org.junit.jupiter.api.Test") ||
                method.hasAnnotation("javax.ws.rs.GET") ||
                method.hasAnnotation("org.springframework.context.annotation.Bean")
    }

    private fun PsiMethod.hasAnnotation(fqn: String): Boolean {
        return modifierList.findAnnotation(fqn) != null
    }
}
