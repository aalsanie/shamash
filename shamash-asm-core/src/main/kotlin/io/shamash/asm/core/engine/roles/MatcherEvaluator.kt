/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.engine.roles

import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.facts.model.ClassFact

internal object MatcherEvaluator {
    fun compile(matcher: Matcher): CompiledMatcher =
        when (matcher) {
            is Matcher.AnyOf -> {
                CompiledMatcher.AnyOf(matcher.anyOf.map { compile(it) })
            }

            is Matcher.AllOf -> {
                CompiledMatcher.AllOf(matcher.allOf.map { compile(it) })
            }

            is Matcher.Not -> {
                CompiledMatcher.Not(compile(matcher.not))
            }

            is Matcher.PackageRegex -> {
                val rx = compileRegexOrThrow(matcher.packageRegex, "Matcher.PackageRegex")
                CompiledMatcher.PackageRegex(rx)
            }

            is Matcher.PackageContainsSegment -> {
                CompiledMatcher.PackageContainsSegment(matcher.packageContainsSegment)
            }

            is Matcher.ClassNameEndsWith -> {
                CompiledMatcher.ClassNameEndsWith(matcher.classNameEndsWith)
            }

            is Matcher.Annotation -> {
                CompiledMatcher.Annotation(matcher.annotation)
            }

            is Matcher.AnnotationPrefix -> {
                CompiledMatcher.AnnotationPrefix(matcher.annotationPrefix)
            }
        }

    sealed interface CompiledMatcher {
        fun matches(c: ClassFact): Boolean

        data class AnyOf(
            val anyOf: List<CompiledMatcher>,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean = anyOf.any { it.matches(c) }
        }

        data class AllOf(
            val allOf: List<CompiledMatcher>,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean = allOf.all { it.matches(c) }
        }

        data class Not(
            val not: CompiledMatcher,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean = !not.matches(c)
        }

        data class PackageRegex(
            val regex: Regex,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean =
                // packageName can be empty for default package; regex decides.
                regex.containsMatchIn(c.packageName)
        }

        data class PackageContainsSegment(
            val seg: String,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean {
                val s = seg.trim()
                if (s.isEmpty()) return false
                val pkg = c.packageName
                if (pkg.isEmpty()) return false
                // exact segment match only
                return pkg.split('.').any { it == s }
            }
        }

        data class ClassNameEndsWith(
            val suffix: String,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean {
                val suf = suffix.trim()
                if (suf.isEmpty()) return false
                return c.simpleName.endsWith(suf)
            }
        }

        data class Annotation(
            val annotationFqn: String,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean {
                val a = annotationFqn.trim()
                if (a.isEmpty()) return false
                return a in c.annotationsFqns
            }
        }

        data class AnnotationPrefix(
            val prefix: String,
        ) : CompiledMatcher {
            override fun matches(c: ClassFact): Boolean {
                val p = prefix.trim()
                if (p.isEmpty()) return false
                return c.annotationsFqns.any { it.startsWith(p) }
            }
        }
    }

    private fun compileRegexOrThrow(
        pattern: String,
        ctx: String,
    ): Regex {
        val p = pattern.trim()
        require(p.isNotEmpty()) { "$ctx: regex must not be blank" }
        return try {
            Regex(p)
        } catch (t: Throwable) {
            throw IllegalArgumentException("$ctx: invalid regex '$p': ${t.message}", t)
        }
    }
}
