package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import io.shamash.psi.util.*

object LayerRules {

    private val limits = mapOf(
        Layer.CONTROLLER to 5,
        Layer.SERVICE to 5,
        Layer.DAO to 5,
        Layer.UTIL to 10,
        Layer.WORKFLOW to 10,
        Layer.CLI to 10
    )

    fun maxMethods(layer: Layer): Int? = limits[layer]
    /**
     * Architecture rules for Shamash.
     * If it breaks here, it's a design violation.
     */

    fun controllerMustNotDependOnDao(clazz: PsiClass): Boolean {
        if (!clazz.isController()) return false
        return DependencyQueries.dependsOn(clazz) { it.isDao() }
    }

    fun serviceMustNotDependOnController(clazz: PsiClass): Boolean {
        if (!clazz.isService()) return false
        return DependencyQueries.dependsOn(clazz) { it.isController() }
    }

    fun controllerMustHaveSinglePublicEndpoint(clazz: PsiClass): Boolean {
        if (!clazz.isController()) return false
        return clazz.publicMethodCount() > 1
    }

    fun noPrivateMethodsOutsideUtil(clazz: PsiClass): Boolean {
        if (clazz.isUtilityClass()) return false
        return clazz.hasPrivateMethods()
    }
}
