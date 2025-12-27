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
import io.shamash.psi.architecture.DependencyQueries
import io.shamash.psi.architecture.Layer
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.LayerRules
import io.shamash.psi.fixes.DeleteElementFix
import io.shamash.psi.util.ShamashMessages.msg

class ServiceControllerDependencyInspection : AbstractBaseJavaLocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ) = object : JavaElementVisitor() {
        override fun visitClass(psiClass: PsiClass) {
            val layer = LayerDetector.detect(psiClass)
            if (layer != Layer.SERVICE) return

            if (!LayerRules.isDependencyAllowed(layer, Layer.CONTROLLER) &&
                DependencyQueries.violatesLayerDependency(
                    psiClass,
                    layer,
                    Layer.CONTROLLER,
                )
            ) {
                holder.registerProblem(
                    psiClass.nameIdentifier ?: return,
                    msg("Services must not depend on controllers"),
                    DeleteElementFix(),
                )
            }
        }
    }
}
