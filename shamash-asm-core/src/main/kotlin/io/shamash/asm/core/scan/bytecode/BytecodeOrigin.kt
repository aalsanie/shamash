/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.asm.core.scan.bytecode

import java.nio.file.Path

data class BytecodeOrigin(
    val id: String,
    val kind: Kind,
    val bucket: Bucket,
    val path: Path,
    /** Normalized path for matching/logging, project-relative when possible. */
    val stablePath: String,
) {
    enum class Kind {
        CLASSES_DIR,
        JAR_FILE,
    }

    enum class Bucket {
        PROJECT,
        EXTERNAL,
    }
}
