/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.fixes.providers

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenameProcessor
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.PsiResolver
import io.shamash.psi.core.fixes.ShamashFix

/** Uses finding data["suffix"] and RenameProcessor to update the name and its usages. */
class NamingBannedSuffixesFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID && !f.data[SUFFIX_KEY].isNullOrBlank()

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project

        val element = PsiResolver.resolveElement(project, f) ?: return emptyList()
        val named = element as? PsiNamedElement ?: return emptyList()

        val suffix = f.data[SUFFIX_KEY]?.trim().orEmpty()
        if (suffix.isBlank()) return emptyList()

        val oldName = named.name ?: return emptyList()
        if (!oldName.endsWith(suffix)) return emptyList()

        val newName = oldName.removeSuffix(suffix).trim()
        if (newName.isBlank() || newName == oldName) return emptyList()

        return listOf(RenameElementFix(project, named, oldName, newName))
    }

    private class RenameElementFix(
        private val project: Project,
        private val element: PsiNamedElement,
        private val oldName: String,
        private val newName: String,
    ) : ShamashFix {
        override val id: String = "naming.rename.$oldName.to.$newName"
        override val title: String = "Rename '$oldName' → '$newName'"

        override fun isApplicable(): Boolean = element.isValid

        override fun apply() {
            if (!element.isValid) return

            WriteCommandAction.runWriteCommandAction(project) {
                RenameProcessor(
                    project,
                    element,
                    newName,
                    // searchInComments =
                    false,
                    // searchInTextOccurrences =
                    false,
                ).run()
            }
        }
    }

    private companion object {
        private const val RULE_ID = "naming.bannedSuffixes"
        private const val SUFFIX_KEY = "suffix"
    }
}
