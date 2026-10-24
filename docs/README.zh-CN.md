<p align="center">
  <a href="../README.md">English</a> •
  <a href="./README.es.md">Español</a> •
  <a href="./README.zh-CN.md">简体中文</a> •
  <a href="./README.pt-BR.md">Português (Brasil)</a> •
  <a href="./README.ru.md">Русский</a>
</p>

<p align="center">
  <img src="../assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

**无需编写架构测试，即可检查 Java 和 Kotlin 项目的架构。**

检测依赖环、约束包与架构角色之间的边界，并识别高耦合区域。通过命令行或 CI 检查已编译代码，也可在 IntelliJ 中查看源码级问题并使用修复功能。

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![许可证](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](../LICENSE)

## 首次扫描

需要 **Java 17 或更高版本**和已编译的类文件。从[发布页面下载命令行工具及校验和文件](https://github.com/aalsanie/shamash/releases/latest)，验证压缩包后，将其 `bin` 目录加入 `PATH`（Unix 使用 `shamash`，Windows 使用 `shamash.bat`）。

```bash
./gradlew classes  # or ./mvnw package
shamash scan
```

未提供配置时，扫描会显示发现的问题，但不会写入项目文件，也不会仅因架构违规而返回失败状态。

## 启用规则检查

```bash
shamash init
shamash validate
shamash scan --fail-on ERROR
```

`init` 会创建包含依赖环检测规则的 `shamash/configs/asm.yml`。使用 `shamash init --preset spring` 生成 Spring 默认规则，或使用 `--preset reference` 获取完整示例。

对于现有项目，运行 `shamash baseline create` 将当前问题纳入基线。将更新后的配置和 `.shamash/baseline/asm-baseline.json` 提交到版本控制；后续扫描将报告新增违规。

## GitHub Actions

```yaml
name: Architecture

on: [pull_request, push]

jobs:
  architecture:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - run: ./gradlew classes
      - uses: aalsanie/shamash@v0.92.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

Maven 项目请将构建步骤替换为 `./mvnw package`。此 Action 会在扫描前验证下载的命令行工具的校验和。

**退出码：** `0` 扫描完成且未达到失败阈值；`2` 配置或输入错误；`3` 扫描失败或不完整；`4` 发现的问题达到或超过失败阈值。

## IntelliJ

从 [JetBrains Marketplace 安装插件](https://plugins.jetbrains.com/plugin/29504-shamash)，然后打开 **Tools → Shamash**。**Build Analysis** 检查已编译代码；**Source Analysis** 提供源码级问题定位、抑制规则和修复功能。

## 库集成

已发布至 Maven Central，要求 Java 17 或更高版本。

**Gradle**
```kotlin
dependencies {
    implementation("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

**Maven**
```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
</dependency>
```

```kotlin
import io.shamash.asm.core.scan.ScanOptions
import io.shamash.asm.core.scan.ShamashAsmScanRunner
import java.nio.file.Path

val result = ShamashAsmScanRunner().run(ScanOptions(projectBasePath = Path.of(".")))
check(result.isSuccess) { result.toString() }
val findings = requireNotNull(result.engine).findings
```

`isSuccess` 表示扫描已成功完成，并不表示不存在架构违规。

## 进阶功能

可配置 API 与注解限制、JAR 来源规则、依赖度量和依赖图检查。支持导出 JSON、SARIF、HTML、XML 报告，以及查看事实数据、热点与架构评分；还可扩展自定义规则注册表。

[命令行选项与校验和](./QUICK_START.zh-CN.md) · [配置示例](../docs/asm/schema/) · [自定义规则注册表](./REGISTRY_GUIDE.zh-CN.md) · [性能基准测试](../benchmarks/)

[更新日志](../CHANGELOG.md) · [安全政策](../SECURITY.md) · [参与贡献](../CONTRIBUTING.md) · [License](../LICENSE)
