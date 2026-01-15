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
package io.shamash.asm.core.scan.bytecode

import io.shamash.artifacts.util.PathNormalizer
import io.shamash.artifacts.util.glob.GlobMatcher
import io.shamash.asm.core.config.schema.v1.model.BytecodeConfig
import io.shamash.asm.core.config.schema.v1.model.ScanConfig
import io.shamash.asm.core.config.schema.v1.model.ScanScope
import io.shamash.asm.core.facts.bytecode.BytecodeUnit
import io.shamash.asm.core.facts.model.OriginKind
import io.shamash.asm.core.facts.model.SourceLocation
import java.io.InputStream
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Bytecode scanner:
 * - discovers class output directories and jar files under [BytecodeConfig.roots]
 * - filters by [BytecodeConfig.outputsGlobs] and [BytecodeConfig.jarGlobs]
 * - respects [ScanScope] for project/external bucketing
 * - produces [BytecodeUnit] suitable for FactExtractor
 *
 * Scanner is best-effort:
 * - IO errors are captured into [BytecodeScanResult.errors]
 * - scan can be truncated using [ScanConfig.maxClasses]
 */
class BytecodeScanner {
    data class BytecodeScanError(
        val message: String,
        val path: String? = null,
        val throwableClass: String? = null,
    )

    data class BytecodeScanResult(
        val origins: List<BytecodeOrigin>,
        val units: List<BytecodeUnit>,
        val errors: List<BytecodeScanError>,
        val truncated: Boolean,
    )

