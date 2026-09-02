/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
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
package io.shamash.asm.core.facts.model

import io.shamash.artifacts.util.PathNormalizer

/** Callers provide the bytecode origin; the extractor adds source file/line data when debug info exists. */
data class SourceLocation(
    val originKind: OriginKind,
    /** Normalized class-file path for directories, or jar path for jar entries. */
    val originPath: String,
    /** Present only for jar entries. */
    val containerPath: String? = null,
    /** Present only for jar entries. */
    val entryPath: String? = null,
    /** From ClassFile 'SourceFile' attribute if available. */
    val sourceFile: String? = null,
    /** Best-effort line number (from LineNumberTable) if available. */
    val line: Int? = null,
) {
    fun normalized(): SourceLocation =
        copy(
            originPath = PathNormalizer.normalize(originPath),
            containerPath = containerPath?.let { PathNormalizer.normalize(it) },
        )

    fun withSourceFile(sourceFile: String?): SourceLocation = copy(sourceFile = sourceFile)

    fun withLine(line: Int?): SourceLocation = copy(line = line)
}

enum class OriginKind {
    DIR_CLASS,
    JAR_ENTRY,
}
