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

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.refactor.SafeMoveRefactoring
import io.shamash.psi.refactor.TargetPackageResolver

class ConfigureRootPackageFix : LocalQuickFix {
    override fun getName(): String = "Move class to correct package"

    override fun getFamilyName(): String = "Shamash architecture fixes"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val psiClass =
            PsiTreeUtil.getParentOfType(descriptor.psiElement, PsiClass::class.java, false)
                ?: return

        val file = psiClass.containingFile as? PsiJavaFile ?: return

        val root = TargetPackageResolver.resolveRoot(file) ?: return
        val targetPkg = TargetPackageResolver.resolveTargetPackage(root, psiClass) ?: return

        if (file.packageName == targetPkg) return

        // Creates the package if missing (asks user), then runs a safe refactoring move.
        SafeMoveRefactoring.moveToPackage(
            project = project,
            file = file,
            targetPackageFqn = targetPkg,
            askUserToCreate = true,
        )
    }
}
