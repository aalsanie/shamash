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
    fun resolveRoot(file: PsiJavaFile): String? {
        val pkg = file.packageName.takeIf { it.isNotBlank() } ?: return null
        // If you already implemented better root detection, keep yours.
        return pkg.substringBefore(".controller.")
            .substringBefore(".service.")
            .substringBefore(".dao.")
            .substringBefore(".util.")
            .substringBefore(".workflow.")
            .ifBlank { pkg.substringBefore('.', pkg) }
    }

    /**
     * Resolves the conventional package for a given architectural layer under the detected root.
     *
     * Examples:
     *  root = "com.foo", layer=SERVICE -> "com.foo.service"
     *  root = "org.acme.app", layer=DAO -> "org.acme.app.dao"
     *  root = "io.shamash", layer=OTHER/CLI -> "io.shamash"
     */
    fun resolveLayerPackage(root: String, layer: Layer): String {
        val marker = layer.packageMarker?.trim('.') ?: return root
        // marker comes like ".service." -> "service"
        return "$root.$marker"
    }

    /**
     * Existing method you already used in ConfigureRootPackageFix.
     * Keep your current implementation if you have one.
     */
    fun resolveTargetPackage(root: String, psiClass: com.intellij.psi.PsiClass): String? {
        // If you already implemented this, DO NOT replace it.
        // This placeholder is only here to show compatibility.
        val layer = io.shamash.psi.architecture.LayerDetector.detect(psiClass)
        return when (layer) {
            Layer.CONTROLLER -> "$root.controller"
            Layer.SERVICE -> "$root.service"
            Layer.DAO -> "$root.dao"
            Layer.UTIL -> "$root.util"
            Layer.WORKFLOW -> "$root.workflow"
            Layer.CLI, Layer.OTHER -> root
        }
    }
}
