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
package io.shamash.psi.facts

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import io.shamash.psi.facts.model.v1.ClassFact
import io.shamash.psi.facts.model.v1.DependencyFact
import io.shamash.psi.facts.model.v1.DependencyKind
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.facts.model.v1.FieldFact
import io.shamash.psi.facts.model.v1.MethodFact
import io.shamash.psi.facts.model.v1.Visibility

object FactExtractor {
    private data class CacheEntry(
        val stamp: Long,
        val facts: FactsIndex,
    )

    private val CACHE_KEY: Key<CacheEntry> = Key.create("shamash.psi.facts.cache")

    /**
     * Production caching:
     * - Reuses facts if PsiFile modification stamp is unchanged.
     * - Safe for IDE inspection mode.
     */
    fun extract(file: PsiFile): FactsIndex {
        val stamp = file.modificationStamp
        file.getUserData(CACHE_KEY)?.let { cached ->
            if (cached.stamp == stamp) return cached.facts
        }

        val computed = computeFacts(file)

        file.putUserData(CACHE_KEY, CacheEntry(stamp, computed))
        return computed
    }

    private fun computeFacts(file: PsiFile): FactsIndex {
        val classes = mutableListOf<ClassFact>()
        val methods = mutableListOf<MethodFact>()
        val fields = mutableListOf<FieldFact>()
        val deps = mutableListOf<DependencyFact>()

        val filePath = file.virtualFile?.path ?: file.name

        file.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitClass(aClass: PsiClass) {
                    super.visitClass(aClass)

                    val fqn = aClass.qualifiedName ?: return
                    val pkg = fqn.substringBeforeLast('.', "")
                    val annotations =
                        aClass.modifierList
                            ?.annotations
                            ?.mapNotNull { it.qualifiedName }
                            ?.toSet()
                            ?: emptySet()

                    val superFqn = aClass.superClass?.qualifiedName
                    val ifaces = aClass.interfaces.mapNotNull { it.qualifiedName }.toSet()
                    val hasMain =
                        aClass.methods.any { m ->
                            m.name == "main" &&
                                m.hasModifierProperty(PsiModifier.STATIC) &&
                                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                                m.parameterList.parametersCount == 1
                        }

                    classes +=
                        ClassFact(
                            fqName = fqn,
                            packageName = pkg,
                            simpleName = aClass.name ?: fqn.substringAfterLast('.'),
                            annotationsFqns = annotations,
                            superClassFqn = superFqn,
                            interfacesFqns = ifaces,
                            hasMainMethod = hasMain,
                            filePath = filePath,
                            textRange = safeRange(aClass),
                        )

                    // structural deps
                    // extends / implements / annotations
                    superFqn?.let { addDep(deps, fqn, it, DependencyKind.EXTENDS, filePath, safeRange(aClass), "extends") }
                    ifaces.forEach { addDep(deps, fqn, it, DependencyKind.IMPLEMENTS, filePath, safeRange(aClass), "implements") }
                    annotations.forEach {
                        addDep(
                            deps,
                            fqn,
                            it,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            safeRange(aClass),
                            "class-annotation",
                        )
                    }
                }

                override fun visitField(field: PsiField) {
                    super.visitField(field)

                    val cls = field.containingClass?.qualifiedName ?: return
                    val anns =
                        field.modifierList
                            ?.annotations
                            ?.mapNotNull { it.qualifiedName }
                            ?.toSet() ?: emptySet()

                    // Facts
                    // keep complete (even primitives)
                    fields +=
                        FieldFact(
                            containingClassFqn = cls,
                            name = field.name,
                            typeFqn = normalizeFactType(field.type),
                            visibility = visibilityOf(field),
                            isStatic = field.hasModifierProperty(PsiModifier.STATIC),
                            isFinal = field.hasModifierProperty(PsiModifier.FINAL),
                            annotationsFqns = anns,
                            filePath = filePath,
                            textRange = safeRange(field),
                        )

                    // dependencies
                    // exclude primitives/unresolved etc.
                    normalizeDepType(field.type)?.let { typeFqn ->
                        addDep(deps, cls, typeFqn, DependencyKind.FIELD_TYPE, filePath, safeRange(field), "field:${field.name}")
                    }
                    anns.forEach {
                        addDep(deps, cls, it, DependencyKind.ANNOTATION_TYPE, filePath, safeRange(field), "field-annotation:${field.name}")
                    }
                }

                override fun visitMethod(method: PsiMethod) {
                    super.visitMethod(method)

                    val cls = method.containingClass?.qualifiedName ?: return
                    val anns =
                        method.modifierList.annotations
                            .mapNotNull { it.qualifiedName }
                            .toSet()

                    val paramsDep = method.parameterList.parameters.mapNotNull { normalizeDepType(it.type) }
                    val retDep = method.returnType?.let { normalizeDepType(it) }

                    methods +=
                        MethodFact(
                            containingClassFqn = cls,
                            name = method.name,
                            signature = buildSignature(method),
                            visibility = visibilityOf(method),
                            isStatic = method.hasModifierProperty(PsiModifier.STATIC),
                            isAbstract = method.hasModifierProperty(PsiModifier.ABSTRACT),
                            isConstructor = method.isConstructor,
                            returnTypeFqn = retDep,
                            parameterTypeFqns = paramsDep,
                            annotationsFqns = anns,
                            filePath = filePath,
                            textRange = safeRange(method),
                        )

                    // Structural deps:
                    // params return + annotations
                    paramsDep.forEach {
                        addDep(
                            deps,
                            cls,
                            it,
                            DependencyKind.PARAMETER_TYPE,
                            filePath,
                            safeRange(method),
                            "param:${method.name}",
                        )
                    }
                    retDep?.let { addDep(deps, cls, it, DependencyKind.RETURN_TYPE, filePath, safeRange(method), "return:${method.name}") }
                    anns.forEach {
                        addDep(
                            deps,
                            cls,
                            it,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            safeRange(method),
                            "method-annotation:${method.name}",
                        )
                    }

                    // Call deps
                    // method calls + constructor calls
                    val body = method.body ?: return
                    body.accept(
                        object : JavaRecursiveElementVisitor() {
                            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                                super.visitMethodCallExpression(expression)

                                val target = expression.resolveMethod()
                                val owner = target?.containingClass?.qualifiedName
                                if (owner != null) {
                                    addDep(
                                        deps,
                                        cls,
                                        owner,
                                        DependencyKind.METHOD_CALL,
                                        filePath,
                                        safeRange(expression),
                                        "call:${target.name}",
                                    )
                                }
                            }

                            override fun visitNewExpression(expression: PsiNewExpression) {
                                super.visitNewExpression(expression)

                                val resolved = expression.classReference?.resolve() as? PsiClass
                                val ctorClass = resolved?.qualifiedName
                                if (ctorClass != null) {
                                    addDep(deps, cls, ctorClass, DependencyKind.METHOD_CALL, filePath, safeRange(expression), "new")
                                }
                            }
                        },
                    )
                }
            },
        )

        return FactsIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            dependencies = deps.distinct(),
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private fun safeRange(e: PsiElement): TextRange? =
        try {
            e.textRange
        } catch (_: Throwable) {
            null
        }

    /**
     * Facts type: always returns a string/primitives allowed
     */
    private fun normalizeFactType(t: PsiType): String = t.canonicalText

    /**
     * Dependency type: returns null for primitives / void / unresolved.
     * Strips generics + one level of array suffix.
     */
    private fun normalizeDepType(t: PsiType): String? {
        if (t is PsiPrimitiveType) return null
        val text = t.canonicalText
        if (text == "void") return null

        val base = text.substringBefore('<').removeSuffix("[]")
        // prevents <T> generics.
        return if (base.contains('.')) base else null
    }

    private fun buildSignature(m: PsiMethod): String {
        val params = m.parameterList.parameters.joinToString(",") { it.type.presentableText }
        return if (m.isConstructor) {
            "${m.name}($params)"
        } else {
            "${m.name}($params):${m.returnType?.presentableText ?: "void"}"
        }
    }

    private fun visibilityOf(m: PsiMethod): Visibility =
        when {
            m.hasModifierProperty(PsiModifier.PUBLIC) -> Visibility.PUBLIC
            m.hasModifierProperty(PsiModifier.PROTECTED) -> Visibility.PROTECTED
            m.hasModifierProperty(PsiModifier.PRIVATE) -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    private fun visibilityOf(f: PsiField): Visibility =
        when {
            f.hasModifierProperty(PsiModifier.PUBLIC) -> Visibility.PUBLIC
            f.hasModifierProperty(PsiModifier.PROTECTED) -> Visibility.PROTECTED
            f.hasModifierProperty(PsiModifier.PRIVATE) -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    private fun addDep(
        out: MutableList<DependencyFact>,
        from: String,
        to: String,
        kind: DependencyKind,
        filePath: String,
        range: TextRange?,
        detail: String?,
    ) {
        if (from == to) return
        if (to.isBlank()) return

        out +=
            DependencyFact(
                fromClassFqn = from,
                toTypeFqn = to,
                kind = kind,
                filePath = filePath,
                textRange = range,
                detail = detail,
            )
    }
}
