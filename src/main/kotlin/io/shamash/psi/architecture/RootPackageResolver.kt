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

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import io.shamash.psi.settings.ShamashSettings

object RootPackageResolver {
    fun resolve(project: Project): String? {
        ShamashSettings
            .getInstance()
            .state
            .rootPackage
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        val packages = mutableListOf<String>()

        PsiManager
            .getInstance(project)
            .findViewProvider(project.baseDir)
            ?.allFiles
            ?.forEach { file ->
                if (file is PsiJavaFile) {
                    packages += file.packageName
                }
            }

        if (packages.isEmpty()) return null

        return longestCommonPrefix(packages)
            .takeIf { it.contains('.') }
    }

    private fun longestCommonPrefix(values: List<String>): String {
        if (values.isEmpty()) return ""

        val split = values.map { it.split('.') }
        val minSize = split.minOf { it.size }

        val prefix = mutableListOf<String>()
        for (i in 0 until minSize) {
            val part = split[0][i]
            if (split.all { it[i] == part }) {
                prefix += part
            } else {
                break
            }
        }

        return prefix.joinToString(".")
    }
}
