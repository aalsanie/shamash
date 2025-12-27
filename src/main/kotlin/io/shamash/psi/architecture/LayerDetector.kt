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
import com.intellij.psi.PsiModifier

enum class Layer(
    val packageMarker: String?,
) {
    CONTROLLER(".controller."),
    SERVICE(".service."),
    DAO(".dao."),
    UTIL(".util."),
    WORKFLOW(".workflow."),
    CLI(null),
    OTHER(null),
}

object LayerDetector {
    fun detect(psiClass: PsiClass): Layer {
        val qName = psiClass.qualifiedName ?: return Layer.OTHER
        val name = psiClass.name.orEmpty()

        return when {
            isController(psiClass, qName, name) -> Layer.CONTROLLER
            isService(psiClass, qName, name) -> Layer.SERVICE
            isDao(psiClass, qName, name) -> Layer.DAO
            qName.contains(Layer.WORKFLOW.packageMarker!!) -> Layer.WORKFLOW
            isUtil(psiClass, qName, name) -> Layer.UTIL
            hasMainMethod(psiClass) -> Layer.CLI
            else -> Layer.OTHER
        }
    }

    private fun isController(
        psiClass: PsiClass,
        qName: String,
        name: String,
    ): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Controller") ||
            psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController") ||
            qName.contains(Layer.CONTROLLER.packageMarker!!) ||
            name.endsWith("Controller")

    private fun isService(
        psiClass: PsiClass,
        qName: String,
        name: String,
    ): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Service") ||
            qName.contains(Layer.SERVICE.packageMarker!!) ||
            name.endsWith("Service")

    private fun isDao(
        psiClass: PsiClass,
        qName: String,
        name: String,
    ): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Repository") ||
            qName.contains(Layer.DAO.packageMarker!!) ||
            name.endsWith("Dao") ||
            name.endsWith("Repository")

    private fun isUtil(
        psiClass: PsiClass,
        qName: String,
        name: String,
    ): Boolean =
        qName.contains(Layer.UTIL.packageMarker!!) ||
            name.endsWith("Util")

    private fun hasMainMethod(psiClass: PsiClass): Boolean =
        psiClass.methods.any {
            it.name == "main" && it.hasModifierProperty(PsiModifier.STATIC)
        }

    fun hasControllerStereotype(psiClass: PsiClass): Boolean =
        psiClass.hasAnnotation("org.springframework.stereotype.Controller") ||
            psiClass.hasAnnotation("org.springframework.web.bind.annotation.RestController")
}
