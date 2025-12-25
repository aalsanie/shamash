package io.shamash.psi.fixes

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.safeDelete.SafeDeleteHandler

/**
 * Safely deletes the PSI element that introduces a violation.
 *
 * Uses IntelliJ Safe Delete (undoable + reference-aware).
 * Target selection is conservative:
 *  - delete import statement if the violation is inside an import
 *  - otherwise delete the nearest structural owner (parameter/field/method/class)
 */
class DeleteElementFix(
    private val label: String = "Delete element"
) : LocalQuickFix {

    override fun getName(): String = label

    override fun getFamilyName(): String = "Shamash fixes"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val leaf = descriptor.psiElement ?: return
        val target = findTargetToDelete(leaf) ?: return
        if (!target.isValid) return

        SafeDeleteHandler.invoke(project, arrayOf(target), /* showDialog = */ true)
    }

    private fun findTargetToDelete(leaf: PsiElement): PsiElement? {
        PsiTreeUtil.getParentOfType(leaf, PsiImportStatementBase::class.java, false)
            ?.let { return it }

        return PsiTreeUtil.getParentOfType(
            leaf,
            com.intellij.psi.PsiParameter::class.java,
            com.intellij.psi.PsiField::class.java,
            com.intellij.psi.PsiMethod::class.java,
            com.intellij.psi.PsiClass::class.java
        ) ?: leaf
    }
}
