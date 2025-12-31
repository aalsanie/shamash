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
package io.shamash.psi.fixes.providers

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.refactoring.rename.RenameProcessor
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixProvider
import io.shamash.psi.fixes.PsiResolver
import io.shamash.psi.fixes.ShamashFix

class NamingBannedSuffixesFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == "naming.bannedSuffixes" && !f.data["suffix"].isNullOrBlank()

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project
        val element = PsiResolver.resolveElement(project, f) ?: return emptyList()
        val named = element as? com.intellij.psi.PsiNamedElement ?: return emptyList()
        val suffix = f.data["suffix"] ?: return emptyList()
        val oldName = named.name ?: return emptyList()
        if (!oldName.endsWith(suffix)) return emptyList()
        val newName = oldName.removeSuffix(suffix).ifBlank { oldName }
        if (newName == oldName) return emptyList()
        return listOf(RenameElementFix(project, named, oldName, newName))
    }

    private class RenameElementFix(
        private val project: com.intellij.openapi.project.Project,
        private val element: com.intellij.psi.PsiNamedElement,
        private val oldName: String,
        private val newName: String,
    ) : ShamashFix {
        override val id: String = "naming.rename.$oldName.to.$newName"
        override val title: String = "Rename '$oldName' → '$newName'"

        override fun isApplicable(): Boolean = element.isValid

        override fun apply() {
            if (!element.isValid) return
            // RenameProcessor will perform a safe refactoring across usages.
            WriteCommandAction.runWriteCommandAction(project) {
                RenameProcessor(project, element, newName, false, false).run()
            }
        }
    }
}
