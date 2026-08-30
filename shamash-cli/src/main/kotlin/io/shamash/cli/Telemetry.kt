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
package io.shamash.cli

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Privacy-preserving, explicit-opt-in telemetry transport.
 *
 * It is intentionally dormant unless BOTH SHAMASH_TELEMETRY=1 and
 * SHAMASH_TELEMETRY_ENDPOINT are configured. The repository does not invent or
 * hard-code a collection service. Analysis never depends on telemetry success.
 */
internal object Telemetry {
    private val enabled = System.getenv("SHAMASH_TELEMETRY") == "1"
    private val endpoint = System.getenv("SHAMASH_TELEMETRY_ENDPOINT")?.trim()?.takeIf { it.startsWith("https://") }

    fun event(
        name: String,
        projectRoot: Path?,
        version: String,
        properties: Map<String, String> = emptyMap(),
    ) {
        if (!enabled || endpoint == null) return
        runCatching {
            val body = buildPayload(name, projectRoot, version, properties)
            val request =
                HttpRequest
                    .newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build()
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
                .send(request, HttpResponse.BodyHandlers.discarding())
        }
        // Deliberately ignore all telemetry failures.
    }

    private fun buildPayload(
        name: String,
        projectRoot: Path?,
        version: String,
        properties: Map<String, String>,
    ): String {
        val fields =
            linkedMapOf(
                "event" to name,
                "version" to version,
                "os" to System.getProperty("os.name").take(80),
                "ci" to if (System.getenv("CI")?.equals("true", true) == true) "true" else "false",
            )
        projectRoot?.let { fields["projectId"] = projectId(it) }
        properties.forEach { (k, v) -> fields[k.take(40)] = v.take(120) }
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "\"${escape(k)}\":\"${escape(v)}\"" }
    }

    private fun projectId(projectRoot: Path): String {
        val saltFile = Path.of(System.getProperty("user.home"), ".shamash", "telemetry-salt")
        val salt =
            if (Files.isRegularFile(saltFile)) {
                Files.readAllBytes(saltFile)
            } else {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                Files.createDirectories(saltFile.parent)
                Files.write(saltFile, bytes)
                bytes
            }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(
            projectRoot
                .toAbsolutePath()
                .normalize()
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        )
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest.digest())
            .take(24)
    }

    private fun escape(value: String): String =
        buildString(value.length + 8) {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(c)
                }
            }
        }
}
