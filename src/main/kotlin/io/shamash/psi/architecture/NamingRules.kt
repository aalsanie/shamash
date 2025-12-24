package io.shamash.psi.architecture

import com.intellij.psi.PsiClass

object NamingRules {

    private val bannedSuffixes = listOf(
        "Manager",
        "Helper",
        "Impl"
    )

    fun bannedSuffix(psiClass: PsiClass): String? {
        val name = psiClass.name ?: return null
        return bannedSuffixes.firstOrNull { name.endsWith(it) }
    }

    fun isAbbreviated(psiClass: PsiClass): Boolean {
        val name = psiClass.name ?: return false
        return name.length <= 4 && name.any { it.isUpperCase() }
    }
}
