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
package io.shamash.asm.core.export.facts

/** [SCHEMA_ID] and [SCHEMA_VERSION] together identify the facts format compatibility contract. */
object FactsExportSchema {
    const val SCHEMA_ID: String = "io.shamash.asm.facts"
    const val SCHEMA_VERSION: Int = 1

    const val RECORD_META: String = "meta"
    const val RECORD_CLASS: String = "class"
    const val RECORD_EDGE: String = "edge"
}
