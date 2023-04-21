package io.shamash.psi.util

import com.intellij.psi.*

object PsiDependencyUtil {

    fun PsiClass.dependsOnPackage(pkg: String): Boolean {
        return this.references.any {
            val resolved = it.resolve() ?: return@any false
            val file = resolved.containingFile ?: return@any false
            val path = file.virtualFile?.path ?: return@any false
            path.contains(pkg.replace(".", "/"))
        }
    }
}
