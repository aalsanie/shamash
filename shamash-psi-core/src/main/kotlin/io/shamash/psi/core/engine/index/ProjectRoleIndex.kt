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
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.core.engine.RoleClassifier
import io.shamash.psi.core.facts.FactExtractor
import io.shamash.psi.core.facts.model.v1.ClassFact
import java.security.MessageDigest
import kotlin.collections.isNotEmpty

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
            project.getUserData(KEY) ?: ProjectRoleIndex(project).also { project.putUserData(KEY, it) }
    }

    private data class SnapshotCache(
        val fingerprint: String,
        val snapshot: ProjectRoleIndexSnapshot,
    )

    private val cacheKey: Key<CachedValue<SnapshotCache>> = Key.create("shamash.psi.projectRoleIndex.snapshotCache.v1")

    fun getOrBuild(config: ShamashPsiConfigV1): ProjectRoleIndexSnapshot {
        val fp = fingerprint(config)

        val cv =
            project.getUserData(cacheKey) ?: CachedValuesManager
                .getManager(project)
                .createCachedValue {
                    CachedValueProvider.Result.create(
                        SnapshotCache(
                            fingerprint = fp,
                            snapshot = buildSnapshot(config),
                        ),
                        PsiModificationTracker.MODIFICATION_COUNT,
                    )
                }.also { project.putUserData(cacheKey, it) }

        val cached = cv.value
        if (cached.fingerprint == fp) return cached.snapshot

        // Replace the CachedValue when config changes; its contents cannot be mutated.
        val rebuilt = SnapshotCache(fp, buildSnapshot(config))
        val newCv =
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(rebuilt, PsiModificationTracker.MODIFICATION_COUNT)
            }
        project.putUserData(cacheKey, newCv)
        return rebuilt.snapshot
    }

    private fun buildSnapshot(config: ShamashPsiConfigV1): ProjectRoleIndexSnapshot {
        val include = config.project.sourceGlobs.include
        val exclude = config.project.sourceGlobs.exclude

        val bySearch = collectViaAllClassesSearch(include, exclude)

        // Fallback strategy: VFS walk + FactExtractor (covers Kotlin reliably, avoids "no classes" cases).
        val collected =
            if (bySearch.classFacts.isNotEmpty()) {
                bySearch
            } else {
                collectViaVfsScan(include, exclude)
            }

        val roleClassifier = RoleClassifier(multiRole = false)
        val classification = roleClassifier.classify(collected.classFacts, config.roles)

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

            val ok = include.any { GlobMatcher.matches(it, path) } && exclude.none { GlobMatcher.matches(it, path) }
            if (!ok) return@forEach

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

                val path = GlobMatcher.normalizePath(vf.path)

                val ok = include.any { GlobMatcher.matches(it, path) } && exclude.none { GlobMatcher.matches(it, path) }
                if (!ok) return@iterateChildrenRecursively true

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

    private fun fingerprint(config: ShamashPsiConfigV1): String {
        // Fingerprint roles and source globs; rule params do not affect this index.

        val rolesPart =
            config.roles.entries
                .sortedBy { it.key }
                .joinToString(";") { (k, v) ->
                    val matchStable = v.match.toString()
                    "$k:${v.priority}:$matchStable"
                }

        val includePart =
            config.project.sourceGlobs.include
                .sorted()
                .joinToString(",") { GlobMatcher.normalizePath(it) }
        val excludePart =
            config.project.sourceGlobs.exclude
                .sorted()
                .joinToString(",") { GlobMatcher.normalizePath(it) }

        val material = "$rolesPart|inc:$includePart|exc:$excludePart"
        return sha256Hex(material)
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
        return sb.toString()
    }
}

data class ProjectRoleIndexSnapshot(
    val roleToClasses: Map<String, Set<String>>,
    val classToRole: Map<String, String>,
    val classToAnnotations: Map<String, Set<String>>,
    val classToFilePath: Map<String, String>,
)
