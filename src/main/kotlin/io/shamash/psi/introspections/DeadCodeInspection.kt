package io.shamash.psi.introspections

import com.intellij.codeInspection.*
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch
import io.shamash.psi.fixes.DeleteElementFix
import io.shamash.psi.util.EntryPointUtil
import io.shamash.psi.util.PsiMethodUtil


class DeadCodeInspection : AbstractBaseJavaLocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {

        return object : JavaElementVisitor() {

            override fun visitClass(aClass: PsiClass) {
                if (EntryPointUtil.isEntryPoint(aClass)) return
                if (aClass is PsiAnonymousClass) return

                if (ReferencesSearch.search(aClass).findFirst() == null) {
                    holder.registerProblem(
                        aClass.nameIdentifier ?: return,
                        "Shamash: class appears unused",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        DeleteElementFix("Delete unused class")
                    )
                }
            }

            override fun visitMethod(method: PsiMethod) {
                if (EntryPointUtil.isEntryPoint(method)) return
                if (!PsiMethodUtil.isSafeToDelete(method)) return

                if (ReferencesSearch.search(method).findFirst() == null) {
                    holder.registerProblem(
                        method.nameIdentifier ?: return,
                        "Shamash: method appears unused",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        DeleteElementFix("Delete unused method")
                    )
                }
            }
        }
    }
}
