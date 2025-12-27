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
package io.shamash.psi.util

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod

object EntryPointUtil {
    fun isEntryPoint(element: PsiElement): Boolean =
        when (element) {
            is PsiMethod -> isEntryPointMethod(element)
            is PsiClass -> false
            else -> false
        }

    private fun isEntryPointMethod(method: PsiMethod): Boolean =
        method.name == "main" ||
            method.hasAnnotation("org.junit.Test") ||
            method.hasAnnotation("org.junit.jupiter.api.Test") ||
            method.hasAnnotation("javax.ws.rs.GET") ||
            method.hasAnnotation("org.springframework.context.annotation.Bean")

    private fun PsiMethod.hasAnnotation(fqn: String): Boolean = modifierList.findAnnotation(fqn) != null
}
