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
import com.intellij.psi.PsiMember
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import io.shamash.artifacts.contract.Finding
import io.shamash.psi.core.fixes.FixContext
import io.shamash.psi.core.fixes.FixProvider
import io.shamash.psi.core.fixes.PsiResolver
import io.shamash.psi.core.fixes.ShamashFix

class DeadcodeUnusedPrivateMembersFixProvider : FixProvider {
    override fun supports(f: Finding): Boolean = f.ruleId == RULE_ID

    override fun fixesFor(
        f: Finding,
        ctx: FixContext,
    ): List<ShamashFix> {
        val project = ctx.project

        val element = PsiResolver.resolveMember(project, f) ?: return emptyList()
        val member = element as? PsiMember ?: return emptyList()

        val kind = f.data[MEMBER_KIND_KEY]?.takeIf { it.isNotBlank() } ?: "member"
        val name = member.name ?: "<unnamed>"
        val title = "Delete unused private $kind '$name'"

        return listOf(DeleteMemberFix(project, member, title))
    }

    private class DeleteMemberFix(
        private val project: Project,
        private val member: PsiMember,
        override val title: String,
    ) : ShamashFix {
        override val id: String = "deadcode.unusedPrivateMembers.delete.${member.name ?: "unnamed"}"

        override fun isApplicable(): Boolean = member.isValid

        override fun apply() {
            if (!member.isValid) return

            // Re-check references at apply time to avoid acting on stale findings.
            val scope = GlobalSearchScope.projectScope(project)
            val hasRef = ReferencesSearch.search(member, scope).findFirst() != null
            if (hasRef) return

            WriteCommandAction.runWriteCommandAction(project) {
                if (member.isValid) member.delete()
            }
        }
    }

    private companion object {
        private const val RULE_ID = "deadcode.unusedPrivateMembers"
        private const val MEMBER_KIND_KEY = "memberKind"
    }
}
