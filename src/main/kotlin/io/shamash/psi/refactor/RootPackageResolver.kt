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
package io.shamash.psi.refactor
import com.intellij.psi.PsiJavaFile

object RootPackageResolver {
    private val markers =
        listOf(
            ".controller.",
            ".service.",
            ".dao.",
            ".workflow.",
            ".util.",
        )

    fun detect(file: PsiJavaFile): String? {
        val pkg = file.packageName.trim()
        if (pkg.isBlank()) return null

        markers.firstOrNull { pkg.contains(it) }?.let { marker ->
            return pkg.substringBefore(marker).trimEnd('.')
        }

        val parts = pkg.split('.').filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0]}.${parts[1]}"
            parts.size == 1 -> parts[0]
            else -> null
        }
    }
}
