package io.shamash.psi.util

import com.intellij.psi.*

object PsiUtil {

    fun isFinalWithPrivateConstructor(psiClass: PsiClass): Boolean {
        if (!psiClass.hasModifierProperty(PsiModifier.FINAL)) return false

        val constructors = psiClass.constructors
        if (constructors.isEmpty()) return false

        return constructors.all {
            it.hasModifierProperty(PsiModifier.PRIVATE)
        }
    }

    fun hasSpringStereotype(psiClass: PsiClass): Boolean {
        val forbiddenAnnotations = listOf(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration"
        )

        return forbiddenAnnotations.any { psiClass.hasAnnotation(it) }
    }
}
