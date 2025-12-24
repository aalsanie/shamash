package io.shamash.psi.introspections

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import io.shamash.psi.util.safe

abstract class ShamashInspectionBase : AbstractBaseJavaLocalInspectionTool() {

    final override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : JavaElementVisitor() {
        override fun visitClass(aClass: PsiClass) {
            aClass.safe {
                inspectClass(aClass, holder)
            }
        }
    }

    protected abstract fun inspectClass(
        clazz: PsiClass,
        holder: ProblemsHolder
    )
}
