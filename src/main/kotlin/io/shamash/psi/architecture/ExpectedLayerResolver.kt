package io.shamash.psi.architecture

import com.intellij.psi.PsiClass

object ExpectedLayerResolver {

    /**
     * Priority:
     * 1) Strong framework stereotypes (Controller/Service/Repository)
     * 2) Strong naming signals (FooController/FooService/FooDao/FooRepository/FooUtil/FooWorkflow)
     */
    fun resolveExpectedLayer(psiClass: PsiClass): Layer? {
        val qName = psiClass.qualifiedName ?: ""
        val name = psiClass.name ?: return null

        // 1) Spring stereotypes
        if (LayerDetector.hasControllerStereotype(psiClass)) return Layer.CONTROLLER
        if (psiClass.hasAnnotation("org.springframework.stereotype.Service")) return Layer.SERVICE
        if (psiClass.hasAnnotation("org.springframework.stereotype.Repository")) return Layer.DAO

        // 2) Naming signals (strict, deterministic)
        return when {
            name.endsWith("Controller") -> Layer.CONTROLLER
            name.endsWith("Service") -> Layer.SERVICE
            name.endsWith("Repository") || name.endsWith("Dao") -> Layer.DAO
            name.endsWith("Workflow") -> Layer.WORKFLOW
            name.endsWith("Util") -> Layer.UTIL
            // Optional: allow package-based hints if name is neutral
            qName.contains(Layer.WORKFLOW.packageMarker ?: "") -> Layer.WORKFLOW
            qName.contains(Layer.UTIL.packageMarker ?: "") -> Layer.UTIL
            else -> null
        }
    }
}
