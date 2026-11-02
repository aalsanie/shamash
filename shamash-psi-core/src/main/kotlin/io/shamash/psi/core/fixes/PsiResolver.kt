/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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

/** Resolve Java/Kotlin targets through generic PSI; missing or stale targets must return null. */
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

    /** Prefer an offset anchor, then member, class, and finally file. */
    fun resolveElement(
        project: Project,
        finding: Finding,
    ): PsiElement? {
        val file = resolveFile(project, finding.filePath) ?: return null

        val off = finding.startOffset
        if (off != null && off in 0 until file.textLength) {
            val leaf = runCatching { file.findElementAt(off) }.getOrNull()
            if (leaf != null) {
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
