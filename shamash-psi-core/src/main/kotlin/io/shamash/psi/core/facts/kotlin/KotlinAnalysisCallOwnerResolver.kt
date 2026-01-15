/*
 * Copyright © 2025-2026 | Shamash
 *
 * Shamash is a JVM architecture enforcement tool that helps teams
 * define, validate, and continuously enforce architectural boundaries.
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
package io.shamash.psi.core.facts.kotlin

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtPsiUtil

/**
 * Kotlin Analysis API "second pass" resolver.
 *
 * When UAST resolve() fails for Kotlin calls in some IDE states,
 * use Analysis API to resolve the call and return the owning class FQN.
 */
object KotlinAnalysisCallOwnerResolver {
    fun resolveOwnerClassFqn(expr: KtExpression): String? {
        ProgressManager.checkCanceled()

        val callExpr = unwrapToCallExpression(expr) ?: return null

        return try {
            analyze(callExpr) {
                val callInfo = resolveCallCompat(callExpr) ?: return@analyze null

                // Don't touch strongly-typed "symbol" APIs directly (they churn across baselines).
                // Instead:
                // 1) Try to get the "single*CallOrNull" result objects reflectively.
                // 2) Extract a symbol reflectively from those result objects (or from callInfo itself).
                val symbol =
                    listOfNotNull(
                        invokeNoArg(callInfo, "singleFunctionCallOrNull"),
                        invokeNoArg(callInfo, "singleConstructorCallOrNull"),
                        invokeNoArg(callInfo, "singleCallOrNull"),
                    ).asSequence()
                        .mapNotNull { call -> extractSymbolReflectively(call) }
                        .firstOrNull()
                        ?: extractSymbolReflectively(callInfo) // last-resort: sometimes symbol sits higher
                        ?: return@analyze null

                ownerFqnFromSymbol(symbol)
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun unwrapToCallExpression(expr: KtExpression): KtCallExpression? {
        val unwrapped = KtPsiUtil.deparenthesize(expr) ?: expr
        val direct = unwrapped as? KtCallExpression
        if (direct != null) return direct

        // For qualified expressions, selector is often the call.
        // Keep it conservative to avoid depending on specific PSI classes.
        return unwrapped.lastChild as? KtCallExpression
    }

    /**
     * resolveCall() is an Analysis API extension available inside analyze { }.
     * We call it reflectively so we don't couple to signature/package churn.
     */
    private fun resolveCallCompat(ktElement: KtElement): Any? {
        // In some baselines, resolveCall is bridged as an instance method.
        try {
            val m = ktElement::class.java.methods.firstOrNull { it.name == "resolveCall" && it.parameterCount == 0 }
            if (m != null) return m.invoke(ktElement)
        } catch (_: Throwable) {
            // ignore
        }

        // Fallback: try generated helper class resolveCall(KtElement)
        return tryInvokeStaticResolveCall(ktElement)
    }

    private fun tryInvokeStaticResolveCall(ktElement: KtElement): Any? {
        val candidates =
            listOf(
                "org.jetbrains.kotlin.analysis.api.resolution.KtResolveCallKt",
                "org.jetbrains.kotlin.analysis.api.resolution.ResolveCallKt",
            )

        for (fqcn in candidates) {
            try {
                val cls = Class.forName(fqcn)
                val m = cls.methods.firstOrNull { it.name == "resolveCall" && it.parameterCount == 1 }
                if (m != null) return m.invoke(null, ktElement)
            } catch (_: Throwable) {
                // ignore and keep trying
            }
        }
        return null
    }

    private fun invokeNoArg(
        receiver: Any,
        methodName: String,
    ): Any? =
        try {
            val m = receiver::class.java.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
            m?.invoke(receiver)
        } catch (_: Throwable) {
            null
        }

    private fun extractSymbolReflectively(call: Any): Any? {
        // try call.symbol (getter form)
        try {
            val m = call::class.java.methods.firstOrNull { it.name == "getSymbol" && it.parameterCount == 0 }
            val v = m?.invoke(call)
            if (v != null) return v
        } catch (_: Throwable) {
            // ignore
        }

        // try call.partiallyAppliedSymbol.symbol
        try {
            val m = call::class.java.methods.firstOrNull { it.name == "getPartiallyAppliedSymbol" && it.parameterCount == 0 }
            val pas = m?.invoke(call) ?: return null
            val m2 = pas::class.java.methods.firstOrNull { it.name == "getSymbol" && it.parameterCount == 0 }
            val v = m2?.invoke(pas)
            if (v != null) return v
        } catch (_: Throwable) {
            // ignore
        }

        return null
    }

    private fun ownerFqnFromSymbol(symbol: Any): String? {
        // Try direct containingClassId -> asSingleFqName().asString()
        try {
            val m = symbol::class.java.methods.firstOrNull { it.name == "getContainingClassId" && it.parameterCount == 0 }
            val classId = m?.invoke(symbol)
            classIdToFqn(classId)?.let { return it }
        } catch (_: Throwable) {
            // ignore
        }

        // Try callableIdIfNonLocal.classId -> asSingleFqName().asString()
        try {
            val m = symbol::class.java.methods.firstOrNull { it.name == "getCallableIdIfNonLocal" && it.parameterCount == 0 }
            val callableId = m?.invoke(symbol) ?: return null

            val m2 = callableId::class.java.methods.firstOrNull { it.name == "getClassId" && it.parameterCount == 0 }
            val classId = m2?.invoke(callableId)

            classIdToFqn(classId)?.let { return it }
        } catch (_: Throwable) {
            // ignore
        }

        // Try containingDeclaration -> classId (best-effort)
        try {
            val m = symbol::class.java.methods.firstOrNull { it.name == "getContainingDeclaration" && it.parameterCount == 0 }
            val decl = m?.invoke(symbol) ?: return null
            val m2 = decl::class.java.methods.firstOrNull { it.name == "getClassId" && it.parameterCount == 0 }
            val classId = m2?.invoke(decl)
            classIdToFqn(classId)?.let { return it }
        } catch (_: Throwable) {
            // ignore
        }

        return null
    }

    private fun classIdToFqn(classId: Any?): String? {
        if (classId == null) return null

        // classId.asSingleFqName().asString()
        return try {
            val m1 = classId::class.java.methods.firstOrNull { it.name == "asSingleFqName" && it.parameterCount == 0 }
            val fqName = m1?.invoke(classId) ?: return null
            val m2 = fqName::class.java.methods.firstOrNull { it.name == "asString" && it.parameterCount == 0 }
            m2?.invoke(fqName) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
