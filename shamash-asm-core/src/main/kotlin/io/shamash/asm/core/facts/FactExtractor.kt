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
package io.shamash.asm.core.facts

import io.shamash.asm.core.facts.bytecode.BytecodeUnit
import io.shamash.asm.core.facts.model.ClassFact
import io.shamash.asm.core.facts.model.DependencyEdge
import io.shamash.asm.core.facts.model.DependencyKind
import io.shamash.asm.core.facts.model.FieldRef
import io.shamash.asm.core.facts.model.MethodRef
import io.shamash.asm.core.facts.model.SourceLocation
import io.shamash.asm.core.facts.model.TypeRef
import io.shamash.asm.core.facts.query.FactIndex
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.util.Printer

/**
 * Bytecode facts extraction.
 *
 * best-effort extraction (never throws for user bytecode)
 */
object FactExtractor {
    /** Back-compat API: returns facts only (errors are dropped). */
    fun extract(unit: BytecodeUnit): FactIndex = extractResult(unit).facts

    /** Production API: facts + structured errors. */
    fun extractResult(unit: BytecodeUnit): FactsResult = extractAll(sequenceOf(unit))

    /**
     * Extract and aggregate facts for multiple units.
     *
     * Notes:
     * - This does not scan; it just aggregates what you pass in.
     * - Errors are per-unit.
     */
    fun extractAll(units: Sequence<BytecodeUnit>): FactsResult {
        val errors = mutableListOf<FactsError>()
        var acc = FactIndex.empty()

        for (u in units) {
            val originId = u.originId
            try {
                val one = computeFactsForUnit(u, errors)
                acc = acc.merge(one)
            } catch (t: Throwable) {
                errors +=
                    FactsError(
                        originId = originId,
                        phase = "computeFacts",
                        message = t.message ?: "Facts extraction failed",
                        throwableClass = t::class.java.name,
                    )
            }
        }

        return FactsResult(facts = acc, errors = errors).stabilize()
    }

