package io.shamash.asm.scan

import com.intellij.openapi.compiler.CompilerPaths
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import java.io.File

/**
 * ASM-1 (Option B): locate module output directories + dependency jars.
 */
object ClassFileLocator {

    fun collectSources(project: Project): List<ClassFileSource> {
        val sources = mutableListOf<ClassFileSource>()

        val modules = ModuleManager.getInstance(project).modules
        for (module in modules) {
            collectModuleOutputs(module, sources)
            collectModuleDependencyJars(module, sources)
        }

        // De-dup by path (same jar can appear on multiple modules).
        return sources
            .distinctBy { it.path }
            .sortedBy { it.displayName }
    }

    private fun collectModuleOutputs(
        module: com.intellij.openapi.module.Module,
        out: MutableList<ClassFileSource>
    ) {
        val mainOut = CompilerPaths.getModuleOutputPath(module, /* forTests = */ false)
        val testOut = CompilerPaths.getModuleOutputPath(module, /* forTests = */ true)

        listOfNotNull(mainOut, testOut)
            .map { File(it) }
            .filter { it.exists() && it.isDirectory }
            .forEach { dir ->
                out += ClassFileSource.Directory(
                    displayName = "${module.name}: ${dir.name}",
                    path = dir.absolutePath
                )
            }
    }

    private fun collectModuleDependencyJars(
        module: com.intellij.openapi.module.Module,
        out: MutableList<ClassFileSource>
    ) {
        val roots: Array<VirtualFile> =
            ModuleRootManager.getInstance(module)
                .orderEntries()
                .withoutSdk()
                .withoutModuleSourceEntries()
                .librariesOnly()
                .classes()
                .roots

        for (root in roots) {
            val jarFile = toLocalJarFile(root) ?: continue
            if (!jarFile.exists() || !jarFile.isFile) continue
            if (!jarFile.name.endsWith(".jar")) continue

            out += ClassFileSource.Jar(
                displayName = jarFile.name,
                path = jarFile.absolutePath
            )
        }
    }

    private fun toLocalJarFile(root: VirtualFile): File? {
        // In IntelliJ, library class roots might be a jar root (jar://...!/)
        val localJarVf = JarFileSystem.getInstance().getVirtualFileForJar(root) ?: root
        return try {
            VfsUtilCore.virtualToIoFile(localJarVf)
        } catch (_: Throwable) {
            null
        }
    }
}