    fun scan(
        projectBasePath: Path,
        bytecode: BytecodeConfig,
        scan: ScanConfig,
    ): BytecodeScanResult {
        val errors = mutableListOf<BytecodeScanError>()

        val baseAbs = projectBasePath.toAbsolutePath().normalize()
        val followLinks = scan.followSymlinks
        val visitOpts = if (followLinks) EnumSet.of(FileVisitOption.FOLLOW_LINKS) else EnumSet.noneOf(FileVisitOption::class.java)

        // Resolve roots
        val roots: List<Path> =
            bytecode.roots
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { raw ->
                    val p = Paths.get(raw)
                    if (p.isAbsolute) p else projectBasePath.resolve(p)
                }.map { it.toAbsolutePath().normalize() }
                .distinct()
                .toList()

        // Discover output dirs + jars
        val outDirs = LinkedHashSet<Path>()
        val jarFiles = LinkedHashSet<Path>()

        fun matchesGlobSet(
            include: List<String>,
            exclude: List<String>,
            stablePath: String,
        ): Boolean {
            val inc = include.any { GlobMatcher.matches(it, stablePath) }
            if (!inc) return false
            val exc = exclude.any { GlobMatcher.matches(it, stablePath) }
            return !exc
        }

        fun stableFor(p: Path): String = PathNormalizer.relativizeOrNormalize(baseAbs, p)

        for (root in roots) {
            if (!Files.exists(root)) continue

            try {
                Files.walkFileTree(
                    root,
                    visitOpts,
                    Int.MAX_VALUE,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            val stable = stableFor(dir)
                            if (matchesGlobSet(bytecode.outputsGlobs.include, bytecode.outputsGlobs.exclude, stable)) {
                                outDirs.add(dir)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                            if (file.extension.lowercase() != "jar") return FileVisitResult.CONTINUE

                            val stable = stableFor(file)
                            if (matchesGlobSet(bytecode.jarGlobs.include, bytecode.jarGlobs.exclude, stable)) {
                                jarFiles.add(file)
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: java.io.IOException,
                        ): FileVisitResult {
                            errors +=
                                BytecodeScanError(
                                    message = "Failed to access path: ${exc.message ?: exc::class.java.simpleName}",
                                    path = file.toString(),
                                    throwableClass = exc::class.java.name,
                                )
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            } catch (t: Throwable) {
                errors +=
                    BytecodeScanError(
                        message = "Failed to walk root '$root': ${t.message ?: t::class.java.simpleName}",
                        path = root.toString(),
                        throwableClass = t::class.java.name,
                    )
            }
        }

        // Build origins
        val origins = mutableListOf<BytecodeOrigin>()

        fun bucketFor(path: Path): BytecodeOrigin.Bucket =
            if (path.toAbsolutePath().normalize().startsWith(baseAbs)) BytecodeOrigin.Bucket.PROJECT else BytecodeOrigin.Bucket.EXTERNAL

        fun includeByScope(bucket: BytecodeOrigin.Bucket): Boolean =
            when (scan.scope) {
                ScanScope.PROJECT_ONLY -> bucket == BytecodeOrigin.Bucket.PROJECT
                ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS -> true
                ScanScope.ALL_SOURCES -> true
            }

        // For ALL_SOURCES, we still bucket by location for display, but callers can ignore bucket.

        val includedDirs =
            outDirs
                .asSequence()
                .map { it.toAbsolutePath().normalize() }
                .distinct()
                .toList()
        for (dir in includedDirs) {
            val stable = stableFor(dir)
            val bucket = bucketFor(dir)
            if (!includeByScope(bucket)) continue
            origins +=
                BytecodeOrigin(
                    id = "dir:$stable",
                    kind = BytecodeOrigin.Kind.CLASSES_DIR,
                    bucket = bucket,
                    path = dir,
                    stablePath = stable,
                )
        }

        val includedJars =
            jarFiles
                .asSequence()
                .map { it.toAbsolutePath().normalize() }
                .distinct()
                .toList()
        for (jar in includedJars) {
            val stable = stableFor(jar)
            val bucket = bucketFor(jar)
            if (!includeByScope(bucket)) continue

            val maxJarBytes = scan.maxJarBytes
            if (maxJarBytes != null) {
                try {
                    val size = Files.size(jar)
                    if (size > maxJarBytes.toLong()) {
                        errors +=
                            BytecodeScanError(
                                message = "Jar skipped (size $size > maxJarBytes=$maxJarBytes)",
                                path = stable,
                            )
                        continue
                    }
                } catch (t: Throwable) {
                    errors +=
                        BytecodeScanError(
                            message = "Failed to stat jar: ${t.message ?: t::class.java.simpleName}",
                            path = stable,
                            throwableClass = t::class.java.name,
                        )
                    continue
                }
            }

            origins +=
                BytecodeOrigin(
                    id = "jar:$stable",
                    kind = BytecodeOrigin.Kind.JAR_FILE,
                    bucket = bucket,
                    path = jar,
                    stablePath = stable,
                )
        }

        // Deterministic order by stablePath
        val sortedOrigins =
            origins.sortedWith(
                compareBy<BytecodeOrigin> { it.bucket.name }.thenBy { it.kind.name }.thenBy { it.stablePath },
            )

        // Emit units
        val maxClasses = scan.maxClasses
        val maxClassBytes = scan.maxClassBytes
        val units = ArrayList<BytecodeUnit>(maxClasses?.coerceAtMost(4096) ?: 4096)
        val seenOriginIds = HashSet<String>(8192)
        var truncated = false

        fun checkLimit(): Boolean {
            if (maxClasses != null && units.size >= maxClasses) {
                truncated = true
                return true
            }
            return false
        }

        fun recordReadError(
            message: String,
            path: String?,
            t: Throwable? = null,
        ) {
            errors +=
                BytecodeScanError(
                    message = message,
                    path = path,
                    throwableClass = t?.javaClass?.name,
                )
        }

        fun readClassBytes(
            originId: String,
            displayPath: String,
            open: () -> InputStream,
        ): ByteArray? {
            try {
                open().use { input ->
                    // Read with an upper bound if configured.
                    if (maxClassBytes == null) {
                        return input.readBytes()
                    }

                    val buf = ByteArray(8192)
                    var total = 0
                    val out = java.io.ByteArrayOutputStream(minOf(maxClassBytes, 64 * 1024))
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxClassBytes) {
                            recordReadError(
                                message = "Class skipped (bytes $total > maxClassBytes=$maxClassBytes)",
                                path = displayPath,
                            )
                            return null
                        }
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            } catch (t: Throwable) {
                recordReadError(
                    message = "Failed to read class bytes: ${t.message ?: t::class.java.simpleName}",
                    path = displayPath,
                    t = t,
                )
                return null
            }
        }

        // Prefer scanning project outputs first for maxClasses truncation behavior.
        val projectFirst =
            sortedOrigins.sortedWith(
                compareBy<BytecodeOrigin> {
                    it.bucket != BytecodeOrigin.Bucket.PROJECT
                }.thenBy { it.kind.name }.thenBy { it.stablePath },
            )

        for (origin in projectFirst) {
            if (checkLimit()) break

            when (origin.kind) {
                BytecodeOrigin.Kind.CLASSES_DIR -> {
                    val dir = origin.path
                    if (!dir.isDirectory()) continue

                    try {
                        Files.walkFileTree(
                            dir,
                            visitOpts,
                            Int.MAX_VALUE,
                            object : SimpleFileVisitor<Path>() {
                                override fun visitFile(
                                    file: Path,
                                    attrs: BasicFileAttributes,
                                ): FileVisitResult {
                                    if (checkLimit()) return FileVisitResult.TERMINATE
                                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                                    if (file.extension.lowercase() != "class") return FileVisitResult.CONTINUE

                                    val stableFile = stableFor(file)
                                    val originId = stableFile
                                    if (!seenOriginIds.add(originId)) return FileVisitResult.CONTINUE

                                    // Quick size check for dirs
                                    if (maxClassBytes != null) {
                                        try {
                                            val size = Files.size(file)
                                            if (size > maxClassBytes.toLong()) {
                                                recordReadError(
                                                    message = "Class skipped (size $size > maxClassBytes=$maxClassBytes)",
                                                    path = stableFile,
                                                )
                                                return FileVisitResult.CONTINUE
                                            }
                                        } catch (t: Throwable) {
                                            recordReadError(
                                                message = "Failed to stat class file: ${t.message ?: t::class.java.simpleName}",
                                                path = stableFile,
                                                t = t,
                                            )
                                            return FileVisitResult.CONTINUE
                                        }
                                    }

                                    val bytes =
                                        readClassBytes(originId, stableFile) { Files.newInputStream(file) }
                                            ?: return FileVisitResult.CONTINUE
                                    units +=
                                        BytecodeUnit(
                                            bytes = bytes,
                                            location = SourceLocation(originKind = OriginKind.DIR_CLASS, originPath = stableFile),
                                            originId = originId,
                                        )

                                    return FileVisitResult.CONTINUE
                                }

                                override fun visitFileFailed(
                                    file: Path,
                                    exc: java.io.IOException,
                                ): FileVisitResult {
                                    recordReadError(
                                        message = "Failed to access class file: ${exc.message ?: exc::class.java.simpleName}",
                                        path = stableFor(file),
                                        t = exc,
                                    )
                                    return FileVisitResult.CONTINUE
                                }
                            },
                        )
                    } catch (t: Throwable) {
                        recordReadError(
                            message = "Failed to walk classes dir: ${t.message ?: t::class.java.simpleName}",
                            path = origin.stablePath,
                            t = t,
                        )
                    }
                }

                BytecodeOrigin.Kind.JAR_FILE -> {
                    val jarPath = origin.path
                    if (!jarPath.isRegularFile()) continue

                    try {
                        JarFile(jarPath.toFile()).use { jar ->
                            val entries = jar.entries()
                            while (entries.hasMoreElements()) {
                                if (checkLimit()) break
                                val e = entries.nextElement()
                                if (e.isDirectory) continue
                                if (!e.name.endsWith(".class")) continue

                                val entryName = e.name.replace('\\', '/')
                                val originId = "${origin.stablePath}!/$entryName"
                                if (!seenOriginIds.add(originId)) continue

                                val displayPath = originId
                                val bytes = readClassBytes(originId, displayPath) { jar.getInputStream(e) } ?: continue

                                units +=
                                    BytecodeUnit(
                                        bytes = bytes,
                                        location =
                                            SourceLocation(
                                                originKind = OriginKind.JAR_ENTRY,
                                                originPath = origin.stablePath,
                                                containerPath = origin.stablePath,
                                                entryPath = "/$entryName",
                                            ),
                                        originId = originId,
                                    )
                            }
                        }
                    } catch (t: Throwable) {
                        recordReadError(
                            message = "Failed to read jar: ${t.message ?: t::class.java.simpleName}",
                            path = origin.stablePath,
                            t = t,
                        )
                    }
                }
            }
        }

        // Deterministic unit ordering by originId
        val stableUnits = units.sortedBy { it.originId }
        val stableErrors = errors.sortedWith(compareBy<BytecodeScanError> { it.path ?: "" }.thenBy { it.message })

        return BytecodeScanResult(
            origins = projectFirst,
            units = stableUnits,
            errors = stableErrors,
            truncated = truncated,
        )
    }
}
