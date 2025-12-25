package io.shamash.psi.refactor
import com.intellij.psi.PsiJavaFile

object RootPackageResolver {

    private val markers = listOf(
        ".controller.",
        ".service.",
        ".dao.",
        ".workflow.",
        ".util."
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
