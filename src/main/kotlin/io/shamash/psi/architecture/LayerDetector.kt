package io.shamash.psi.architecture

import com.intellij.psi.*

enum class Layer {
    CONTROLLER, SERVICE, DAO, UTIL, WORKFLOW, CLI, OTHER
}

object LayerDetector {

    fun detect(psiClass: PsiClass): Layer {
        val qName = psiClass.qualifiedName ?: return Layer.OTHER

        return when {
            psiClass.hasAnnotation("org.springframework.stereotype.Controller") ||
                    psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController") ||
                    qName.contains(".controller.") -> Layer.CONTROLLER

            psiClass.hasAnnotation("org.springframework.stereotype.Service") ||
                    qName.contains(".service.") -> Layer.SERVICE

            psiClass.hasAnnotation("org.springframework.stereotype.Repository") ||
                    qName.contains(".dao.") -> Layer.DAO

            qName.contains(".workflow.") -> Layer.WORKFLOW
            qName.contains(".util.") || psiClass.name?.endsWith("Util") == true -> Layer.UTIL
            hasMainMethod(psiClass) -> Layer.CLI
            else -> Layer.OTHER
        }
    }

    private fun hasMainMethod(psiClass: PsiClass): Boolean {
        return psiClass.methods.any {
            it.name == "main" && it.hasModifierProperty(PsiModifier.STATIC)
        }
    }
}
