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

data class FieldRef(
    val owner: TypeRef,
    val name: String,
    val desc: String,
    val signature: String?,
    val access: Int,
    val fieldType: TypeRef?,
    val annotationsFqns: Set<String>,
    val location: SourceLocation,
) {
    val fqName: String = "${owner.fqName}#$name:$desc"

    val visibility: Visibility =
        when {
            (access and Opcodes.ACC_PUBLIC) != 0 -> Visibility.PUBLIC
            (access and Opcodes.ACC_PROTECTED) != 0 -> Visibility.PROTECTED
            (access and Opcodes.ACC_PRIVATE) != 0 -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }

    val isStatic: Boolean get() = (access and Opcodes.ACC_STATIC) != 0
    val isFinal: Boolean get() = (access and Opcodes.ACC_FINAL) != 0
}
