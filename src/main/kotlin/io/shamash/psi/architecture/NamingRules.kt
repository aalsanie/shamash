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

object NamingRules {
    private val bannedSuffixes =
        listOf(
            "Manager",
            "Helper",
            "Impl",
        )

    fun bannedSuffix(psiClass: PsiClass): String? {
        val name = psiClass.name ?: return null
        return bannedSuffixes.firstOrNull { name.endsWith(it) }
    }

    fun isAbbreviated(psiClass: PsiClass): Boolean {
        val name = psiClass.name ?: return false
        return name.length <= 4 && name.any { it.isUpperCase() }
    }
}
