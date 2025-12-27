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
package io.shamash.psi.introspections

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.PackageRules
import io.shamash.psi.fixes.ConfigureRootPackageFix
import io.shamash.psi.util.ShamashMessages.msg

class PackageRootInspection : AbstractBaseJavaLocalInspectionTool() {
    @JvmField
    var rootPackage: String = ""

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ) = object : JavaElementVisitor() {
        override fun visitClass(psiClass: PsiClass) {
            val resolvedRoot =
                rootPackage.takeIf { it.isNotBlank() }
                    ?: detectRootPackage(psiClass)
                    ?: return

            // 1) Root package rule
            if (PackageRules.violatesRootPackage(psiClass, resolvedRoot)) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("Class is outside the configured root package '$resolvedRoot'"),
                    ConfigureRootPackageFix(),
                )
                return
            }

            val mismatch = PackageRules.layerPlacementMismatch(psiClass)

            if (mismatch != null) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("Class appears to be a ${mismatch.expected} but is not located in '${mismatch.expectedMarker}' package"),
                    ConfigureRootPackageFix(),
                )
            }
        }

        /**
         * If the class is under a known layer marker, treat everything before it as the root.
         * Example: com.foo.controller -> root com.foo
         */
        private fun detectRootPackage(psiClass: PsiClass): String? {
            val file = psiClass.containingFile as? PsiJavaFile ?: return null
            val pkg = file.packageName
            if (pkg.isBlank()) return null

            val markers = listOf(".controller.", ".service.", ".dao.", ".workflow.", ".util.")
            for (m in markers) {
                if (pkg.contains(m)) {
                    return pkg.substringBefore(m).trimEnd('.')
                }
            }

            // Fallback: first 2 segments, deterministic (com.foo)
            val parts = pkg.split('.').filter { it.isNotBlank() }
            return when {
                parts.size >= 2 -> parts[0] + "." + parts[1]
                parts.size == 1 -> parts[0]
                else -> null
            }
        }
    }
}
