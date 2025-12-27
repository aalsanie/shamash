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
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier

object ControllerRules {
    /**
     * Returns public methods that exceed the "one public endpoint" rule.
     *
     * Rule:
     * - Applies only to detected controller classes.
     * - Considers non-constructor public methods as "endpoints" (strict by design).
     * - Returns all public methods after the first one, so the inspection can report each.
     */
    fun excessPublicMethods(psiClass: PsiClass): List<PsiMethod> {
        if (!psiClass.isValid) return emptyList()
        if (LayerDetector.detect(psiClass) != Layer.CONTROLLER) return emptyList()

        val publicMethods =
            psiClass.methods
                .asSequence()
                .filter { !it.isConstructor }
                .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
                .toList()

        return if (publicMethods.size <= 1) emptyList() else publicMethods.drop(1)
    }
}
