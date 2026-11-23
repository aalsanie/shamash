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

enum class SourceExclusion {
    NOT_SOURCE,
    TEST,
    LIBRARY,
    GENERATED,
    UNSUPPORTED_ROOT,
    NON_JVM,
    UNKNOWN_PLATFORM,
}

sealed interface SourceEligibility {
    val included: Boolean
    val exclusion: SourceExclusion?

    data class Included(
        val module: Module,
        val root: VirtualFile,
    ) : SourceEligibility {
        override val included = true
        override val exclusion: SourceExclusion? = null
    }

    data class Excluded(
        override val exclusion: SourceExclusion,
    ) : SourceEligibility {
        override val included = false
    }
}

class JvmSourceEligibility(
    private val index: ProjectFileIndex,
) {
    private enum class Platform { JVM, NON_JVM, UNKNOWN, IMPLICIT }

    private enum class RootKind { JAVA, KOTLIN, GENERATED, UNSUPPORTED }

    private val platforms = HashMap<Module, Platform>()
    private val roots = HashMap<Pair<Module, VirtualFile>, RootKind>()

    fun evaluate(file: VirtualFile): SourceEligibility {
        if (file.isDirectory || file.extension !in setOf("java", "kt")) {
            return SourceEligibility.Excluded(SourceExclusion.NOT_SOURCE)
        }
        if (index.isInLibraryClasses(file) || index.isInLibrarySource(file)) {
            return SourceEligibility.Excluded(SourceExclusion.LIBRARY)
        }
        if (index.isInTestSourceContent(file)) return SourceEligibility.Excluded(SourceExclusion.TEST)
        if (!index.isInSourceContent(file)) return SourceEligibility.Excluded(SourceExclusion.NOT_SOURCE)

        val module = index.getModuleForFile(file) ?: return SourceEligibility.Excluded(SourceExclusion.NOT_SOURCE)
        val root = index.getSourceRootForFile(file) ?: return SourceEligibility.Excluded(SourceExclusion.NOT_SOURCE)
        val rootKind = roots.getOrPut(module to root) { rootKind(module, root) }
        if (rootKind == RootKind.GENERATED) return SourceEligibility.Excluded(SourceExclusion.GENERATED)
        if (rootKind == RootKind.UNSUPPORTED) return SourceEligibility.Excluded(SourceExclusion.UNSUPPORTED_ROOT)
        return when (platforms.getOrPut(module) { modulePlatform(module) }) {
            Platform.JVM -> {
                SourceEligibility.Included(module, root)
            }

            Platform.NON_JVM -> {
                SourceEligibility.Excluded(SourceExclusion.NON_JVM)
            }

            Platform.UNKNOWN -> {
                SourceEligibility.Excluded(SourceExclusion.UNKNOWN_PLATFORM)
            }

            Platform.IMPLICIT -> {
                if (rootKind == RootKind.JAVA) {
                    SourceEligibility.Included(module, root)
                } else {
                    SourceEligibility.Excluded(SourceExclusion.UNKNOWN_PLATFORM)
                }
            }
        }
    }

    private fun rootKind(
        module: Module,
        root: VirtualFile,
    ): RootKind {
        val folders =
            ModuleRootManager
                .getInstance(module)
                .contentEntries
                .flatMap { it.sourceFolders.toList() }
                .filter { it.file == root }
        if (folders.isEmpty()) return RootKind.UNSUPPORTED
        if (folders.any {
                (it.jpsElement.properties as? JavaSourceRootProperties)?.isForGeneratedSources == true
            }
        ) {
            return RootKind.GENERATED
        }
        return when {
            folders.any { it.rootType == JavaSourceRootType.SOURCE } -> RootKind.JAVA
            folders.any { it.rootType == SourceKotlinRootType } -> RootKind.KOTLIN
            else -> RootKind.UNSUPPORTED
        }
    }

    private fun modulePlatform(module: Module): Platform {
        val facet = KotlinFacet.get(module) ?: return Platform.IMPLICIT
        val target = facet.configuration.settings.targetPlatform ?: return Platform.UNKNOWN
        return if (target.componentPlatforms.singleOrNull() is JvmPlatform) {
            Platform.JVM
        } else {
            Platform.NON_JVM
        }
    }
}

@Service(Service.Level.PROJECT)
class JvmSourceScopeRevision(
    private val project: Project,
) {
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
        fun getInstance(project: Project): JvmSourceScopeRevision = project.getService(JvmSourceScopeRevision::class.java)
    }
}
