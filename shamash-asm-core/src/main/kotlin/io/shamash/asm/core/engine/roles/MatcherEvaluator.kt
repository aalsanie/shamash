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
package io.shamash.asm.core.engine.roles

import io.shamash.asm.core.config.schema.v1.model.Matcher
import io.shamash.asm.core.facts.model.ClassFact

/**
 * Compiles and evaluates role matchers, used by RoleClassifier.
 */
internal object MatcherEvaluator {
    fun compile(matcher: Matcher): CompiledMatcher =
        when (matcher) {
            is Matcher.AnyOf -> CompiledMatcher.AnyOf(matcher.anyOf.map { compile(it) })
            is Matcher.AllOf -> CompiledMatcher.AllOf(matcher.allOf.map { compile(it) })
            is Matcher.Not -> CompiledMatcher.Not(compile(matcher.not))

            is Matcher.PackageRegex -> {
                val rx = compileRegexOrThrow(matcher.packageRegex, "Matcher.PackageRegex")
                CompiledMatcher.PackageRegex(rx)
            }

            is Matcher.PackageContainsSegment ->
                CompiledMatcher.PackageContainsSegment(matcher.packageContainsSegment)

            is Matcher.ClassNameEndsWith ->
                CompiledMatcher.ClassNameEndsWith(matcher.classNameEndsWith)

            is Matcher.Annotation ->
                CompiledMatcher.Annotation(matcher.annotation)

            is Matcher.AnnotationPrefix ->
                CompiledMatcher.AnnotationPrefix(matcher.annotationPrefix)
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
