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
