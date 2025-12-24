package io.shamash.psi.architecture

import io.shamash.psi.util.EntryPointUtil
import io.shamash.psi.util.PsiMethodUtil
import com.intellij.psi.*
import com.intellij.psi.search.searches.ReferencesSearch

object DeadCodeRules {

    fun isUnusedClass(psiClass: PsiClass): Boolean {
        if (EntryPointUtil.isEntryPoint(psiClass)) return false
        if (psiClass is PsiAnonymousClass) return false
        return ReferencesSearch.search(psiClass).findFirst() == null
    }

    fun isUnusedMethod(method: PsiMethod): Boolean {
        if (EntryPointUtil.isEntryPoint(method)) return false
        if (!PsiMethodUtil.isSafeToDelete(method)) return false
        return ReferencesSearch.search(method).findFirst() == null
    }
}
