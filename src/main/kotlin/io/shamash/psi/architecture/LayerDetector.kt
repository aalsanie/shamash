package io.shamash.psi.architecture

import com.intellij.psi.*

enum class Layer(
    val packageMarker: String?
) {
    CONTROLLER(".controller."),
    SERVICE(".service."),
    DAO(".dao."),
    UTIL(".util."),
    WORKFLOW(".workflow."),
    CLI(null),
    OTHER(null)//null means not package-detectable
}

object LayerDetector {

    fun detect(psiClass: PsiClass): Layer {
        val qName = psiClass.qualifiedName ?: return Layer.OTHER

        return when {
            isController(psiClass, qName) -> Layer.CONTROLLER
            isService(psiClass, qName) -> Layer.SERVICE
            isDao(psiClass, qName) -> Layer.DAO
            qName.contains(Layer.WORKFLOW.packageMarker!!) -> Layer.WORKFLOW
            isUtil(psiClass, qName) -> Layer.UTIL
            hasMainMethod(psiClass) -> Layer.CLI
            else -> Layer.OTHER
        }
    }

    private fun isController(psiClass: PsiClass, qName: String): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Controller") ||
                psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController") ||
                qName.contains(Layer.CONTROLLER.packageMarker!!)

    private fun isService(psiClass: PsiClass, qName: String): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Service") ||
                qName.contains(Layer.SERVICE.packageMarker!!)

    private fun isDao(psiClass: PsiClass, qName: String): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Repository") ||
                qName.contains(Layer.DAO.packageMarker!!)

    private fun isUtil(psiClass: PsiClass, qName: String): Boolean =
        qName.contains(Layer.UTIL.packageMarker!!) ||
                psiClass.name?.endsWith("Util") == true

    private fun hasMainMethod(psiClass: PsiClass): Boolean =
        psiClass.methods.any {
            it.name == "main" &&
                    it.hasModifierProperty(PsiModifier.STATIC)
        }
}
