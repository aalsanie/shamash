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
package io.shamash.psi.engine.index

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AllClassesSearch
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.RoleClassifier
import io.shamash.psi.facts.model.v1.ClassFact
import io.shamash.psi.util.GlobMatcher
import java.security.MessageDigest

/**
 * Project-wide role index.
 *
 * - File-local role classification breaks architecture rules (deps to other project types look 'external').
 * - This index builds class->role mapping for ALL source classes within configured source globs.
 *
 * Cache:
 * - CachedValue invalidated by PsiModificationTracker.MODIFICATION_COUNT
 * - Also re-built if schema's role matcher definitions change (fingerprint-based).
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

        // Same CachedValue instance, but schema fingerprint changed (roles/globs changed).
        val rebuilt = SnapshotCache(fp, buildSnapshot(config))
        // We cannot mutate CachedValue contents, so we replace the CachedValue in user data.
        val newCv =
            CachedValuesManager.getManager(project).createCachedValue {
                CachedValueProvider.Result.create(rebuilt, PsiModificationTracker.MODIFICATION_COUNT)
            }
        project.putUserData(cacheKey, newCv)
        return rebuilt.snapshot
    }

    private fun buildSnapshot(config: ShamashPsiConfigV1): ProjectRoleIndexSnapshot {
        val scope = GlobalSearchScope.projectScope(project)
        val include = config.project.sourceGlobs.include
        val exclude = config.project.sourceGlobs.exclude

        val classFacts = ArrayList<ClassFact>(4096)
        val annotations = HashMap<String, Set<String>>(4096)
        val filePaths = HashMap<String, String>(4096)

        // Covers Java + Kotlin (as light classes).
        AllClassesSearch.search(scope, project).forEach { psiClass: PsiClass ->
            ProgressManager.checkCanceled()

            val fqn = psiClass.qualifiedName ?: return@forEach
            val vf = psiClass.containingFile?.virtualFile ?: return@forEach

            val rawPath = vf.path
            val path = GlobMatcher.normalizePath(rawPath)

            // Apply source globs
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

        val roleClassifier = RoleClassifier(multiRole = false)
        val classification = roleClassifier.classify(classFacts, config.roles)

        return ProjectRoleIndexSnapshot(
            roleToClasses = classification.roleToClasses,
            classToRole = classification.classToRole,
            classToAnnotations = annotations,
            classToFilePath = filePaths,
        )
    }

    private fun fingerprint(config: ShamashPsiConfigV1): String {
        // Must change when role definitions or source globs change.
        // Deliberately ignore rule params; those don't affect role index.

        // Ensure stable ordering.
        val rolesPart =
            config.roles.entries
                .sortedBy { it.key }
                .joinToString(";") { (k, v) ->
                    val matchStable = v.match.toString() // best-effort; match is a schema model and should be stable.
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
