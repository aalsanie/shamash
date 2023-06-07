package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
object PackageRules {

    fun violatesRootPackage(
        psiClass: PsiClass,
        rootPackage: String
    ): Boolean {
        val file = psiClass.containingFile as? PsiJavaFile ?: return false
        return !file.packageName.startsWith(rootPackage)
    }
}
