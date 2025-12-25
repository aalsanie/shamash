package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.util.packageName
import io.shamash.psi.util.referencedClasses

object DependencyQueries {

    /**
     * Returns true if [psiClass] references any class whose package matches [forbiddenPackage].
     *
     * Supports BOTH:
     *  - Prefix form: "com.foo.dao" (package startsWith prefix)
     *  - Marker form: ".dao." / ".controller." (segment match anywhere in package)
     */
    fun dependsOnPackage(psiClass: PsiClass, forbiddenPackage: String): Boolean {
        val raw = forbiddenPackage.trim()
        if (raw.isBlank()) return false

        val isMarker = raw.startsWith(".") || raw.endsWith(".")
        val needle = raw.trim('.')

        return psiClass.referencedClasses().any { ref ->
            val pkg = ref.packageName()

            if (!isMarker) {
                pkg.startsWith(raw)
            } else {
                // Match segment anywhere (".dao." should match "com.foo.dao" or "com.foo.dao.impl")
                val segments = pkg.split('.')
                segments.contains(needle)
            }
        }
    }

    fun dependsOn(clazz: PsiClass, predicate: (PsiClass) -> Boolean): Boolean {
        return clazz.referencedClasses().any(predicate)
    }

    fun violatesLayerDependency(
        psiClass: PsiClass,
        from: Layer,
        to: Layer
    ): Boolean {
        if (LayerDetector.detect(psiClass) != from) return false
        return layerDependencySources(psiClass, to).isNotEmpty()
    }

    /**
     * Returns PSI elements inside [psiClass] that introduce a dependency on [toLayer].
     *
     * We keep this intentionally structural:
     * - import statements
     * - type references (field types, return types, parameter types, extends/implements)
     *
     * These are good anchors for precise highlighting + quick-fixes.
     */
    fun layerDependencySources(
        psiClass: PsiClass,
        toLayer: Layer
    ): List<PsiElement> {
        val refs = PsiTreeUtil.collectElementsOfType(psiClass, PsiJavaCodeReferenceElement::class.java)
        if (refs.isEmpty()) return emptyList()

        return refs
            .filter { ref ->
                val resolved = ref.resolve() as? PsiClass ?: return@filter false
                LayerDetector.detect(resolved) == toLayer
            }
            .map { ref ->
                // If this is an import reference, report the whole import statement (cleaner UX).
                PsiTreeUtil.getParentOfType(ref, PsiImportStatementBase::class.java, false) ?: ref
            }
            .distinct()
    }
}
