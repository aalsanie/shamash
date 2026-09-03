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

/** Captures IO failures in [BytecodeScanResult.errors]; [ScanConfig.maxClasses] may truncate output. */
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
        require(scan.maxClasses == null || scan.maxClasses > 0) { "maxClasses must be > 0" }
        require(scan.maxJarBytes == null || scan.maxJarBytes > 0) { "maxJarBytes must be > 0" }
        require(scan.maxClassBytes == null || scan.maxClassBytes > 0) { "maxClassBytes must be > 0" }
        val errors = mutableListOf<BytecodeScanError>()

        val baseAbs = projectBasePath.toAbsolutePath().normalize()
        val followLinks = scan.followSymlinks
        val visitOpts = if (followLinks) EnumSet.of(FileVisitOption.FOLLOW_LINKS) else EnumSet.noneOf(FileVisitOption::class.java)

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

        fun matchesOutputDirectory(
            globs: List<String>,
            dir: Path,
        ): Boolean {
            val stable = stableFor(dir)
            return globs.any { GlobMatcher.matches(it, stable) || GlobMatcher.matches(it, "$stable/") }
        }

        fun excludedOutputDirectory(dir: Path): Boolean =
            generateSequence(dir) { it.parent }.any { matchesOutputDirectory(bytecode.outputsGlobs.exclude, it) }

        for (root in minimalRoots(roots)) {
            if (Files.notExists(root)) continue

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
                            if (matchesOutputDirectory(bytecode.outputsGlobs.include, dir) && !excludedOutputDirectory(dir)) {
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

        val origins = mutableListOf<BytecodeOrigin>()

        fun bucketFor(path: Path): BytecodeOrigin.Bucket =
            if (path.toAbsolutePath().normalize().startsWith(baseAbs)) BytecodeOrigin.Bucket.PROJECT else BytecodeOrigin.Bucket.EXTERNAL

        fun includeByScope(bucket: BytecodeOrigin.Bucket): Boolean =
            when (scan.scope) {
                ScanScope.PROJECT_ONLY -> bucket == BytecodeOrigin.Bucket.PROJECT
                ScanScope.PROJECT_WITH_EXTERNAL_BUCKETS -> true
                ScanScope.ALL_SOURCES -> true
            }

        val includedDirs =
            outDirs
                .filter { includeByScope(bucketFor(it)) }
                .groupBy { bucketFor(it) }
                .values
                .flatMap { minimalRoots(it) }
        for (dir in includedDirs) {
            val stable = stableFor(dir)
            val bucket = bucketFor(dir)
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

        val sortedOrigins =
            origins.sortedWith(
                compareBy<BytecodeOrigin> { it.bucket.name }.thenBy { it.kind.name }.thenBy { it.stablePath },
            )

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
            displayPath: String,
            open: () -> InputStream,
        ): ByteArray? {
            try {
                open().use { input ->
                    if (maxClassBytes == null) {
                        return input.readBytes()
                    }

                    val buf = ByteArray(8192)
                    var total = 0L
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
            if (truncated) break

            when (origin.kind) {
                BytecodeOrigin.Kind.CLASSES_DIR -> {
                    val dir = origin.path
                    if (!dir.isDirectory()) {
                        recordReadError("Classes directory is no longer accessible", origin.stablePath)
                        continue
                    }

                    try {
                        Files.walkFileTree(
                            dir,
                            visitOpts,
                            Int.MAX_VALUE,
                            object : SimpleFileVisitor<Path>() {
                                override fun preVisitDirectory(
                                    dir: Path,
                                    attrs: BasicFileAttributes,
                                ): FileVisitResult =
                                    if (excludedOutputDirectory(dir)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                                override fun visitFile(
                                    file: Path,
                                    attrs: BasicFileAttributes,
                                ): FileVisitResult {
                                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                                    if (file.extension.lowercase() != "class") return FileVisitResult.CONTINUE

                                    val stableFile = stableFor(file)
                                    if (bytecode.outputsGlobs.exclude.any { GlobMatcher.matches(it, stableFile) }) {
                                        return FileVisitResult.CONTINUE
                                    }
                                    val originId = stableFile
                                    if (!seenOriginIds.add(originId)) return FileVisitResult.CONTINUE

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

                                    if (checkLimit()) return FileVisitResult.TERMINATE
                                    val bytes =
                                        readClassBytes(stableFile) { Files.newInputStream(file) }
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
                    if (!jarPath.isRegularFile()) {
                        recordReadError("Jar is no longer accessible", origin.stablePath)
                        continue
                    }

                    try {
                        JarFile(jarPath.toFile()).use { jar ->
                            val entries = jar.entries()
                            while (entries.hasMoreElements()) {
                                val e = entries.nextElement()
                                if (e.isDirectory) continue
                                if (!e.name.endsWith(".class")) continue

                                val entryName = e.name.replace('\\', '/')
                                val originId = "${origin.stablePath}!/$entryName"
                                if (!seenOriginIds.add(originId)) continue

                                if (maxClassBytes != null && e.size > maxClassBytes.toLong()) {
                                    recordReadError(
                                        message = "Class skipped (size ${e.size} > maxClassBytes=$maxClassBytes)",
                                        path = originId,
                                    )
                                    continue
                                }
                                if (checkLimit()) break
                                val bytes = readClassBytes(originId) { jar.getInputStream(e) } ?: continue

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

        val stableUnits = units.sortedBy { it.originId }
        val stableErrors = errors.sortedWith(compareBy<BytecodeScanError> { it.path ?: "" }.thenBy { it.message })

        return BytecodeScanResult(
            origins = projectFirst,
            units = stableUnits,
            errors = stableErrors,
            truncated = truncated,
        )
    }

    private fun minimalRoots(paths: Collection<Path>): List<Path> {
        val selected = LinkedHashSet<Path>()
        for (path in paths.sortedWith(compareBy<Path> { it.nameCount }.thenBy { it.toString() })) {
            if (generateSequence(path.parent) { it.parent }.none { it in selected }) {
                selected.add(path)
            }
        }
        return selected.toList()
    }
}
