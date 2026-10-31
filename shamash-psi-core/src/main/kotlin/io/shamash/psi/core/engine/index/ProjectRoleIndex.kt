/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.psi.core.engine.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.psi.core.config.SourceGlobFilter
import io.shamash.psi.core.config.schema.v1.model.Role
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.config.schema.v1.model.SourceGlobsV1
import io.shamash.psi.core.engine.RoleClassifier
import io.shamash.psi.core.facts.FactExtractor
import io.shamash.psi.core.facts.model.v1.ClassFact

/**
 * Project-wide roles keep dependencies on other source files from appearing external.
 * Invalidate on PSI changes and on role/source-glob changes.
 */
class ProjectRoleIndex private constructor(
    private val project: Project,
) {
    companion object {
        private val KEY: Key<ProjectRoleIndex> = Key.create("shamash.psi.projectRoleIndex")

        fun getInstance(project: Project): ProjectRoleIndex =
            synchronized(KEY) {
                project.getUserData(KEY) ?: ProjectRoleIndex(project).also { project.putUserData(KEY, it) }
            }
    }

    /** Only the configuration inputs used by the index; unrelated rule settings cannot invalidate it. */
    private data class IndexInputs(
        val roles: Map<String, Role>,
        val sourceGlobs: SourceGlobsV1,
    ) {
        companion object {
            fun from(config: ShamashPsiConfigV1): IndexInputs =
                IndexInputs(
                    roles = config.roles.toMap(),
                    sourceGlobs =
                        config.project.sourceGlobs.let { globs ->
                            globs.copy(include = globs.include.toList(), exclude = globs.exclude.toList())
                        },
                )
        }
    }

    private data class SnapshotCache(
        val inputs: IndexInputs,
        val value: CachedValue<ProjectRoleIndexSnapshot>,
    )

    private val cacheLock = Any()

    @Volatile
    private var cache: SnapshotCache? = null

    fun getOrBuild(config: ShamashPsiConfigV1): ProjectRoleIndexSnapshot {
        val inputs = IndexInputs.from(config)
        val selected =
            cache?.takeIf { it.inputs == inputs }
                ?: synchronized(cacheLock) {
                    cache?.takeIf { it.inputs == inputs }
                        ?: createCache(inputs).also { cache = it }
                }

        // Never compute a project-wide snapshot under cacheLock. Concurrent requests may
        // select different configurations; each must receive its own matching snapshot.
        return selected.value.value
    }

    private fun createCache(inputs: IndexInputs): SnapshotCache {
        val roots = ProjectRootModificationTracker.getInstance(project)
        val value =
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(
                    buildSnapshot(inputs),
                    PsiModificationTracker.MODIFICATION_COUNT,
                    roots,
                )
            }
        return SnapshotCache(inputs, value)
    }

    private fun buildSnapshot(inputs: IndexInputs): ProjectRoleIndexSnapshot {
        ProgressManager.checkCanceled()
        val include = inputs.sourceGlobs.include
        val exclude = inputs.sourceGlobs.exclude

        val bySearch = collectViaAllClassesSearch(include, exclude)

        val fromFiles = collectViaVfsScan(include, exclude, indexedJavaFiles = bySearch.classToFilePath.values.toSet())
        val collected =
            Collected(
                classFacts = (fromFiles.classFacts + bySearch.classFacts).distinctBy { it.fqName }.sortedBy { it.fqName },
                classToAnnotations = bySearch.classToAnnotations + fromFiles.classToAnnotations,
                classToFilePath = bySearch.classToFilePath + fromFiles.classToFilePath,
            )

        val roleClassifier = RoleClassifier(multiRole = false)
        val classification = roleClassifier.classify(collected.classFacts, inputs.roles)

        return ProjectRoleIndexSnapshot(
            roleToClasses = classification.roleToClasses,
            classToRole = classification.classToRole,
            classToAnnotations = collected.classToAnnotations,
            classToFilePath = collected.classToFilePath,
        )
    }

    private data class Collected(
        val classFacts: List<ClassFact>,
        val classToAnnotations: Map<String, Set<String>>,
        val classToFilePath: Map<String, String>,
    )

    private fun collectViaAllClassesSearch(
        include: List<String>,
        exclude: List<String>,
    ): Collected {
        val scope = GlobalSearchScope.projectScope(project)

        val classFacts = ArrayList<ClassFact>(4096)
        val annotations = HashMap<String, Set<String>>(4096)
        val filePaths = HashMap<String, String>(4096)

        AllClassesSearch.search(scope, project).forEach { psiClass: PsiClass ->
            ProgressManager.checkCanceled()

            val fqn = psiClass.qualifiedName ?: return@forEach
            val vf = psiClass.containingFile?.virtualFile ?: return@forEach

            val path = GlobMatcher.normalizePath(vf.path)

            if (!SourceGlobFilter.matches(path, include, exclude)) return@forEach

            val pkg = fqn.substringBeforeLast('.', "")
            val simple = psiClass.name ?: fqn.substringAfterLast('.')
            val anns =
                psiClass.modifierList
                    ?.annotations
                    ?.mapNotNull { it.qualifiedName }
                    ?.toSet() ?: emptySet()

            val hasMain =
                psiClass.methods.any { m ->
                    m.name == "main" &&
                        m.hasModifierProperty(PsiModifier.PUBLIC) &&
                        m.hasModifierProperty(PsiModifier.STATIC) &&
                        m.parameterList.parametersCount == 1
                }

            classFacts +=
                ClassFact(
                    fqName = fqn,
                    packageName = pkg,
                    simpleName = simple,
                    annotationsFqns = anns,
                    superClassFqn = psiClass.superClass?.qualifiedName,
                    interfacesFqns = psiClass.interfaces.mapNotNull { it.qualifiedName }.toSet(),
                    hasMainMethod = hasMain,
                    filePath = path,
                    textRange = null,
                )

            annotations[fqn] = anns
            filePaths[fqn] = path
        }

        return Collected(
            classFacts = classFacts,
            classToAnnotations = annotations,
            classToFilePath = filePaths,
        )
    }

    private fun collectViaVfsScan(
        include: List<String>,
        exclude: List<String>,
        indexedJavaFiles: Set<String>,
    ): Collected {
        val roots = collectContentRoots()
        if (roots.isEmpty()) {
            return Collected(emptyList(), emptyMap(), emptyMap())
        }

        val psiManager = PsiManager.getInstance(project)

        val seen = LinkedHashMap<String, ClassFact>(4096)
        val annotations = HashMap<String, Set<String>>(4096)
        val filePaths = HashMap<String, String>(4096)

        for (root in roots) {
            ProgressManager.checkCanceled()
            VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                ProgressManager.checkCanceled()

                if (vf.isDirectory) return@iterateChildrenRecursively true
                if (vf.fileType.isBinary) return@iterateChildrenRecursively true
                if (vf.extension != "java" && vf.extension != "kt" && vf.extension != "kts") return@iterateChildrenRecursively true

                val path = GlobMatcher.normalizePath(vf.path)
                if (vf.extension == "java" && path in indexedJavaFiles) return@iterateChildrenRecursively true
                if (!SourceGlobFilter.matches(path, include, exclude)) return@iterateChildrenRecursively true

                val psiFile: PsiFile = psiManager.findFile(vf) ?: return@iterateChildrenRecursively true
                val facts = FactExtractor.extractResult(psiFile).facts

                for (c in facts.classes) {
                    if (!seen.containsKey(c.fqName)) {
                        seen[c.fqName] = c
                        annotations[c.fqName] = c.annotationsFqns
                        filePaths[c.fqName] = c.filePath
                    }
                }

                true
            }
        }

        return Collected(
            classFacts = seen.values.toList(),
            classToAnnotations = annotations,
            classToFilePath = filePaths,
        )
    }

    private fun collectContentRoots(): List<VirtualFile> {
        val out = ArrayList<VirtualFile>(8)

        out.addAll(ProjectRootManager.getInstance(project).contentRoots)

        val modules =
            com.intellij.openapi.module.ModuleManager
                .getInstance(project)
                .modules
        for (m in modules) {
            out.addAll(ModuleRootManager.getInstance(m).contentRoots)
        }

        val seen = LinkedHashSet<String>(out.size)
        val deduped = ArrayList<VirtualFile>(out.size)
        for (vf in out) {
            if (seen.add(vf.path)) deduped.add(vf)
        }
        return deduped
    }

}

data class ProjectRoleIndexSnapshot(
    val roleToClasses: Map<String, Set<String>>,
    val classToRole: Map<String, String>,
    val classToAnnotations: Map<String, Set<String>>,
    val classToFilePath: Map<String, String>,
)
