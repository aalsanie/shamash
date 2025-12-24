package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import io.shamash.psi.util.PsiDependencyUtil.dependsOnPackage
import io.shamash.psi.util.packageName
import io.shamash.psi.util.referencedClasses

/**
 * Queries about class dependencies.
 * Still no policy – just answers.
 */

object DependencyQueries {

    fun dependsOnPackage(clazz: PsiClass, forbiddenPackage: String): Boolean {
        return clazz.referencedClasses()
            .any { it.packageName().startsWith(forbiddenPackage) }
    }

    fun dependsOn(clazz: PsiClass, predicate: (PsiClass) -> Boolean): Boolean {
        return clazz.referencedClasses().any(predicate)
    }

    fun violatesLayerDependency(
        psiClass: PsiClass,
        from: Layer,
        to: Layer
    ): Boolean {
        val marker = to.packageMarker ?: return false
        return psiClass.dependsOnPackage(marker)
    }
}
