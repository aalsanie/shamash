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
package kotlin_right

class Container<T>(
    val owner: kotlin_left.KotlinOwner,
    val payload: T,
)

data class Payload(val name: String)

object Registry {
    val defaultPayload = Payload("default")

    fun create(owner: kotlin_left.KotlinOwner): Container<Payload> =
        Container(owner, defaultPayload)
}
