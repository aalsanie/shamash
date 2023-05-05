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

    fun allowsPrivateMethods(layer: Layer): Boolean =
        layer !in setOf(
            Layer.CONTROLLER,
            Layer.SERVICE,
            Layer.DAO
        )

    fun exceedsMethodLimit(psiClass: PsiClass, layer: Layer): Int? {
        val max = maxMethods(layer) ?: return null
        val count = psiClass.methods.count { !it.isConstructor }
        return if (count > max) count else null
    }

    fun isDependencyAllowed(from: Layer, to: Layer): Boolean =
        when (from) {
            Layer.SERVICE -> to != Layer.CONTROLLER
            else -> true
        }
}
