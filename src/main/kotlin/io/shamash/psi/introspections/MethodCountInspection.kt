package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.LayerRules

class MethodCountInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {

        return object : JavaElementVisitor() {

            override fun visitClass(aClass: PsiClass) {
                val layer = LayerDetector.detect(aClass)
                val max = LayerRules.maxMethods(layer) ?: return

                val methodCount = aClass.methods.count { !it.isConstructor }
                if (methodCount > max) {
                    holder.registerProblem(
                        aClass.nameIdentifier ?: return,
                        "Shamash: $layer has $methodCount methods (max $max)"
                    )
                }
            }
        }
    }
}
