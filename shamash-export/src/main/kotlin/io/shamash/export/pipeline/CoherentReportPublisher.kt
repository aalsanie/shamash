/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.export.pipeline

import io.shamash.artifacts.report.layout.ExportOutputLayout
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Renders a complete report generation away from its destination and swaps directories only on success.
 * Existing non-report files are copied into the staging directory and retained.
 */
class CoherentReportPublisher {
    fun <T> publish(
        outputDir: Path,
        render: (Path) -> T,
    ): T {
        val destination = outputDir.toAbsolutePath().normalize()
        val parent = requireNotNull(destination.parent) { "Report directory has no parent" }
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(destination)) { "Report destination must not be a symbolic link" }
        val stage = Files.createTempDirectory(parent, ".shamash-stage-")
        var backup: Path? = null
        var published = false
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
                Files.move(destination, reserved, StandardCopyOption.ATOMIC_MOVE)
                backup = reserved
            }
            try {
                Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (failure: Throwable) {
                backup?.let { Files.move(it, destination, StandardCopyOption.ATOMIC_MOVE) }
                backup = null
                throw failure
            }
            published = true
            return result
        } finally {
            if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) deleteTree(stage)
            if (published) backup?.let { deleteTree(it) }
        }
    }

    private fun copyUnrelated(
        source: Path,
        destination: Path,
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(destination.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
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
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private companion object {
        val REPORT_FILES = setOf(
            ExportOutputLayout.JSON_FILE_NAME,
            ExportOutputLayout.SARIF_FILE_NAME,
            ExportOutputLayout.XML_FILE_NAME,
            ExportOutputLayout.HTML_FILE_NAME,
            ExportOutputLayout.FACTS_JSONL_GZ_FILE_NAME,
            ExportOutputLayout.FACTS_JSON_FILE_NAME,
            ExportOutputLayout.ROLES_JSON_FILE_NAME,
            ExportOutputLayout.RULE_PLAN_JSON_FILE_NAME,
            ExportOutputLayout.ANALYSIS_GRAPHS_JSON_FILE_NAME,
            ExportOutputLayout.ANALYSIS_HOTSPOTS_JSON_FILE_NAME,
            ExportOutputLayout.ANALYSIS_SCORES_JSON_FILE_NAME,
        )
    }
}
