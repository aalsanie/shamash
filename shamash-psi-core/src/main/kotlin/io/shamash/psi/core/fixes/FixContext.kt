/*
 * Copyright © 2025-2026 | Shamash
 *
 * Author: @aalsanie
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.shamash.psi.core.fixes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.shamash.psi.core.config.schema.v1.model.ShamashPsiConfigV1

data class FixContext(
    val project: Project,
    val config: ShamashPsiConfigV1? = null,
    val configFile: VirtualFile? = null,
)
