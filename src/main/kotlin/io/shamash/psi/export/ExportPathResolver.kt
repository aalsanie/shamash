package io.shamash.psi.export

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves and prepares the canonical export output directory for Shamash reports.
 *
 * Contract:
 * - Reports are always written under: <projectRoot>/shamash
 * - The directory is created if it does not already exist.
 */
object ExportPathResolver {

    private const val OUTPUT_DIR_NAME: String = "shamash"

    /**
     * Returns the output directory `<projectRoot>/shamash` and ensures it exists.
     *
     * If [projectRoot] points to a file, its parent directory is treated as the project root.
     *
     * @throws IllegalArgumentException if the resolved project root is null or empty.
     * @throws IllegalStateException if the output directory cannot be created.
     */
    fun resolveAndEnsureOutputDir(projectRoot: Path): Path {
        val normalizedRoot = normalizeProjectRoot(projectRoot)
        val outputDir = normalizedRoot.resolve(OUTPUT_DIR_NAME).normalize()

        try {
            Files.createDirectories(outputDir)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to create Shamash output directory: $outputDir",
                e
            )
        }

        return outputDir
    }

    private fun normalizeProjectRoot(projectRoot: Path): Path {
        val normalized = projectRoot.toAbsolutePath().normalize()
        val root = if (Files.isRegularFile(normalized)) {
            normalized.parent
        } else {
            normalized
        }

        requireNotNull(root) { "Project root path resolved to null for input: $projectRoot" }
        return root
    }
}
