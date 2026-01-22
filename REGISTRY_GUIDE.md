# Registry Guide (ASM Rule Registries)

Shamash ASM ships with a built-in rule registry: **`default`**.

And recently we added **pluggability** so advanced users can supply alternate registries in a controlled way:

- **CLI**: Java `ServiceLoader` via `AsmRuleRegistryProvider`
- **IntelliJ**: IntelliJ **Extension Point** `io.shamash.asmRuleRegistryProvider`

Registry selection is **opt-in**. If you don’t touch anything, Shamash continues using the built-in registry.

---

## What is a registry?

A **registry** is the engine’s catalogue of:

- which rules exist
- how rule parameters are validated (RuleSpec contracts)
- how rules execute

It is **not** your `asm.yml`.

- `asm.yml` selects *which* rules to run + configuration.
- the registry defines *what rules are available* and *what they mean*.

---

## Safety model

1) **Default remains default**
- If registry selection is omitted (or `default` is chosen), Shamash uses the built-in registry.

2) **Fail fast for unknown registries**
- CLI: unknown id → exit code `2` + actionable message + available ids
- IntelliJ: selected provider missing/uninstalled → actionable error

3) **Registry identity is explicit**
- Each provider has a stable `id` and a human `displayName`.

---

## CLI: using registry providers

### List available registries

```bash
shamash registry list
```

This prints all registry providers discoverable on the runtime classpath.

### Choose a registry for scan

```bash
shamash scan --registry default
shamash scan --registry <id>
```

Notes:

- `--registry` is **optional**. If omitted, Shamash uses `default`.
- Unknown id → CLI fails with a config error and prints the available registry ids.

---

## CLI: writing a custom registry provider

You implement:

```kotlin
interface AsmRuleRegistryProvider {
  val id: String
  val displayName: String
  fun create(): RuleRegistry
}
```

### Example provider (Kotlin)

```kotlin
package com.acme.shamash.registry

import io.shamash.asm.core.rules.DefaultRuleRegistry
import io.shamash.asm.core.rules.RuleRegistry
import io.shamash.asm.core.rules.spi.AsmRuleRegistryProvider

class AcmeRuleRegistryProvider : AsmRuleRegistryProvider {
  override val id: String = "acme"
  override val displayName: String = "Acme Rules"

  override fun create(): RuleRegistry {
    // Option A: start from default and add/override
    val base = DefaultRuleRegistry.create()

    // Add/override rules here depending on your RuleRegistry API
    // base.register(...)
    return base
  }
}
```

### ServiceLoader file (required)

Create this file in your jar:

**`src/main/resources/META-INF/services/io.shamash.asm.core.rules.spi.AsmRuleRegistryProvider`**

with one line:

```
com.acme.shamash.registry.AcmeRuleRegistryProvider
```

### Running with your provider jar

Your provider jar must be on the **runtime classpath** of the CLI.

Example (manual `java -cp`):

```bash
java -cp "shamash-cli.jar:acme-registry.jar" io.shamash.cli.MainKt registry list
java -cp "shamash-cli.jar:acme-registry.jar" io.shamash.cli.MainKt scan --registry acme --project .
```

---

## IntelliJ: using registry providers

### Choose registry in the UI

Shamash ASM exposes **Run Settings → Registry**.

- Default: `default`
- If other plugins contribute registries, they appear in the dropdown
- Missing registry provider → actionable error

---

## IntelliJ: writing a custom registry provider plugin

### 1) Implement provider

Your plugin implements Shamash’s EP interface (the interface exposed by the Shamash plugin for registry providers):

```kotlin
package com.acme.shamash.intellij

import io.shamash.asm.core.rules.DefaultRuleRegistry
import io.shamash.asm.core.rules.RuleRegistry
import io.shamash.intellij.plugin.asm.registry.AsmRuleRegistryProviderEp

class AcmeRegistryProvider : AsmRuleRegistryProviderEp {
  override val id: String = "acme"
  override val displayName: String = "Acme Rules"

  override fun create(): RuleRegistry =
    DefaultRuleRegistry.create()
}
```

> The exact package name for the EP interface depends on the Shamash plugin module where it lives. Use your IDE “Go to declaration” from the Shamash EP interface to confirm the import.

### 2) Register in `plugin.xml`

```xml
<extensions defaultExtensionNs="io.shamash">
  <asmRuleRegistryProvider implementation="com.acme.shamash.intellij.AcmeRegistryProvider"/>
</extensions>
```

Install your plugin alongside Shamash and select it in **Run Settings → Registry**.

---

## Compatibility & versioning

Registry providers are **binary-coupled** to the Shamash API they compile against.

This implies:

- if Shamash changes the SPI or `RuleRegistry` API, providers must be rebuilt
- providers should declare compatibility (in release notes / plugin metadata)

Recommended practice:

- target a specific Shamash version range
- rebuild providers when you upgrade Shamash

---

## Known limitation: `asm-core` is not published to Maven

Currently third parties can’t easily compile a standalone CLI provider from scratch without one of these approaches:

- build against Shamash sources (vendored / included)
- build against an internal/company artifact repository
- build against a shipped “SDK” (zip containing the needed jars)
- deliver providers as IntelliJ plugins that compile against the Shamash plugin distribution

ServiceLoader/EP solve discovery, but **they do not solve how provider authors obtain compile-time dependencies**.

---

## Troubleshooting

### `registry list` shows only `default`

No other providers were found on the classpath.

Check your provider jar:

- is on the **runtime classpath**
- contains `META-INF/services/...AsmRuleRegistryProvider`
- the service file contains the correct fully-qualified class name
- your provider class is public and loadable (no missing deps)

### Provider crashes during `create()`

Shamash reports:

- provider id
- provider implementation class
- error stack (or message)

Then exits with a runtime error (CLI) or shows an error (IntelliJ).

---

