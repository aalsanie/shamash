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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMember
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import io.shamash.psi.engine.Finding
import io.shamash.psi.fixes.FixContext
import io.shamash.psi.fixes.FixProvider
import io.shamash.psi.fixes.PsiResolver
import io.shamash.psi.fixes.ShamashFix

class DeadcodeUnusedPrivateMembersFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == "deadcode.unusedPrivateMembers"

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project
        val element = PsiResolver.resolveMember(project, f) ?: return emptyList()
        val member = element as? PsiMember ?: return emptyList()
        val title = "Delete unused private ${f.data["memberKind"] ?: "member"} '${member.name}'"
        return listOf(DeleteMemberFix(project, member, title))
    }

    private class DeleteMemberFix(
        private val project: com.intellij.openapi.project.Project,
        private val member: PsiMember,
        override val title: String,
    ) : ShamashFix {
        override val id: String = "deadcode.delete.${member.name}"

        override fun isApplicable(): Boolean = member.isValid

        override fun apply() {
            if (!member.isValid) return

            // Re-check references at apply time to avoid stale findings.
            val scope = GlobalSearchScope.projectScope(project)
            val hasRef = ReferencesSearch.search(member, scope).findFirst() != null
            if (hasRef) return

            WriteCommandAction.runWriteCommandAction(project) {
                if (member.isValid) {
                    member.delete()
                }
            }
        }
    }
}
