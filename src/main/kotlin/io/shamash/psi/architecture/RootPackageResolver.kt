package io.shamash.psi.architecture

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import io.shamash.psi.settings.ShamashSettings

object RootPackageResolver {

    fun resolve(project: Project): String? {

        ShamashSettings.getInstance()
            .state
            .rootPackage
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        val packages = mutableListOf<String>()

        PsiManager.getInstance(project)
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
            } else break
        }

        return prefix.joinToString(".")
    }
}
