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

/**
 * Fix provider for naming.bannedSuffixes.
 *
 * Engine contract:
 * - Finding.data["suffix"] contains the banned suffix that should be removed.
 *
 * Fix:
 * - Renames the target PSI named element by stripping the suffix.
 * - Uses RenameProcessor to perform a refactoring-safe rename across usages.
 */
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

            // RenameProcessor performs a refactoring-safe rename across usages.
            // It will no-op/fail safely if the element cannot be renamed.
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
