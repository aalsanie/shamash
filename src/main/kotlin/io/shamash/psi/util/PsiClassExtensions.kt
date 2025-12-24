package io.shamash.psi.util

import com.intellij.psi.*
import io.shamash.psi.util.PsiUtil.isFinalWithPrivateConstructor

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

fun PsiClass.isConcreteClass(): Boolean =
    !isInterface && !isEnum && name != null

fun PsiClass.isUtilityCandidate(): Boolean =
    name!!.endsWith("Util") ||
            name!!.endsWith("Helper") ||
            isFinalWithPrivateConstructor()

fun PsiClass.hasOnlyStaticFields(): Boolean =
    fields.all { it.hasModifierProperty(PsiModifier.STATIC) }

fun PsiClass.hasOnlyStaticMethods(): Boolean =
    methods
        .filterNot { it.isConstructor }
        .all { it.hasModifierProperty(PsiModifier.STATIC) }

fun PsiClass.isFinalWithPrivateConstructor(): Boolean {
    if (!hasModifierProperty(PsiModifier.FINAL)) return false

    val constructors = constructors
    if (constructors.isEmpty()) return false

    return constructors.all {
        it.hasModifierProperty(PsiModifier.PRIVATE)
    }
}