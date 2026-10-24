[English](../REGISTRY_GUIDE.md) · [Español](./REGISTRY_GUIDE.es.md) · [简体中文](./REGISTRY_GUIDE.zh-CN.md) · [Português (Brasil)](./REGISTRY_GUIDE.pt-BR.md) · [Русский](./REGISTRY_GUIDE.ru.md)

# 规则注册表指南

内置 ASM 规则注册表为 `default`。无需显式选择；未指定时将自动使用内置注册表。

注册表定义可用的 ASM 规则及其执行方式；`shamash/configs/asm.yml` 则负责为项目选择和配置规则，两者相互独立。

## 依赖

注册表提供者需要依赖 Maven Central 上发布的 `shamash-asm-core` 进行编译。

Gradle：

```kotlin
dependencies {
    compileOnly("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

Maven：

```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
    <scope>provided</scope>
</dependency>
```

应使用与提供者目标版本一致的依赖。运行时由命令行工具或 IntelliJ 插件提供 `shamash-asm-core`，因此提供者 JAR 不应重复打包该库。

## 命令行规则注册表提供者

命令行工具通过 Java 的 `ServiceLoader` 发现外部注册表。

实现以下接口：

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

在以下文件中注册提供者：

```text
src/main/resources/META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

文件内容为提供者的完全限定类名：

```text
com.acme.shamash.AcmeRuleRegistryProvider
```

提供者 JAR 必须位于命令行工具的运行时类路径中。使用已打包的 CLI 时，请先将 JAR 放入发行包的 `lib` 目录。

列出可用注册表：

```bash
shamash registry list
```

扫描时指定注册表：

```bash
shamash scan --registry acme
```

指定未知注册表 ID 会导致配置错误，同时列出可用 ID。

## IntelliJ 规则注册表提供者

IntelliJ 插件提供以下扩展点：

```text
io.shamash.asmRuleRegistryProvider
```

它与命令行工具使用相同的提供者接口：

```text
io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

配套的 IntelliJ 插件可以实现该接口并注册实现类：

```xml
<idea-plugin>
    <depends>io.shamash</depends>

    <extensions defaultExtensionNs="io.shamash">
        <asmRuleRegistryProvider implementation="com.acme.shamash.AcmeRuleRegistryProvider"/>
    </extensions>
</idea-plugin>
```

注册的规则集会显示在注册表选择界面中。插件会忽略重复或空白的 ID，并报告相应问题。

## 兼容性

注册表提供者与编译时使用的规则注册表 API 存在二进制兼容性约束。

明确指定编译时使用的 `shamash-asm-core` 版本，并确保运行时版本兼容。如果升级改变了规则注册表 API，需要重新编译并测试提供者。

`ServiceLoader` 与 IntelliJ 扩展点负责运行时发现，Maven Central 则提供编译所需的 API 依赖。
