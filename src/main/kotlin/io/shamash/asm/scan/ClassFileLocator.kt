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
package io.shamash.asm.scan

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

object ClassFileLocator {
    fun collectSources(project: Project): List<ClassFileSource> {
        val sources = mutableListOf<ClassFileSource>()

        val modules = ModuleManager.getInstance(project).modules
        for (module in modules) {
            collectModuleOutputs(module, sources)
            collectModuleDependencyJars(module, sources)
        }

        return sources
            .distinctBy { it.path }
            .sortedBy { it.displayName }
    }

    private fun collectModuleOutputs(
        module: com.intellij.openapi.module.Module,
        out: MutableList<ClassFileSource>,
    ) {
        val ext = CompilerModuleExtension.getInstance(module) ?: return

        val mainOutVf = ext.compilerOutputPath
        val testOutVf = ext.compilerOutputPathForTests

        listOfNotNull(mainOutVf, testOutVf)
            .map { File(it.path) }
            .filter { it.exists() && it.isDirectory }
            .forEach { dir ->
                out +=
                    ClassFileSource.Directory(
                        displayName = "${module.name}: ${dir.name}",
                        path = dir.absolutePath,
                    )
            }
    }

    private fun collectModuleDependencyJars(
        module: com.intellij.openapi.module.Module,
        out: MutableList<ClassFileSource>,
    ) {
        val roots: Array<VirtualFile> =
            ModuleRootManager
                .getInstance(module)
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

            out +=
                ClassFileSource.Jar(
                    displayName = jarFile.name,
                    path = jarFile.absolutePath,
                )
        }
    }

    private fun toLocalJarFile(root: VirtualFile): File? {
        val localJarVf = JarFileSystem.getInstance().getVirtualFileForJar(root) ?: root
        return try {
            VfsUtilCore.virtualToIoFile(localJarVf)
        } catch (_: Throwable) {
            null
        }
    }
}
