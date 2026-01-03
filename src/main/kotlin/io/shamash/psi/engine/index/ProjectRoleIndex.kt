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

/**
 * Project-wide role index.
 *
 * Why this exists:
 * - File-local role classification breaks architecture rules (deps to other project types look 'external').
 * - This index builds class->role mapping for ALL source classes within configured source globs.
 *
 * Cache:
 * - CachedValue invalidated by PsiModificationTracker.MODIFICATION_COUNT
 * - Also re-built if schema's role matcher definitions change (by keying cache per schema fingerprint).
 */
class ProjectRoleIndex private constructor(
    private val project: Project,
) {
    companion object {
        private val KEY: Key<ProjectRoleIndex> = Key.create("shamash.psi.projectRoleIndex")

        fun getInstance(project: Project): ProjectRoleIndex =
            project.getUserData(KEY) ?: ProjectRoleIndex(project).also { project.putUserData(KEY, it) }
    }

    // Cache is per schema fingerprint
    // changes in roles/sourceGlobs rebuild immediately.
    private val cacheByFingerprint = mutableMapOf<String, CachedValue<ProjectRoleIndexSnapshot>>()

    fun getOrBuild(config: ShamashPsiConfigV1): ProjectRoleIndexSnapshot {
        val fp = fingerprint(config)
        val cached =
            cacheByFingerprint.getOrPut(fp) {
                CachedValuesManager.getManager(project).createCachedValue {
                    CachedValueProvider.Result.create(buildSnapshot(config), PsiModificationTracker.MODIFICATION_COUNT)
                }
            }
        return cached.value
    }

    private fun buildSnapshot(config: ShamashPsiConfigV1): ProjectRoleIndexSnapshot {
        val scope = GlobalSearchScope.projectScope(project)
        val include = config.project.sourceGlobs.include
        val exclude = config.project.sourceGlobs.exclude

        val classFacts = ArrayList<ClassFact>(4096)
        val annotations = HashMap<String, Set<String>>(4096)
        val filePaths = HashMap<String, String>(4096)

        // this covers Java + Kotlin (as light classes).
        AllClassesSearch.search(scope, project).forEach { psiClass: PsiClass ->
            val fqn = psiClass.qualifiedName ?: return@forEach

            val vf = psiClass.containingFile?.virtualFile
            val path = vf?.path ?: return@forEach

            // apply source globs
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
        // deliberately ignore rule params; those don't affect role index.
        val rolesPart =
            config.roles.entries
                .sortedBy { it.key }
                .joinToString(";") { (k, v) -> "$k:${v.priority}:${v.match}" }
        val globsPart =
            (
                config.project.sourceGlobs.include
                    .sorted() to
                    config.project.sourceGlobs.exclude
                        .sorted()
            ).toString()
        return (rolesPart + "|" + globsPart).hashCode().toString()
    }
}

data class ProjectRoleIndexSnapshot(
    val roleToClasses: Map<String, Set<String>>,
    val classToRole: Map<String, String>,
    val classToAnnotations: Map<String, Set<String>>,
    val classToFilePath: Map<String, String>,
)