    private fun computeFactsForUnit(
        unit: BytecodeUnit,
        errors: MutableList<FactsError>,
    ): FactIndex {
        val originId = unit.originId
        val baseLoc = unit.location.normalized()

        val classes = mutableListOf<ClassFact>()
        val methods = mutableListOf<MethodRef>()
        val fields = mutableListOf<FieldRef>()
        val edges = mutableListOf<DependencyEdge>()

        fun record(
            phase: String,
            t: Throwable,
        ) {
            errors +=
                FactsError(
                    originId = originId,
                    phase = phase,
                    message = t.message ?: "ASM extraction error",
                    throwableClass = t::class.java.name,
                )
        }

        val reader =
            try {
                ClassReader(unit.bytes)
            } catch (t: Throwable) {
                record("ClassReader", t)
                return FactIndex.empty()
            }

        val visitor =
            CollectingClassVisitor(
                baseLocation = baseLoc,
                originId = originId,
                onClass = { classes += it },
                onMethod = { methods += it },
                onField = { fields += it },
                onEdge = { edges += it },
                onError = { phase, t -> record(phase, t) },
            )

        try {
            // We don't need frames for facts;
            // skipping frames improves speed and reduces verifier sensitivity.
            reader.accept(visitor, ClassReader.SKIP_FRAMES)
        } catch (t: Throwable) {
            record("ClassReader.accept", t)
            return FactIndex.empty()
        }

        return FactIndex(
            classes = classes,
            methods = methods,
            fields = fields,
            edges = edges,
            roles = emptyMap(),
            classToRole = emptyMap(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Visitors
    // ---------------------------------------------------------------------------------------------

    private class CollectingClassVisitor(
        private val baseLocation: SourceLocation,
        private val originId: String,
        private val onClass: (ClassFact) -> Unit,
        private val onMethod: (MethodRef) -> Unit,
        private val onField: (FieldRef) -> Unit,
        private val onEdge: (DependencyEdge) -> Unit,
        private val onError: (String, Throwable) -> Unit,
    ) : ClassVisitor(Opcodes.ASM9) {
        private var classInternalName: String? = null
        private var classAccess: Int = 0
        private var superInternalName: String? = null
        private var interfaceInternalNames: List<String> = emptyList()
        private var sourceFile: String? = null

        private val classAnnotations = linkedSetOf<String>()

        private var hasMain: Boolean = false

        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?,
        ) {
            classInternalName = name
            classAccess = access
            superInternalName = superName
            interfaceInternalNames = interfaces?.toList().orEmpty()

            // Structural deps: extends/implements.
            val from = TypeRef.fromInternalName(name)
            if (superName != null) {
                TypeRef.fromInternalName(superName).let { to ->
                    onEdge(
                        DependencyEdge(
                            from = from,
                            to = to,
                            kind = DependencyKind.EXTENDS,
                            location = baseLocation,
                            detail = "extends",
                        ),
                    )
                }
            }
            for (i in interfaceInternalNames) {
                TypeRef.fromInternalName(i).let { to ->
                    onEdge(
                        DependencyEdge(
                            from = from,
                            to = to,
                            kind = DependencyKind.IMPLEMENTS,
                            location = baseLocation,
                            detail = "implements",
                        ),
                    )
                }
            }
        }

        override fun visitSource(
            source: String?,
            debug: String?,
        ) {
            sourceFile = source
        }

        override fun visitAnnotation(
            descriptor: String,
            visible: Boolean,
        ): AnnotationVisitor? {
            val ann = annotationFqn(descriptor)
            if (ann != null) {
                classAnnotations += ann
                val from = classInternalName?.let { TypeRef.fromInternalName(it) }
                val to = TypeRef.fromInternalName(ann.replace('.', '/'))
                if (from != null && from.internalName != to.internalName) {
                    onEdge(
                        DependencyEdge(
                            from = from,
                            to = to,
                            kind = DependencyKind.ANNOTATION_TYPE,
                            location = baseLocation,
                            detail = "class-annotation",
                        ),
                    )
                }
            }
            return null
        }

        override fun visitField(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            value: Any?,
        ): FieldVisitor {
            val ownerInternal = classInternalName
            if (ownerInternal == null) return NoopFieldVisitor

            val owner = TypeRef.fromInternalName(ownerInternal)
            val fieldType =
                try {
                    TypeRef.dependencyTargetFromType(Type.getType(descriptor))
                } catch (_: Throwable) {
                    null
                }

            val anns = linkedSetOf<String>()

            val fv =
                object : FieldVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        val a = annotationFqn(descriptor)
                        if (a != null) {
                            anns += a

                            val to = TypeRef.fromInternalName(a.replace('.', '/'))
                            if (owner.internalName != to.internalName) {
                                onEdge(
                                    DependencyEdge(
                                        from = owner,
                                        to = to,
                                        kind = DependencyKind.ANNOTATION_TYPE,
                                        location = baseLocation,
                                        detail = "field-annotation:$name",
                                    ),
                                )
                            }
                        }
                        return null
                    }

                    override fun visitEnd() {
                        val loc = baseLocation.withSourceFile(sourceFile)

                        onField(
                            FieldRef(
                                owner = owner,
                                name = name,
                                desc = descriptor,
                                signature = signature,
                                access = access,
                                fieldType = fieldType,
                                annotationsFqns = anns,
                                location = loc,
                            ),
                        )

                        if (fieldType != null && owner.internalName != fieldType.internalName) {
                            onEdge(
                                DependencyEdge(
                                    from = owner,
                                    to = fieldType,
                                    kind = DependencyKind.FIELD_TYPE,
                                    location = loc,
                                    detail = "field:$name",
                                ),
                            )
                        }
                    }
                }

            return safeFieldVisitor(fv)
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
        ): MethodVisitor {
            val ownerInternal = classInternalName
            if (ownerInternal == null) return NoopMethodVisitor

            val owner = TypeRef.fromInternalName(ownerInternal)

            if (MethodRef.isMain(access, name, descriptor)) {
                hasMain = true
            }

            val methodType =
                try {
                    Type.getMethodType(descriptor)
                } catch (t: Throwable) {
                    onError("method:Type.getMethodType", t)
                    Type.getMethodType("()V")
                }

            val ret = TypeRef.dependencyTargetFromType(methodType.returnType)
            val params = methodType.argumentTypes.mapNotNull { TypeRef.dependencyTargetFromType(it) }
            val throwsTypes =
                exceptions
                    ?.mapNotNull {
                        runCatching { TypeRef.fromInternalName(it) }.getOrNull()
                    }.orEmpty()

            val anns = linkedSetOf<String>()

            val methodBaseLoc = baseLocation.withSourceFile(sourceFile)

            // Signature-level dependencies.
            for (p in params) {
                if (p.internalName != owner.internalName) {
                    onEdge(
                        DependencyEdge(
                            from = owner,
                            to = p,
                            kind = DependencyKind.METHOD_PARAM_TYPE,
                            location = methodBaseLoc,
                            detail = "param:$name",
                        ),
                    )
                }
            }
            if (ret != null && ret.internalName != owner.internalName) {
                onEdge(
                    DependencyEdge(
                        from = owner,
                        to = ret,
                        kind = DependencyKind.METHOD_RETURN_TYPE,
                        location = methodBaseLoc,
                        detail = "return:$name",
                    ),
                )
            }
            for (ex in throwsTypes) {
                if (ex.internalName != owner.internalName) {
                    onEdge(
                        DependencyEdge(
                            from = owner,
                            to = ex,
                            kind = DependencyKind.THROWS_TYPE,
                            location = methodBaseLoc,
                            detail = "throws:$name",
                        ),
                    )
                }
            }

            val mv =
                object : MethodVisitor(Opcodes.ASM9) {
                    private var currentLine: Int? = null

                    override fun visitLineNumber(
                        line: Int,
                        start: Label?,
                    ) {
                        currentLine = line
                    }

                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        val a = annotationFqn(descriptor)
                        if (a != null) {
                            anns += a

                            val to = TypeRef.fromInternalName(a.replace('.', '/'))
                            if (owner.internalName != to.internalName) {
                                onEdge(
                                    DependencyEdge(
                                        from = owner,
                                        to = to,
                                        kind = DependencyKind.ANNOTATION_TYPE,
                                        location = methodBaseLoc.withLine(currentLine),
                                        detail = "method-annotation:$name",
                                    ),
                                )
                            }
                        }
                        return null
                    }

                    override fun visitTypeInsn(
                        opcode: Int,
                        type: String,
                    ) {
                        // NEW/CHECKCAST/INSTANCEOF/ANEWARRAY
                        val to = TypeRef.fromInternalName(type)
                        if (owner.internalName == to.internalName) return

                        val op = Printer.OPCODES.getOrNull(opcode) ?: opcode.toString()
                        onEdge(
                            DependencyEdge(
                                from = owner,
                                to = to,
                                kind = DependencyKind.TYPE_INSTRUCTION,
                                location = methodBaseLoc.withLine(currentLine),
                                detail = "type:$op",
                            ),
                        )
                    }

                    override fun visitFieldInsn(
                        opcode: Int,
                        ownerInternalName: String,
                        name: String,
                        descriptor: String,
                    ) {
                        val to = TypeRef.fromInternalName(ownerInternalName)
                        if (owner.internalName == to.internalName) return

                        val op = Printer.OPCODES.getOrNull(opcode) ?: opcode.toString()
                        onEdge(
                            DependencyEdge(
                                from = owner,
                                to = to,
                                kind = DependencyKind.FIELD_ACCESS,
                                location = methodBaseLoc.withLine(currentLine),
                                detail = "field:$op:$name:$descriptor",
                            ),
                        )
                    }

                    override fun visitMethodInsn(
                        opcode: Int,
                        ownerInternalName: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        val to = TypeRef.fromInternalName(ownerInternalName)
                        if (owner.internalName == to.internalName) return

                        val op = Printer.OPCODES.getOrNull(opcode) ?: opcode.toString()
                        onEdge(
                            DependencyEdge(
                                from = owner,
                                to = to,
                                kind = DependencyKind.METHOD_CALL,
                                location = methodBaseLoc.withLine(currentLine),
                                detail = "call:$op:$name$descriptor",
                            ),
                        )
                    }

                    override fun visitTryCatchBlock(
                        start: Label?,
                        end: Label?,
                        handler: Label?,
                        type: String?,
                    ) {
                        if (type == null) return
                        val to = TypeRef.fromInternalName(type)
                        if (owner.internalName == to.internalName) return

                        onEdge(
                            DependencyEdge(
                                from = owner,
                                to = to,
                                kind = DependencyKind.THROWS_TYPE,
                                location = methodBaseLoc.withLine(currentLine),
                                detail = "catch",
                            ),
                        )
                    }

                    override fun visitLdcInsn(value: Any?) {
                        when (value) {
                            is Type -> {
                                val to = TypeRef.dependencyTargetFromType(value) ?: return
                                if (owner.internalName == to.internalName) return

                                onEdge(
                                    DependencyEdge(
                                        from = owner,
                                        to = to,
                                        kind = DependencyKind.CONST_TYPE,
                                        location = methodBaseLoc.withLine(currentLine),
                                        detail = "ldc:type",
                                    ),
                                )
                            }
                            is Handle -> {
                                val to = TypeRef.fromInternalName(value.owner)
                                if (owner.internalName == to.internalName) return

                                onEdge(
                                    DependencyEdge(
                                        from = owner,
                                        to = to,
                                        kind = DependencyKind.CONST_TYPE,
                                        location = methodBaseLoc.withLine(currentLine),
                                        detail = "ldc:handle:${value.name}",
                                    ),
                                )
                            }
                        }
                    }

                    override fun visitInvokeDynamicInsn(
                        name: String,
                        descriptor: String,
                        bootstrapMethodHandle: Handle,
                        vararg bootstrapMethodArguments: Any?,
                    ) {
                        // Bootstrap handle owner is a type dependency.
                        runCatching {
                            val to = TypeRef.fromInternalName(bootstrapMethodHandle.owner)
                            if (owner.internalName != to.internalName) {
                                onEdge(
                                    DependencyEdge(
                                        from = owner,
                                        to = to,
                                        kind = DependencyKind.CONST_TYPE,
                                        location = methodBaseLoc.withLine(currentLine),
                                        detail = "invokedynamic:bsm:${bootstrapMethodHandle.name}",
                                    ),
                                )
                            }
                        }.onFailure { onError("method:visitInvokeDynamicInsn", it) }

                        // Some bsm args can contain Type/Handle.
                        for (arg in bootstrapMethodArguments) {
                            when (arg) {
                                is Type -> {
                                    val to = TypeRef.dependencyTargetFromType(arg) ?: continue
                                    if (owner.internalName == to.internalName) continue
                                    onEdge(
                                        DependencyEdge(
                                            from = owner,
                                            to = to,
                                            kind = DependencyKind.CONST_TYPE,
                                            location = methodBaseLoc.withLine(currentLine),
                                            detail = "invokedynamic:arg:type",
                                        ),
                                    )
                                }
                                is Handle -> {
                                    val to = TypeRef.fromInternalName(arg.owner)
                                    if (owner.internalName == to.internalName) continue
                                    onEdge(
                                        DependencyEdge(
                                            from = owner,
                                            to = to,
                                            kind = DependencyKind.CONST_TYPE,
                                            location = methodBaseLoc.withLine(currentLine),
                                            detail = "invokedynamic:arg:handle:${arg.name}",
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    override fun visitEnd() {
                        val loc = methodBaseLoc

                        onMethod(
                            MethodRef(
                                owner = owner,
                                name = name,
                                desc = descriptor,
                                signature = signature,
                                access = access,
                                isConstructor = name == "<init>" || name == "<clinit>",
                                returnType = ret,
                                parameterTypes = params,
                                throwsTypes = throwsTypes,
                                annotationsFqns = anns,
                                location = loc,
                            ),
                        )
                    }
                }

            return safeMethodVisitor(mv)
        }

        override fun visitEnd() {
            val name = classInternalName ?: return

            val type = TypeRef.fromInternalName(name)
            val superType = superInternalName?.let { TypeRef.fromInternalName(it) }
            val ifaces = interfaceInternalNames.map { TypeRef.fromInternalName(it) }.toSet()

            val loc = baseLocation.withSourceFile(sourceFile)

            onClass(
                ClassFact(
                    type = type,
                    access = classAccess,
                    superType = superType,
                    interfaces = ifaces,
                    annotationsFqns = classAnnotations,
                    hasMainMethod = hasMain,
                    location = loc,
                ),
            )

            // Annotation types as deps at class end too
            // (visitAnnotation already records edges; this is just in case).
            for (a in classAnnotations) {
                val to = TypeRef.fromInternalName(a.replace('.', '/'))
                if (type.internalName != to.internalName) {
                    onEdge(
                        DependencyEdge(
                            from = type,
                            to = to,
                            kind = DependencyKind.ANNOTATION_TYPE,
                            location = loc,
                            detail = "class-annotation",
                        ),
                    )
                }
            }
        }

        private fun safeMethodVisitor(mv: MethodVisitor): MethodVisitor =
            object : MethodVisitor(Opcodes.ASM9, mv) {
                override fun visitTypeInsn(
                    opcode: Int,
                    type: String,
                ) = safe("method:visitTypeInsn") {
                    super.visitTypeInsn(opcode, type)
                }

                override fun visitFieldInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                ) = safe("method:visitFieldInsn") {
                    super.visitFieldInsn(opcode, owner, name, descriptor)
                }

                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String,
                    name: String,
                    descriptor: String,
                    isInterface: Boolean,
                ) = safe("method:visitMethodInsn") {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                }

                override fun visitTryCatchBlock(
                    start: Label?,
                    end: Label?,
                    handler: Label?,
                    type: String?,
                ) = safe("method:visitTryCatchBlock") {
                    super.visitTryCatchBlock(start, end, handler, type)
                }

                override fun visitLdcInsn(value: Any?) =
                    safe("method:visitLdcInsn") {
                        super.visitLdcInsn(value)
                    }

                override fun visitInvokeDynamicInsn(
                    name: String,
                    descriptor: String,
                    bootstrapMethodHandle: Handle,
                    vararg bootstrapMethodArguments: Any?,
                ) = safe("method:visitInvokeDynamicInsn") {
                    super.visitInvokeDynamicInsn(
                        name,
                        descriptor,
                        bootstrapMethodHandle,
                        *bootstrapMethodArguments,
                    )
                }

                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? =
                    safeR("method:visitAnnotation", null) {
                        super.visitAnnotation(descriptor, visible)
                    }

                override fun visitLineNumber(
                    line: Int,
                    start: Label?,
                ) = safe("method:visitLineNumber") { super.visitLineNumber(line, start) }

                override fun visitEnd() = safe("method:visitEnd") { super.visitEnd() }

                private fun safe(
                    phase: String,
                    block: () -> Unit,
                ) {
                    try {
                        block()
                    } catch (t: Throwable) {
                        onError(phase, t)
                    }
                }

                private fun <T> safeR(
                    phase: String,
                    fallback: T,
                    block: () -> T,
                ): T =
                    try {
                        block()
                    } catch (t: Throwable) {
                        onError(phase, t)
                        fallback
                    }
            }

        private fun safeFieldVisitor(fv: FieldVisitor): FieldVisitor =
            object : FieldVisitor(Opcodes.ASM9, fv) {
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? =
                    safeR("field:visitAnnotation", null) {
                        super.visitAnnotation(descriptor, visible)
                    }

                override fun visitEnd() = safe("field:visitEnd") { super.visitEnd() }

                private fun safe(
                    phase: String,
                    block: () -> Unit,
                ) {
                    try {
                        block()
                    } catch (t: Throwable) {
                        onError(phase, t)
                    }
                }

                private fun <T> safeR(
                    phase: String,
                    fallback: T,
                    block: () -> T,
                ): T =
                    try {
                        block()
                    } catch (t: Throwable) {
                        onError(phase, t)
                        fallback
                    }
            }

        private fun annotationFqn(descriptor: String): String? {
            return try {
                val t = Type.getType(descriptor)
                if (t.sort != Type.OBJECT) return null
                t.className
            } catch (t: Throwable) {
                onError("annotationFqn", t)
                null
            }
        }
    }

    private object NoopMethodVisitor : MethodVisitor(Opcodes.ASM9)

    private object NoopFieldVisitor : FieldVisitor(Opcodes.ASM9)

    // ---------------------------------------------------------------------------------------------
    // Determinism
    // ---------------------------------------------------------------------------------------------

    private fun FactsResult.stabilize(): FactsResult =
        copy(
            facts = facts.stabilizeFacts(),
            errors =
                errors.sortedWith(
                    compareBy(
                        { it.phase },
                        { it.message },
                        { it.throwableClass.orEmpty() },
                        { it.originId },
                    ),
                ),
        )

    private fun FactIndex.stabilizeFacts(): FactIndex {
        val classesStable =
            classes
                .dedupeByKey {
                    "${it.type.internalName}|${it.location.originPath}|${it.location.entryPath.orEmpty()}"
                }.sortedBy { it.fqName }

        val methodsStable =
            methods
                .dedupeByKey { it.fqName }
                .sortedBy { it.fqName }

        val fieldsStable =
            fields
                .dedupeByKey { it.fqName }
                .sortedBy { it.fqName }

        val edgesStable =
            edges
                .dedupeByKey { edgeKey(it) }
                .sortedBy { edgeKey(it) }

        return copy(
            classes = classesStable,
            methods = methodsStable,
            fields = fieldsStable,
            edges = edgesStable,
        )
    }

    private fun edgeKey(e: DependencyEdge): String {
        val loc = e.location
        val locKey =
            "${loc.originKind}|${loc.originPath}|${loc.containerPath.orEmpty()}|" +
                "${loc.entryPath.orEmpty()}|${loc.sourceFile.orEmpty()}|${loc.line ?: -1}"
        return "${e.from.internalName}|${e.to.internalName}|${e.kind.name}|${e.detail.orEmpty()}|$locKey"
    }

    private inline fun <T> List<T>.dedupeByKey(keyFn: (T) -> String): List<T> {
        val seen = LinkedHashMap<String, T>(this.size)
        for (x in this) {
            val k = keyFn(x)
            if (!seen.containsKey(k)) seen[k] = x
        }
        return seen.values.toList()
    }
}
