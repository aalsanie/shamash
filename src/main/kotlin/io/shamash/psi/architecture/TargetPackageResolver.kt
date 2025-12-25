package io.shamash.psi.architecture

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade

object TargetPackageResolver {

    fun resolve(
        project: Project,
        rootPackage: String,
        layer: Layer
    ): String {

        val candidate = layer.packageMarker
            ?.let { rootPackage + it }
            ?: rootPackage

        val exists =
            JavaPsiFacade.getInstance(project)
                .findPackage(candidate) != null

        return if (exists) candidate else rootPackage
    }
}
