/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.source

import com.intellij.facet.Facet
import com.intellij.facet.FacetManager
import com.intellij.facet.FacetManagerListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaSourceRootProperties
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.kotlin.config.SourceKotlinRootType
import org.jetbrains.kotlin.idea.facet.KotlinFacet
import org.jetbrains.kotlin.platform.jvm.JvmPlatform
import java.util.concurrent.atomic.AtomicLong

internal enum class SourceExclusion {
    NOT_SOURCE,
    TEST,
    LIBRARY,
    GENERATED,
    UNSUPPORTED_ROOT,
    NON_JVM,
    UNKNOWN_PLATFORM,
}

internal data class SourceEligibility(
    val module: Module? = null,
    val root: VirtualFile? = null,
    val exclusion: SourceExclusion? = null,
) {
    val included: Boolean get() = module != null && root != null && exclusion == null
}

internal class JvmSourceEligibility(
    private val index: ProjectFileIndex,
) {
    private enum class Platform { JVM, NON_JVM, UNKNOWN }

    private val platforms = HashMap<Module, Platform>()
    private val roots = HashMap<Pair<Module, VirtualFile>, SourceExclusion?>()

    fun evaluate(file: VirtualFile): SourceEligibility {
        if (file.isDirectory || file.extension !in setOf("java", "kt") || file.name.endsWith(".kts")) {
            return SourceEligibility(exclusion = SourceExclusion.NOT_SOURCE)
        }
        if (index.isInLibraryClasses(file) || index.isInLibrarySource(file)) {
            return SourceEligibility(exclusion = SourceExclusion.LIBRARY)
        }
        if (index.isInTestSourceContent(file)) return SourceEligibility(exclusion = SourceExclusion.TEST)
        if (!index.isInSourceContent(file)) return SourceEligibility(exclusion = SourceExclusion.NOT_SOURCE)

        val module = index.getModuleForFile(file) ?: return SourceEligibility(exclusion = SourceExclusion.NOT_SOURCE)
        val root = index.getSourceRootForFile(file) ?: return SourceEligibility(exclusion = SourceExclusion.NOT_SOURCE)
        val reason = roots.getOrPut(module to root) { rootExclusion(module, root) }
        if (reason != null) return SourceEligibility(exclusion = reason)
        return when (platforms.getOrPut(module) { modulePlatform(module, root) }) {
            Platform.JVM -> SourceEligibility(module, root)
            Platform.NON_JVM -> SourceEligibility(exclusion = SourceExclusion.NON_JVM)
            Platform.UNKNOWN -> SourceEligibility(exclusion = SourceExclusion.UNKNOWN_PLATFORM)
        }
    }

    private fun rootExclusion(module: Module, root: VirtualFile): SourceExclusion? {
        val folders =
            ModuleRootManager.getInstance(module).contentEntries
                .flatMap { it.sourceFolders.toList() }
                .filter { it.file == root }
        if (folders.isEmpty()) return SourceExclusion.UNSUPPORTED_ROOT
        if (folders.any { it.jpsElement.properties is JavaSourceRootProperties &&
                (it.jpsElement.properties as JavaSourceRootProperties).isForGeneratedSources
            }
        ) return SourceExclusion.GENERATED
        return if (folders.any {
                it.rootType == JavaSourceRootType.SOURCE || it.rootType == SourceKotlinRootType
            }
        ) null else SourceExclusion.UNSUPPORTED_ROOT
    }

    private fun modulePlatform(module: Module, root: VirtualFile): Platform {
        val facet = KotlinFacet.get(module)
        if (facet == null) {
            val javaRoot = ModuleRootManager.getInstance(module).contentEntries
                .flatMap { it.sourceFolders.toList() }
                .any { it.file == root && it.rootType == JavaSourceRootType.SOURCE }
            return if (javaRoot) Platform.JVM else Platform.UNKNOWN
        }
        val target = facet.configuration.settings.targetPlatform ?: return Platform.UNKNOWN
        return if (target.componentPlatforms.singleOrNull() is JvmPlatform) {
            Platform.JVM
        } else {
            Platform.NON_JVM
        }
    }
}

@Service(Service.Level.PROJECT)
class JvmSourceScopeRevision(private val project: Project) {
    private val changes = AtomicLong()
    val revision: Long get() = changes.get()

    init {
        project.messageBus.connect(project).subscribe(
            FacetManager.FACETS_TOPIC,
            object : FacetManagerListener {
                override fun facetAdded(facet: Facet<*>) {
                    changes.incrementAndGet()
                }

                override fun facetRemoved(facet: Facet<*>) {
                    changes.incrementAndGet()
                }

                override fun facetConfigurationChanged(facet: Facet<*>) {
                    changes.incrementAndGet()
                }
            },
        )
    }

    companion object {
        fun getInstance(project: Project): JvmSourceScopeRevision =
            project.getService(JvmSourceScopeRevision::class.java)
    }
}
