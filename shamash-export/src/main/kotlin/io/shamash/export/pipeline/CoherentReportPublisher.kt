/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.artifacts.report.layout.ExportOutputLayout
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Renders a complete report generation away from its destination and swaps directories only on success.
 * Existing non-report files are copied into the staging directory and retained.
 */
class CoherentReportPublisher(
    private val moveDirectory: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        Unit
    },
) {
    fun <T> publish(
        outputDir: Path,
        render: (Path) -> T,
    ): T = publish(outputDir, {}, render)

    /**
     * Activates the reports before [onActivated], retaining the previous generation until both
     * succeed. A failed completion restores the previous reports; separate destinations cannot
     * offer crash-atomic visibility.
     */
    fun <T> publish(
        outputDir: Path,
        onActivated: () -> Unit,
        render: (Path) -> T,
    ): T {
        val destination = outputDir.toAbsolutePath().normalize()
        val parent = requireNotNull(destination.parent) { "Report directory has no parent" }
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(destination)) { "Report destination must not be a symbolic link" }
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(destination.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val lockFile = parent.resolve(".shamash-publish-${digest.take(24)}.lock")
        FileChannel
            .open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val lock =
                    try {
                        channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                checkNotNull(lock) { "Report destination is already being published: $destination" }
                lock.use {
                    return publishLocked(destination, parent, onActivated, render)
                }
            }
    }

    private fun <T> publishLocked(
        destination: Path,
        parent: Path,
        onActivated: () -> Unit,
        render: (Path) -> T,
    ): T {
        val stage = Files.createTempDirectory(parent, ".shamash-stage-")
        var backup: Path? = null
        var activated = false
        var completed = false
        try {
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    "Report destination is not a directory"
                }
                copyUnrelated(destination, stage)
            }
            val result = render(stage)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                val reserved = Files.createTempDirectory(parent, ".shamash-previous-")
                Files.delete(reserved)
                backup = reserved
                moveDirectory(destination, reserved)
            }
            moveDirectory(stage, destination)
            activated = true
            onActivated()
            completed = true
            return result
        } catch (failure: Throwable) {
            // A move hook may throw after completing its move; reconcile the on-disk state.
            if (!activated && !Files.exists(stage, LinkOption.NOFOLLOW_LINKS) &&
                Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
            ) {
                activated = true
            }
            var discarded: Path? = null
            try {
                if (activated) {
                    val reserved = Files.createTempDirectory(parent, ".shamash-discarded-")
                    Files.delete(reserved)
                    moveDirectory(destination, reserved)
                    discarded = reserved
                }
                val previous = backup
                if (previous != null && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                    moveDirectory(previous, destination)
                }
                backup = null
                discarded?.let { deleteTree(it) }
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(
                    IllegalStateException("Report rollback failed; inspect retained generations in $parent", rollbackFailure),
                )
                // Do not delete retained generations if restoration did not complete.
            }
            throw failure
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage)
            if (completed) backup?.let { runCatching { deleteTree(it) } }
        }
    }

    private fun copyUnrelated(
        source: Path,
        destination: Path,
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.createDirectories(destination.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (source.relativize(file).nameCount == 1 && file.fileName.toString() in REPORT_FILES) {
                        return FileVisitResult.CONTINUE
                    }
                    val target = destination.resolve(source.relativize(file))
                    Files.copy(file, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun deleteTree(path: Path) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        val REPORT_FILES = ExportOutputLayout.MANAGED_REPORT_FILE_NAMES
    }
}
