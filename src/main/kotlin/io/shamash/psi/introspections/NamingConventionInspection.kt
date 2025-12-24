package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*

class NamingConventionInspection : AbstractBaseJavaLocalInspectionTool() {

    private val bannedSuffixes = listOf("Manager", "Helper", "Impl")

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {

        override fun visitClass(psiClass: PsiClass) {
            val name = psiClass.name ?: return

            bannedSuffixes
                .filter { name.endsWith(it) }
                .forEach {
                    holder.registerProblem(
                        psiClass.nameIdentifier ?: return,
                        "Shamash: '$it' suffix is banned. Use explicit domain naming."
                    )
                }

            // Abbreviation detection (basic but strict)
            if (name.length <= 4 && name.any { it.isUpperCase() }) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    "Shamash: Abbreviated class names are not allowed"
                )
            }
        }
    }
}
