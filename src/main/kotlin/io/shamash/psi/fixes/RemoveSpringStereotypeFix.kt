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
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass

class RemoveSpringStereotypeFix : LocalQuickFix {
    override fun getFamilyName(): String = "Remove Spring stereotype annotation"

    override fun applyFix(
        project: Project,
        descriptor: ProblemDescriptor,
    ) {
        val psiClass = descriptor.psiElement.parent as? PsiClass ?: return
        val modifierList = psiClass.modifierList ?: return

        modifierList.annotations
            .filter { it.isSpringStereotype() }
            .forEach(PsiAnnotation::delete)
    }

    private fun PsiAnnotation.isSpringStereotype(): Boolean {
        val qName = qualifiedName ?: return false
        return qName == "org.springframework.stereotype.Component" ||
            qName == "org.springframework.stereotype.Service" ||
            qName == "org.springframework.stereotype.Repository" ||
            qName == "org.springframework.stereotype.Controller" ||
            qName == "org.springframework.web.bind.annotation.RestController"
    }
}
