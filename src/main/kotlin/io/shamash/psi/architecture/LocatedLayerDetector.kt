package io.shamash.psi.architecture

import com.intellij.psi.PsiClass

/**
 * Detects the layer ONLY from the qualified name package markers.
 * This answers: "where is this class located?"
 */
object LocatedLayerDetector {

    fun detect(psiClass: PsiClass): Layer {
        val qName = psiClass.qualifiedName ?: return Layer.OTHER

        return when {
            qName.contains(Layer.CONTROLLER.packageMarker ?: "") -> Layer.CONTROLLER
            qName.contains(Layer.SERVICE.packageMarker ?: "") -> Layer.SERVICE
            qName.contains(Layer.DAO.packageMarker ?: "") -> Layer.DAO
            qName.contains(Layer.WORKFLOW.packageMarker ?: "") -> Layer.WORKFLOW
            qName.contains(Layer.UTIL.packageMarker ?: "") -> Layer.UTIL
            else -> Layer.OTHER
        }
    }
}
