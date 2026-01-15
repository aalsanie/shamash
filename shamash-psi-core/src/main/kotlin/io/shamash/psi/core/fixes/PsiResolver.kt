/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.psi.core.fixes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.artifacts.contract.Finding

/**
 * Resolves PSI targets for a given [Finding].
 *
 * Must remain:
 *  - null-safe
 *  - non-throwing
 *  - language-agnostic (works for Java + Kotlin using generic PSI abstractions)
 */
object PsiResolver {
    fun resolveFile(
        project: Project,
        filePath: String,
    ): PsiFile? {
        return runCatching {
            val normalized = normalizePath(filePath)
            val vf: VirtualFile =
                LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized) ?: return null
            PsiManager.getInstance(project).findFile(vf)
        }.getOrNull()
    }

    /**
     * Resolve the most specific PSI element for this finding.
     *
     * Priority:
     *  1) [Finding.startOffset] anchor -> nearest meaningful parent (named/member-ish), else leaf, else file
     *  2) member (by [Finding.memberName]) inside class
     *  3) class (by [Finding.classFqn])
     *  4) file
     */
    fun resolveElement(
        project: Project,
        finding: Finding,
    ): PsiElement? {
        val file = resolveFile(project, finding.filePath) ?: return null

        // 1) Offset-based resolution (best effort)
        val off = finding.startOffset
        if (off != null && off in 0 until file.textLength) {
            val leaf = runCatching { file.findElementAt(off) }.getOrNull()
            if (leaf != null) {
                // Prefer a "meaningful" element over a leaf token/whitespace.
                val meaningful =
                    PsiTreeUtil.getParentOfType(
                        leaf,
                        PsiNamedElement::class.java,
                        // strict =
                        false,
                    ) as? PsiElement

                return meaningful ?: leaf
            }
        }

        val classFqn = finding.classFqn?.trim().takeIf { !it.isNullOrBlank() }
        val memberName = finding.memberName?.trim().takeIf { !it.isNullOrBlank() }

        if (classFqn == null && memberName == null) return file

        val classElement =
            if (classFqn == null) null else findClassLike(file, simpleNameOf(classFqn))

        if (memberName == null) {
            return classElement ?: file
        }

        // If class exists, search within it first; else search the file.
        val scopeRoot = classElement ?: file
        val member = findNamed(scopeRoot, memberName)
        return member ?: (classElement ?: file)
    }

    fun resolveClass(
        project: Project,
        classFqn: String,
        filePath: String,
    ): PsiElement? {
        val file = resolveFile(project, filePath) ?: return null
        return findClassLike(file, simpleNameOf(classFqn))
    }

    /**
     * Resolve a member-ish element.
     *
     * If [resolveElement] returns a leaf token, we try to lift it to a named element.
     * If it returns file, we fallback to searching by memberName.
     */
    fun resolveMember(
        project: Project,
        finding: Finding,
    ): PsiElement? {
        val file = resolveFile(project, finding.filePath) ?: return null

        val resolved = resolveElement(project, finding) ?: return null
        if (resolved != file) {
            val asNamed =
                (resolved as? PsiNamedElement)
                    ?: PsiTreeUtil.getParentOfType(resolved, PsiNamedElement::class.java, false)
            return asNamed as? PsiElement ?: resolved
        }

        val name = finding.memberName?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return findNamed(file, name)
    }

    private fun normalizePath(path: String): String {
        // LocalFileSystem expects system-independent paths with '/'.
        // Also strips accidental "file://" prefixes if any caller passes URLs.
        val p = path.removePrefix("file://").removePrefix("file:")
        return p.replace('\\', '/')
    }

    private fun simpleNameOf(fqn: String): String = fqn.substringAfterLast('.')

    private fun findClassLike(
        file: PsiFile,
        simpleName: String,
    ): PsiElement? {
        val all = PsiTreeUtil.findChildrenOfType(file, PsiNamedElement::class.java)
        val candidates = all.filter { it.name == simpleName }
        if (candidates.isEmpty()) return null

        // Heuristic: prefer likely type declarations.
        val preferred =
            candidates.firstOrNull {
                val t = (it as? PsiElement)?.text ?: return@firstOrNull false
                t.contains("class ") || t.contains("interface ") || t.contains("enum ") || t.contains("object ")
            }

        return (preferred as? PsiElement) ?: (candidates.first() as? PsiElement)
    }

    private fun findNamed(
        root: PsiElement,
        name: String,
    ): PsiElement? {
        val all = PsiTreeUtil.findChildrenOfType(root, PsiNamedElement::class.java)
        return all.firstOrNull { it.name == name } as? PsiElement
    }
}
