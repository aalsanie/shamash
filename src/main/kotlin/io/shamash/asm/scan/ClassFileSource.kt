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
package io.shamash.asm.scan

import io.shamash.asm.model.AsmOrigin

/**
 * A source of class files.
 *
 * - Directory: module output (e.g., build/classes/java/main)
 * - Jar: dependency jar
 */
sealed class ClassFileSource(
    val origin: AsmOrigin,
    val displayName: String,
    val path: String,
) {
    class Directory(
        displayName: String,
        path: String,
    ) : ClassFileSource(AsmOrigin.MODULE_OUTPUT, displayName, path)

    class Jar(
        displayName: String,
        path: String,
    ) : ClassFileSource(AsmOrigin.DEPENDENCY_JAR, displayName, path)
}
