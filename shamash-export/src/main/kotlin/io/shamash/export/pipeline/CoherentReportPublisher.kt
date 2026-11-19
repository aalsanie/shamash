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

/**
 * Publishes a complete report generation by replacing only Shamash-managed report files.
 *
 * The destination directory and unrelated entries are never replaced. Cooperating publishers
 * are serialized by the parent-directory lock. Individual file moves are atomic on supported
 * filesystems; a multi-file generation is not crash-atomic or an atomic snapshot for readers
 * that do not coordinate with the publisher lock.
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
     * Invokes [onActivated] after activating managed reports while their previous files remain
     * available for rollback. A failure restores the previous managed generation where possible.
     * Separate destinations and unsynchronized readers cannot have crash-atomic visibility.
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
        // The shared parent lock also serializes case-insensitive aliases of the destination.
        val lockFile = parent.resolve(".shamash-publish-lock")
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
                checkNotNull(lock) { "Report destination is already being published: " + destination }
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
        requireDirectoryOrAbsent(destination)
        val stage = Files.createTempDirectory(parent, ".shamash-stage-")
        var backup: Path? = null
        var discarded: Path? = null
        var createdDestination = false
        var rollbackFailed = false
        var primaryFailure: Throwable? = null
        val previouslyManaged = mutableListOf<String>()
        val activated = mutableListOf<String>()
        var pendingActivation: String? = null

        try {
            val result = render(stage)
            val staged = stagedFiles(stage)
            requireDirectoryOrAbsent(destination)
            previouslyManaged += existingManagedFiles(destination)

            if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(destination)
                createdDestination = true
            }
            if (previouslyManaged.isNotEmpty()) {
                backup = Files.createTempDirectory(parent, ".shamash-previous-")
                val previousDirectory = requireNotNull(backup)
                for (name in previouslyManaged) {
                    moveDirectory(destination.resolve(name), previousDirectory.resolve(name))
                }
            }

            for (name in staged) {
                pendingActivation = name
                moveDirectory(stage.resolve(name), destination.resolve(name))
                activated += name
                pendingActivation = null
            }
            onActivated()
            return result
        } catch (failure: Throwable) {
            primaryFailure = failure
            pendingActivation?.let { name ->
                // An injected move may perform the filesystem mutation and then throw.
                if (!Files.exists(stage.resolve(name), LinkOption.NOFOLLOW_LINKS) &&
                    Files.exists(destination.resolve(name), LinkOption.NOFOLLOW_LINKS)
                ) {
                    activated += name
                }
            }
            try {
                if (activated.isNotEmpty()) {
                    discarded = Files.createTempDirectory(parent, ".shamash-discarded-")
                    val discardedDirectory = requireNotNull(discarded)
                    for (name in activated.asReversed()) {
                        val published = destination.resolve(name)
                        if (Files.exists(published, LinkOption.NOFOLLOW_LINKS)) {
                            moveDirectory(published, discardedDirectory.resolve(name))
                        }
                    }
                }
                backup?.let { previousDirectory ->
                    for (name in previouslyManaged.asReversed()) {
                        val saved = previousDirectory.resolve(name)
                        if (Files.exists(saved, LinkOption.NOFOLLOW_LINKS)) {
                            moveDirectory(saved, destination.resolve(name))
                        }
                    }
                }
                if (createdDestination && Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                    // An unrelated writer may have added content after the destination was created.
                    try {
                        Files.delete(destination)
                    } catch (_: java.nio.file.DirectoryNotEmptyException) {
                        // Leave the user's entries and their enclosing directory intact.
                    }
                }
            } catch (rollbackFailure: Throwable) {
                rollbackFailed = true
                failure.addSuppressed(
                    IllegalStateException(
                        "Report rollback failed; inspect retained managed-file recovery data in " + parent,
                        rollbackFailure,
                    ),
                )
            }
            throw failure
        } finally {
            if (!rollbackFailed) {
                cleanupOwned(stage, primaryFailure)
                backup?.let { cleanupOwned(it, primaryFailure) }
                discarded?.let { cleanupOwned(it, primaryFailure) }
            }
        }
    }

    private fun requireDirectoryOrAbsent(destination: Path) {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                "Report destination is not a directory: " + destination
            }
        }
    }

    private fun existingManagedFiles(destination: Path): List<String> =
        ExportOutputLayout.MANAGED_REPORT_FILE_NAMES.sorted().filter { name ->
            val target = destination.resolve(name)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                false
            } else {
                require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    "Managed report path must be a regular file: " + target
                }
                true
            }
        }

    private fun stagedFiles(stage: Path): List<String> {
        require(Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) {
            "Report staging directory is missing or invalid: " + stage
        }
        return Files.list(stage).use { entries ->
            entries.toList().map { file ->
                val name = file.fileName.toString()
                require(name in ExportOutputLayout.MANAGED_REPORT_FILE_NAMES &&
                    Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                ) {
                    "Staged report must be a managed regular file: " + file
                }
                name
            }.sorted()
        }
    }

    private fun cleanupOwned(
        path: Path,
        primaryFailure: Throwable?,
    ) {
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) deleteTree(path)
        } catch (cleanupFailure: Throwable) {
            // Cleanup must not mask the original failure or turn an activated report into a failed publish.
            primaryFailure?.addSuppressed(cleanupFailure)
        }
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
}
