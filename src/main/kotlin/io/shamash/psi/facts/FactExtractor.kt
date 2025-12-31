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

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
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
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import io.shamash.psi.facts.kotlin.KotlinAnalysisCallOwnerResolver
import io.shamash.psi.facts.model.v1.ClassFact
import io.shamash.psi.facts.model.v1.DependencyFact
import io.shamash.psi.facts.model.v1.DependencyKind
import io.shamash.psi.facts.model.v1.FactsIndex
import io.shamash.psi.facts.model.v1.FieldFact
import io.shamash.psi.facts.model.v1.MethodFact
import io.shamash.psi.facts.model.v1.Visibility
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UField
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Kotlin+Java facts extraction.
 *
 * - Uses UAST when available (covers Kotlin bodies/calls/properties and Java too).
 * - Falls back to Java PSI when UAST isn't available.
 *
 * IDE-safe:
 * - cancellation aware
 * - deterministic output (dedupe dependencies)
 */
object FactExtractor {
    private data class CacheEntry(
        val stamp: Long,
        val facts: FactsIndex,
    )

    private val CACHE_KEY: Key<CacheEntry> = Key.create("shamash.psi.facts.cache.v1")

    fun extract(file: PsiFile): FactsIndex {
        ProgressManager.checkCanceled()

        val stamp = file.modificationStamp
        val cached = file.getUserData(CACHE_KEY)
        if (cached != null && cached.stamp == stamp) return cached.facts

        val computed =
            try {
                computeFacts(file)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (_: Throwable) {
                emptyFacts()
            }

        file.putUserData(CACHE_KEY, CacheEntry(stamp, computed))
        return computed
    }

    private fun computeFacts(file: PsiFile): FactsIndex {
        ProgressManager.checkCanceled()

        val filePath = (file.virtualFile?.path ?: file.name).replace('\\', '/')
        val uFile = file.toUElementOfType<UFile>()

        return if (uFile != null) extractFromUast(uFile, filePath) else extractFromJavaPsi(file, filePath)
    }

    private fun extractFromUast(
        uFile: UFile,
        filePath: String,
    ): FactsIndex {
        val classes = mutableListOf<ClassFact>()
        val methods = mutableListOf<MethodFact>()
        val fields = mutableListOf<FieldFact>()
        val deps = mutableListOf<DependencyFact>()

        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitClass(node: UClass): Boolean {
                    ProgressManager.checkCanceled()

                    val fqn = node.qualifiedName ?: return false
                    val pkg = fqn.substringBeforeLast('.', "")
                    val annotations = node.uAnnotations.mapNotNull { it.qualifiedName }.toSet()

                    val psiClass = node.javaPsi
                    val superFqn = psiClass.superClass?.qualifiedName
                    val ifaces = psiClass.interfaces.mapNotNull { it.qualifiedName }.toSet()

                    val hasMain =
                        psiClass.methods.any { m ->
                            m.name == "main" &&
                                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                                m.hasModifierProperty(PsiModifier.STATIC) &&
                                m.parameterList.parametersCount == 1
                        }

                    val range = safeRangeU(node)

                    classes +=
                        ClassFact(
                            fqName = fqn,
                            packageName = pkg,
                            simpleName = node.name ?: fqn.substringAfterLast('.'),
                            annotationsFqns = annotations,
                            superClassFqn = superFqn,
                            interfacesFqns = ifaces,
                            hasMainMethod = hasMain,
                            filePath = filePath,
                            textRange = range,
                        )

                    if (superFqn != null) {
                        addDep(
                            deps,
                            fqn,
                            superFqn,
                            DependencyKind.EXTENDS,
                            filePath,
                            range,
                            "extends",
                        )
                    }
                    for (i in ifaces) {
                        addDep(
                            deps,
                            fqn,
                            i,
                            DependencyKind.IMPLEMENTS,
                            filePath,
                            range,
                            "implements",
                        )
                    }
                    for (a in annotations) {
                        addDep(
                            deps,
                            fqn,
                            a,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            range,
                            "class-annotation",
                        )
                    }

                    return false
                }

                override fun visitMethod(node: UMethod): Boolean {
                    ProgressManager.checkCanceled()

                    val fromClassFqn = containingClassFqnOfU(node) ?: return false

                    val anns = node.uAnnotations.mapNotNull { it.qualifiedName }.toSet()
                    val paramDepTypes = node.uastParameters.mapNotNull { p -> normalizeDepType(p.type) }
                    val retDepType = node.returnType?.let { normalizeDepType(it) }

                    val psiMethod: PsiMethod? = node.javaPsi
                    val range = safeRangeU(node)

                    methods +=
                        MethodFact(
                            containingClassFqn = fromClassFqn,
                            name = node.name,
                            signature = buildSignature(node),
                            visibility = visibilityOf(psiMethod as PsiElement?),
                            isStatic = psiMethod?.hasModifierProperty(PsiModifier.STATIC) ?: false,
                            isAbstract = psiMethod?.hasModifierProperty(PsiModifier.ABSTRACT) ?: false,
                            isConstructor = node.isConstructor,
                            returnTypeFqn = retDepType,
                            parameterTypeFqns = paramDepTypes,
                            annotationsFqns = anns,
                            filePath = filePath,
                            textRange = range,
                        )

                    for (p in paramDepTypes) {
                        addDep(
                            deps,
                            fromClassFqn,
                            p,
                            DependencyKind.PARAMETER_TYPE,
                            filePath,
                            range,
                            "param:${node.name}",
                        )
                    }
                    if (retDepType != null) {
                        addDep(
                            deps,
                            fromClassFqn,
                            retDepType,
                            DependencyKind.RETURN_TYPE,
                            filePath,
                            range,
                            "return:${node.name}",
                        )
                    }
                    for (a in anns) {
                        addDep(
                            deps,
                            fromClassFqn,
                            a,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            range,
                            "method-annotation:${node.name}",
                        )
                    }

                    return false
                }

                override fun visitField(node: UField): Boolean {
                    ProgressManager.checkCanceled()

                    val fromClassFqn = containingClassFqnOfU(node) ?: return false

                    val anns = node.uAnnotations.mapNotNull { it.qualifiedName }.toSet()
                    val range = safeRangeU(node)

                    val psiOwner = node.javaPsi as? PsiModifierListOwner
                    val isStatic = psiOwner?.hasModifierProperty(PsiModifier.STATIC) ?: false
                    val isFinal = psiOwner?.hasModifierProperty(PsiModifier.FINAL) ?: false

                    fields +=
                        FieldFact(
                            containingClassFqn = fromClassFqn,
                            name = node.name,
                            typeFqn = normalizeFactType(node.type),
                            visibility = visibilityOf(node.javaPsi),
                            isStatic = isStatic,
                            isFinal = isFinal,
                            annotationsFqns = anns,
                            filePath = filePath,
                            textRange = range,
                        )

                    normalizeDepType(node.type)?.let { typeFqn ->
                        addDep(
                            deps,
                            fromClassFqn,
                            typeFqn,
                            DependencyKind.FIELD_TYPE,
                            filePath,
                            range,
                            "field:${node.name}",
                        )
                    }
                    for (a in anns) {
                        addDep(
                            deps,
                            fromClassFqn,
                            a,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            range,
                            "field-annotation:${node.name}",
                        )
                    }

                    return false
                }

                override fun visitCallExpression(node: UCallExpression): Boolean {
                    ProgressManager.checkCanceled()

                    val fromClassFqn = containingClassFqnOfU(node) ?: return false
                    val range = safeRangeU(node)

                    when (node.kind) {
                        UastCallKind.METHOD_CALL -> {
                            val resolved = node.resolve()
                            val owner = resolved?.containingClass?.qualifiedName

                            if (owner != null) {
                                addDep(
                                    deps,
                                    fromClassFqn,
                                    owner,
                                    DependencyKind.METHOD_CALL,
                                    filePath,
                                    range,
                                    "call:${resolved.name}",
                                )
                            } else {
                                // Second pass for Kotlin when UAST resolve() returns null.
                                val ktExpr = node.sourcePsi as? KtExpression
                                if (ktExpr != null) {
                                    val kOwner = KotlinAnalysisCallOwnerResolver.resolveOwnerClassFqn(ktExpr)
                                    if (kOwner != null) {
                                        addDep(
                                            deps,
                                            fromClassFqn,
                                            kOwner,
                                            DependencyKind.METHOD_CALL,
                                            filePath,
                                            range,
                                            "call:kotlin-analysis",
                                        )
                                    }
                                }
                            }
                        }
                        UastCallKind.CONSTRUCTOR_CALL -> {
                            val resolved = node.resolve()
                            val owner = resolved?.containingClass?.qualifiedName
                            if (owner != null) {
                                addDep(
                                    deps,
                                    fromClassFqn,
                                    owner,
                                    DependencyKind.METHOD_CALL,
                                    filePath,
                                    range,
                                    "new",
                                )
                            } else {
                                val ktExpr = node.sourcePsi as? KtExpression
                                if (ktExpr != null) {
                                    val kOwner = KotlinAnalysisCallOwnerResolver.resolveOwnerClassFqn(ktExpr)
                                    if (kOwner != null) {
                                        addDep(
                                            deps,
                                            fromClassFqn,
                                            kOwner,
                                            DependencyKind.METHOD_CALL,
                                            filePath,
                                            range,
                                            "new:kotlin-analysis",
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            // ignore
                        }
                    }

                    return false
                }
            },
        )

        return FactsIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            dependencies = deps.distinctBy { depKey(it) },
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private fun extractFromJavaPsi(
        file: PsiFile,
        filePath: String,
    ): FactsIndex {
        val classes = mutableListOf<ClassFact>()
        val methods = mutableListOf<MethodFact>()
        val fields = mutableListOf<FieldFact>()
        val deps = mutableListOf<DependencyFact>()

        file.accept(
            object : JavaRecursiveElementVisitor() {
                override fun visitClass(aClass: PsiClass) {
                    ProgressManager.checkCanceled()
                    super.visitClass(aClass)

                    val fqn = aClass.qualifiedName ?: return
                    val pkg = fqn.substringBeforeLast('.', "")
                    val annotations =
                        aClass.modifierList
                            ?.annotations
                            ?.mapNotNull { it.qualifiedName }
                            ?.toSet() ?: emptySet()

                    val superFqn = aClass.superClass?.qualifiedName
                    val ifaces = aClass.interfaces.mapNotNull { it.qualifiedName }.toSet()

                    val hasMain =
                        aClass.methods.any { m ->
                            m.name == "main" &&
                                m.hasModifierProperty(PsiModifier.STATIC) &&
                                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                                m.parameterList.parametersCount == 1
                        }

                    val range = safeRangePsi(aClass)

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
                            textRange = range,
                        )

                    superFqn?.let {
                        addDep(
                            deps,
                            fqn,
                            it,
                            DependencyKind.EXTENDS,
                            filePath,
                            range,
                            "extends",
                        )
                    }
                    for (i in ifaces) {
                        addDep(
                            deps,
                            fqn,
                            i,
                            DependencyKind.IMPLEMENTS,
                            filePath,
                            range,
                            "implements",
                        )
                    }
                    for (a in annotations) {
                        addDep(
                            deps,
                            fqn,
                            a,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            range,
                            "class-annotation",
                        )
                    }
                }

                override fun visitField(field: PsiField) {
                    ProgressManager.checkCanceled()
                    super.visitField(field)

                    val cls = field.containingClass?.qualifiedName ?: return
                    val anns =
                        field.modifierList
                            ?.annotations
                            ?.mapNotNull { it.qualifiedName }
                            ?.toSet() ?: emptySet()
                    val range = safeRangePsi(field)

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
                            textRange = range,
                        )

                    normalizeDepType(field.type)?.let { typeFqn ->
                        addDep(
                            deps,
                            cls,
                            typeFqn,
                            DependencyKind.FIELD_TYPE,
                            filePath,
                            range,
                            "field:${field.name}",
                        )
                    }
                    for (a in anns) {
                        addDep(
                            deps,
                            cls,
                            a,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            range,
                            "field-annotation:${field.name}",
                        )
                    }
                }

                override fun visitMethod(method: PsiMethod) {
                    ProgressManager.checkCanceled()
                    super.visitMethod(method)

                    val cls = method.containingClass?.qualifiedName ?: return
                    val anns =
                        method.modifierList.annotations
                            .mapNotNull { it.qualifiedName }
                            .toSet()
                    val range = safeRangePsi(method)

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
                            textRange = range,
                        )

                    for (p in paramsDep) {
                        addDep(
                            deps,
                            cls,
                            p,
                            DependencyKind.PARAMETER_TYPE,
                            filePath,
                            range,
                            "param:${method.name}",
                        )
                    }
                    if (retDep != null) {
                        addDep(
                            deps,
                            cls,
                            retDep,
                            DependencyKind.RETURN_TYPE,
                            filePath,
                            range,
                            "return:${method.name}",
                        )
                    }
                    for (a in anns) {
                        addDep(
                            deps,
                            cls,
                            a,
                            DependencyKind.ANNOTATION_TYPE,
                            filePath,
                            range,
                            "method-annotation:${method.name}",
                        )
                    }

                    val body = method.body ?: return
                    body.accept(
                        object : JavaRecursiveElementVisitor() {
                            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                                ProgressManager.checkCanceled()
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
                                        safeRangePsi(expression),
                                        "call:${target.name}",
                                    )
                                }
                            }

                            override fun visitNewExpression(expression: PsiNewExpression) {
                                ProgressManager.checkCanceled()
                                super.visitNewExpression(expression)

                                val resolved = expression.classReference?.resolve() as? PsiClass
                                val ctorClass = resolved?.qualifiedName
                                if (ctorClass != null) {
                                    addDep(
                                        deps,
                                        cls,
                                        ctorClass,
                                        DependencyKind.METHOD_CALL,
                                        filePath,
                                        safeRangePsi(expression),
                                        "new",
                                    )
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
            dependencies = deps.distinctBy { depKey(it) },
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    private fun safeRangePsi(e: PsiElement): TextRange? =
        try {
            e.textRange
        } catch (_: Throwable) {
            null
        }

    private fun safeRangeU(u: UElement): TextRange? {
        val psi = u.sourcePsi ?: return null
        return safeRangePsi(psi)
    }

    private fun normalizeFactType(t: PsiType): String = t.canonicalText

    private fun normalizeDepType(t: PsiType): String? {
        if (t is PsiPrimitiveType) return null
        val text = t.canonicalText
        if (text == "void") return null
        val base = text.substringBefore('<').removeSuffix("[]")
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

    private fun buildSignature(m: UMethod): String {
        val params = m.uastParameters.joinToString(",") { it.type.presentableText }
        val ret = m.returnType?.presentableText ?: "void"
        return if (m.isConstructor) "${m.name}($params)" else "${m.name}($params):$ret"
    }

    private fun visibilityOf(e: PsiElement?): Visibility {
        if (e == null) return Visibility.PACKAGE_PRIVATE
        val owner = e as? PsiModifierListOwner ?: return Visibility.PACKAGE_PRIVATE
        return when {
            owner.hasModifierProperty(PsiModifier.PUBLIC) -> Visibility.PUBLIC
            owner.hasModifierProperty(PsiModifier.PROTECTED) -> Visibility.PROTECTED
            owner.hasModifierProperty(PsiModifier.PRIVATE) -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
    }

    private fun visibilityOf(m: PsiMethod): Visibility = visibilityOf(m as PsiElement)

    private fun visibilityOf(f: PsiField): Visibility = visibilityOf(f as PsiElement)

    private fun containingClassFqnOfU(u: UElement): String? {
        val psi = u.sourcePsi ?: return null

        // 1) best: PSI parent class (works for Kotlin light elements too)
        val psiClass = PsiTreeUtil.getParentOfType(psi, PsiClass::class.java, false)
        val qn = psiClass?.qualifiedName
        if (qn != null) return qn

        // 2) fallback: try UAST conversion on the containing PSI class
        if (psiClass != null) {
            val uClass = psiClass.toUElementOfType<UClass>()
            val uq = uClass?.qualifiedName
            if (uq != null) return uq
        }

        return null
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

    private fun depKey(d: DependencyFact): String {
        val r = d.textRange
        val rangeKey = if (r == null) "" else "${r.startOffset}:${r.endOffset}"
        return "${d.fromClassFqn}|${d.toTypeFqn}|${d.kind.name}|${d.filePath}|${d.detail ?: ""}|$rangeKey"
    }

    private fun emptyFacts(): FactsIndex =
        FactsIndex(
            classes = emptyList(),
            methods = emptyList(),
            fields = emptyList(),
            dependencies = emptyList(),
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
}
