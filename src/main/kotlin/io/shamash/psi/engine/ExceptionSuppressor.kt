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
package io.shamash.psi.engine

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import io.shamash.psi.config.schema.v1.model.ExceptionMatch
import io.shamash.psi.config.schema.v1.model.ShamashPsiConfigV1
import io.shamash.psi.engine.index.ProjectRoleIndexSnapshot
import io.shamash.psi.util.GlobMatcher
import java.time.LocalDate

/**
 * Production-ready suppression (exceptions) matching.
 *
 * Supported matchers (v1.0):
 * - fileGlob (supports **,*,?)
 * - packageRegex
 * - classNameRegex (simple name)
 * - methodNameRegex / fieldNameRegex (uses Finding.memberName)
 * - role (class role)
 * - hasAnnotation / hasAnnotationPrefix (class or member, best-effort)
 * - expiresOn (if expired, suppression is NOT applied)
 */
internal object ExceptionSuppressor {
    fun apply(
        findings: List<Finding>,
        config: ShamashPsiConfigV1,
        roleIndex: ProjectRoleIndexSnapshot,
        file: PsiFile,
    ): List<Finding> {
        if (config.shamashExceptions.isEmpty()) return findings

        val project = file.project
        val basePath = project.basePath

        return findings.filterNot { f ->
            config.shamashExceptions.any { ex ->
                if (!ex.suppress.contains(f.ruleId)) return@any false

                // expiry enforcement
                ex.expiresOn?.let { dateStr ->
                    runCatching { LocalDate.parse(dateStr) }.getOrNull()?.let { exp ->
                        if (LocalDate.now().isAfter(exp)) return@any false
                    }
                }

                matches(ex.match, f, roleIndex, project, basePath)
            }
        }
    }

    private fun matches(
        match: ExceptionMatch,
        f: Finding,
        roleIndex: ProjectRoleIndexSnapshot,
        project: Project,
        basePath: String?,
    ): Boolean {
        // file glob
        match.fileGlob?.let { g ->
            val fp = f.filePath
            val rel =
                basePath?.let { bp ->
                    val nBp = GlobMatcher.normalizePath(bp)
                    val nFp = GlobMatcher.normalizePath(fp)
                    if (nFp.startsWith(nBp)) nFp.removePrefix(nBp).trimStart('/') else null
                }

            val ok = GlobMatcher.matches(g, fp) || (rel != null && GlobMatcher.matches(g, rel))
            if (!ok) return false
        }

        // role
        match.role?.let { expected ->
            val cls = f.classFqn ?: return false
            val role = roleIndex.classToRole[cls] ?: return false
            if (role != expected) return false
        }

        // package regex
        match.packageRegex?.let { rx ->
            val cls = f.classFqn ?: return false
            val pkg = cls.substringBeforeLast('.', "")
            if (!Regex(rx).containsMatchIn(pkg)) return false
        }

        // class name regex (simple)
        match.classNameRegex?.let { rx ->
            val cls = f.classFqn ?: return false
            val simple = cls.substringAfterLast('.')
            if (!Regex(rx).containsMatchIn(simple)) return false
        }

        // member regex
        match.methodNameRegex?.let { rx ->
            val name = f.memberName ?: return false
            if (!Regex(rx).containsMatchIn(name)) return false
        }
        match.fieldNameRegex?.let { rx ->
            val name = f.memberName ?: return false
            if (!Regex(rx).containsMatchIn(name)) return false
        }

        // annotation match
        if (match.hasAnnotation != null || match.hasAnnotationPrefix != null) {
            val cls = f.classFqn ?: return false
            val has = hasMatchingAnnotation(match, f, cls, roleIndex, project)
            if (!has) return false
        }

        return true
    }

    private fun hasMatchingAnnotation(
        match: ExceptionMatch,
        f: Finding,
        clsFqn: String,
        roleIndex: ProjectRoleIndexSnapshot,
        project: Project,
    ): Boolean {
        val exact = match.hasAnnotation
        val prefix = match.hasAnnotationPrefix

        fun annOk(ann: String): Boolean {
            if (exact != null && ann == exact) return true
            if (prefix != null && ann.startsWith(prefix)) return true
            return false
        }

        // 1) Fast path: class annotations from index
        val classAnns = roleIndex.classToAnnotations[clsFqn]
        if (classAnns != null && classAnns.any(::annOk)) return true

        // 2) Best-effort member annotations via PSI resolution
        val memberName = f.memberName ?: return false
        val psiClass =
            JavaPsiFacade.getInstance(project).findClass(clsFqn, GlobalSearchScope.projectScope(project))
                ?: return false

        // Check method first
        psiClass.findMethodsByName(memberName, true).forEach { m ->
            m.modifierList.annotations
                .mapNotNull { it.qualifiedName }
                .forEach { if (annOk(it)) return true }
        }
        // Check field
        psiClass.findFieldByName(memberName, true)?.let { fld ->
            fld.modifierList
                ?.annotations
                ?.mapNotNull { it.qualifiedName }
                ?.forEach { if (annOk(it)) return true }
        }

        return false
    }
}
