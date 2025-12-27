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
