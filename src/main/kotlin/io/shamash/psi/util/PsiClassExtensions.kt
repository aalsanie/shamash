package io.shamash.psi.util

import com.intellij.psi.*

/**
 * Low-level PSI facts about a PsiClass.
 * No rules. No opinions. Just structure.
 */

fun PsiClass.isUtilityClass(): Boolean =
    this.hasModifierProperty(PsiModifier.FINAL) &&
            this.constructors.all { it.hasModifierProperty(PsiModifier.PRIVATE) }

fun PsiClass.hasPrivateMethods(): Boolean =
    this.methods.any { it.hasModifierProperty(PsiModifier.PRIVATE) }

fun PsiClass.publicMethodCount(): Int =
    this.methods.count { it.hasModifierProperty(PsiModifier.PUBLIC) }

fun PsiClass.isController(): Boolean =
    this.annotations.any {
        it.qualifiedName?.endsWith("Controller") == true
    }

fun PsiClass.isService(): Boolean =
    this.annotations.any {
        it.qualifiedName?.endsWith("Service") == true
    }

fun PsiClass.isDao(): Boolean =
    this.annotations.any {
        it.qualifiedName?.endsWith("Repository") == true ||
                it.qualifiedName?.endsWith("Dao") == true
    }

fun PsiClass.packageName(): String =
    (this.containingFile as? PsiJavaFile)?.packageName ?: ""

fun PsiClass.referencedClasses(): List<PsiClass> =
    this.references
        .mapNotNull { it.resolve() }
        .filterIsInstance<PsiClass>()

