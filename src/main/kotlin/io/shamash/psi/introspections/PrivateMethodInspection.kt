package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import io.shamash.psi.architecture.LayerDetector
import io.shamash.psi.architecture.Layer

class PrivateMethodInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {

        return object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                if (!method.hasModifierProperty(PsiModifier.PRIVATE)) return

                val psiClass = method.containingClass ?: return
                val layer = LayerDetector.detect(psiClass)

                if (layer in listOf(Layer.CONTROLLER, Layer.SERVICE, Layer.DAO)) {
                    holder.registerProblem(
                        method.nameIdentifier ?: return,
                        "Shamash: private methods not allowed in $layer"
                    )
                }
            }
        }
    }
}
