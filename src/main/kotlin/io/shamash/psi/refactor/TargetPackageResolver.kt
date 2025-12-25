package io.shamash.psi.refactor

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector

object TargetPackageResolver {

    /**
     * If a class is a controller/service/dao/util/workflow:
     * move it to: <root> + <layer packageMarker>
     *
     * Example:
     *  root = "org.shamash", expected=CONTROLLER => "org.shamash.controller"
     */
    fun resolveTargetPackage(root: String, psiClass: PsiClass): String? {
        val expected: Layer = LayerDetector.detect(psiClass)
        val marker = expected.packageMarker ?: return null

        // marker is like ".controller." -> want ".controller"
        val suffix = marker.trim('.')

        return "$root.$suffix"
    }

    fun resolveRoot(file: PsiJavaFile): String? = RootPackageResolver.detect(file)
}
