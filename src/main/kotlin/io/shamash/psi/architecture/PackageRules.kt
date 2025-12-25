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

    fun isOutsideRootPackage(currentPackage: String, rootPackage: String): Boolean {
        if (currentPackage.isBlank()) return true
        return currentPackage != rootPackage && !currentPackage.startsWith("$rootPackage.")
    }

    fun isWrongLayerPackage(currentPackage: String, expectedLayerRoot: String): Boolean {
        // ok if it's exactly expected package or inside it
        return currentPackage != expectedLayerRoot && !currentPackage.startsWith("$expectedLayerRoot.")
    }

    /**
     * Returns mismatch if a class "looks like" a layer (Controller/Service/DAO/etc)
     * but is not located under that layer's package marker.
     */
    fun layerPlacementMismatch(psiClass: PsiClass): LayerMismatch? {
        val expected = LayerDetector.detect(psiClass)              // what it is
        val located = LocatedLayerDetector.detect(psiClass)        // where it lives

        if (expected == Layer.OTHER) return null
        if (expected == located) return null

        // If expected has no package marker, we can't enforce package placement.
        val expectedMarker = expected.packageMarker ?: return null

        return LayerMismatch(expected, located, expectedMarker)
    }

    data class LayerMismatch(
        val expected: Layer,
        val located: Layer,
        val expectedMarker: String
    )

}
