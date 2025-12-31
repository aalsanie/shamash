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
package io.shamash.psi.fixes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.engine.Finding

/**
 * Resolves PSI targets for a given [Finding].
 *
 * This is used by fix providers (dashboard + intentions) and should remain:
 *  - null-safe
 *  - non-throwing
 *  - language-agnostic (works for Java + Kotlin using generic PSI abstractions)
 */
object PsiResolver {
    fun resolveFile(
        project: Project,
        filePath: String,
    ): PsiFile? {
        return try {
            val vf: VirtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
            PsiManager.getInstance(project).findFile(vf)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolve the most specific PSI element for this finding.
     *
     * Priority:
     *  1) [Finding.startOffset] anchor
     *  2) member (by [Finding.memberName]) inside class
     *  3) class (by [Finding.classFqn])
     *  4) file
     */
    fun resolveElement(
        project: Project,
        finding: Finding,
    ): PsiElement? {
        val file = resolveFile(project, finding.filePath) ?: return null

        // Offset-based resolution (best effort)
        val off = finding.startOffset
        if (off != null && off >= 0 && off < file.textLength) {
            return try {
                file.findElementAt(off) ?: file
            } catch (_: Throwable) {
                // fall back
                null
            } ?: return file
        }

        val classFqn = finding.classFqn
        val memberName = finding.memberName

        if (classFqn.isNullOrBlank() && memberName.isNullOrBlank()) return file

        val classElement =
            if (classFqn.isNullOrBlank()) {
                null
            } else {
                findClassLike(file, simpleNameOf(classFqn))
            }

        if (memberName.isNullOrBlank()) {
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

    fun resolveMember(
        project: Project,
        finding: Finding,
    ): PsiElement? {
        val file = resolveFile(project, finding.filePath) ?: return null
        val element = resolveElement(project, finding) ?: return null
        if (element != file) return element
        // fallback: try member by name directly
        val name = finding.memberName ?: return null
        return findNamed(file, name)
    }

    private fun simpleNameOf(fqn: String): String = fqn.substringAfterLast('.')

    private fun findClassLike(
        file: PsiFile,
        simpleName: String,
    ): PsiElement? {
        // Generic approach: find any named element that matches the class simple name.
        // Prefer elements that look like type declarations by heuristics.
        val all = PsiTreeUtil.findChildrenOfType(file, PsiNamedElement::class.java)
        val candidates = all.filter { it.name == simpleName }
        if (candidates.isEmpty()) return null
        // Heuristic: Kotlin/Java class declarations tend to include keyword "class"/"interface" in text.
        val preferred =
            candidates.firstOrNull {
                val t = (it as? PsiElement)?.text ?: return@firstOrNull false
                t.contains("class ") || t.contains("interface ") || t.contains("enum ")
            }
        return (preferred as? PsiElement) ?: (candidates.first() as? PsiElement)
    }

    private fun findNamed(
        root: PsiElement,
        name: String,
    ): PsiElement? {
        val all = PsiTreeUtil.findChildrenOfType(root, PsiNamedElement::class.java)
        return all.firstOrNull { it.name == name }
    }
}
