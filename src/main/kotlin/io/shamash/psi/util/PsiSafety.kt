package io.shamash.psi.util

import com.intellij.psi.PsiElement

/**
 * PSI safety helpers.
 */

object PsiSafety {
    inline fun PsiElement.safe(block: () -> Unit) {
        try {
            if (this.isValid) {
                block()
            }
            } catch (_: Throwable) {
            // Intentionally ignored
        }
    }
}
