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
package io.shamash.asm.core.facts.model

import org.objectweb.asm.Opcodes

data class ClassFact(
    val type: TypeRef,
    val access: Int,
    val superType: TypeRef?,
    val interfaces: Set<TypeRef>,
    val annotationsFqns: Set<String>,
    val hasMainMethod: Boolean,
    val location: SourceLocation,
) {
    val fqName: String get() = type.fqName
    val packageName: String get() = type.packageName
    val simpleName: String get() = type.simpleName

    val visibility: Visibility =
        when {
            (access and Opcodes.ACC_PUBLIC) != 0 -> Visibility.PUBLIC
            (access and Opcodes.ACC_PROTECTED) != 0 -> Visibility.PROTECTED
            (access and Opcodes.ACC_PRIVATE) != 0 -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    val isInterface: Boolean get() = (access and Opcodes.ACC_INTERFACE) != 0
    val isAbstract: Boolean get() = (access and Opcodes.ACC_ABSTRACT) != 0
    val isEnum: Boolean get() = (access and Opcodes.ACC_ENUM) != 0
}

enum class Visibility { PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE }
