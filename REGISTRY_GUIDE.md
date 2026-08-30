# Registry Guide

Shamash ships with the built-in `default` ASM rule registry. Registry selection is optional; if no registry is selected, Shamash uses the built-in registry.

A registry defines which ASM rules are available and how they execute. It is separate from `shamash/configs/asm.yml`, which selects and configures rules for a project.

## CLI registry providers

The CLI discovers external registries with Java `ServiceLoader`.

Implement:

```kotlin
package com.acme.shamash

import io.shamash.asm.core.engine.rules.DefaultRuleRegistry
import io.shamash.asm.core.engine.rules.RuleRegistry
import io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider

class AcmeRuleRegistryProvider : AsmRuleRegistryProvider {
    override val id: String = "acme"
    override val displayName: String = "Acme Rules"

    override fun create(): RuleRegistry = DefaultRuleRegistry.create()
}
```

Register the provider in:

```text
src/main/resources/META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

with the provider's fully qualified class name:

```text
com.acme.shamash.AcmeRuleRegistryProvider
```

The provider JAR must be on the Shamash CLI runtime classpath. With the packaged CLI, place the JAR in the distribution's `lib` directory before starting Shamash.

List available registries:

```bash
shamash registry list
```

Select one for a scan:

```bash
shamash scan --registry acme
```

An unknown registry id is a configuration error and Shamash prints the available ids.

## IntelliJ registry providers

The IntelliJ plugin exposes this extension point:

```text
io.shamash.asmRuleRegistryProvider
```

It uses the same interface as the CLI:

```text
io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

A companion IntelliJ plugin can implement that interface and register the implementation:

```xml
<idea-plugin>
    <depends>io.shamash</depends>

    <extensions defaultExtensionNs="io.shamash">
        <asmRuleRegistryProvider implementation="com.acme.shamash.AcmeRuleRegistryProvider"/>
    </extensions>
</idea-plugin>
```

Contributed registries appear in Shamash's registry selection UI. Duplicate or blank ids are ignored and reported by the plugin.

## Compatibility

Registry providers are binary-coupled to the Shamash rule-registry API they compile against. Providers should target an explicit Shamash version range and be rebuilt when that API changes.

`shamash-asm-core` is not currently published as a public Maven artifact. External provider authors therefore need a build-time copy of the compatible Shamash API, such as the project sources or artifacts produced from the matching Shamash release. ServiceLoader and the IntelliJ extension point solve runtime discovery; they do not provide the compile-time dependency.
