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
package io.shamash.export.api

import io.shamash.artifacts.report.schema.v1.ExportedReport
import java.nio.file.Path

/** Write reports without mutating them or reordering findings; keep formatting deterministic. */
interface Exporter {
    fun export(
        report: ExportedReport,
        outputDir: Path,
    )
}
