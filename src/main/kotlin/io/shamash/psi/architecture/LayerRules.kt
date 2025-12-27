/*
 * Copyright © 2025-2026 | Shamash is a refactoring tool that enforces clean architecture.
 *
 * Author: @aalsanie
 *
 * Plugin: https://plugins.jetbrains.com/plugin/29504-shamash
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.shamash.psi.architecture

import com.intellij.psi.PsiClass
import io.shamash.psi.util.hasPrivateMethods
import io.shamash.psi.util.isController
import io.shamash.psi.util.isDao
import io.shamash.psi.util.isService
import io.shamash.psi.util.isUtilityClass
import io.shamash.psi.util.publicMethodCount

object LayerRules {
    private val limits =
        mapOf(
            Layer.CONTROLLER to 5,
            Layer.SERVICE to 5,
            Layer.DAO to 5,
            Layer.UTIL to 10,
            Layer.WORKFLOW to 10,
            Layer.CLI to 10,
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
        layer !in
            setOf(
                Layer.CONTROLLER,
                Layer.SERVICE,
                Layer.DAO,
            )

    fun exceedsMethodLimit(
        psiClass: PsiClass,
        layer: Layer,
    ): Int? {
        val max = maxMethods(layer) ?: return null
        val count = psiClass.methods.count { !it.isConstructor }
        return if (count > max) count else null
    }

    fun isDependencyAllowed(
        from: Layer,
        to: Layer,
    ): Boolean =
        when (from) {
            Layer.SERVICE -> to != Layer.CONTROLLER
            else -> true
        }
}
