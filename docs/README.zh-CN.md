<p align="center">
  <img src="assets/shamash_logo.png" alt="Shamash" width="180"/>
</p>

# Shamash

**在架构漂移进入 main 分支之前阻止它。**

Shamash 扫描已编译的 Java/Kotlin 应用，发现依赖循环和架构违规，并且无需编写架构测试代码，就能在 CI 中阻止新的违规进入代码库。

- **CLI 优先：** 独立的 Java 17+ 工具，可用于本地开发和 CI。
- **首次扫描无需配置：** 在学习配置模型之前，就能先看到有价值的架构风险。
- **适合存量项目：** 只需为现有技术债建立一次基线，之后仅对新增违规失败。
- **IntelliJ：** 在一个工作区中提供 Build Analysis 和 Source Analysis。
- **按需使用高级能力：** 自定义角色/规则、facts、图、热点、registry 以及多种报告格式仍然可用。

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](../LICENSE)

## 使用方法

Shamash 分析已编译的字节码。请先构建项目：

```bash
./gradlew classes
# or: ./mvnw package
```

然后运行：

```bash
shamash scan
```

首次扫描不需要任何配置。发现模式仅用于报告：它不会在项目中创建配置、报告或基线，也不会因为发现问题而让命令失败。

输出示例：

```text
Shamash - discovery scan
Report-only mode. No project files were changed.

Shamash found 3 architecture issues

ERROR   graph.noCycles
        Dependency cycle detected ...

WARN    metrics.maxFanOut
        ...

642 classes scanned
1 errors, 2 warnings, 0 info

Ready to enforce architecture? Run: shamash init
```

如果 Shamash 找不到已编译的类，它会识别常见的 Gradle/Maven 项目，并给出应先执行的准确构建命令。

## 安装 CLI

需要 Java 17 或更高版本。

从 GitHub Releases 下载 `shamash-cli-<version>.zip` 和 `SHA256SUMS.txt`，验证校验和，解压后使用：

```text
bin/shamash      # Linux/macOS
bin/shamash.bat  # Windows
```

启动器名称属于已打包产品的稳定契约，并会在发布前于 Linux、Windows 和 macOS 上进行冒烟测试。

## 在项目中执行架构约束

创建精简的默认配置：

```bash
shamash init
```

它会写入：

```text
shamash/configs/asm.yml
```

默认 starter 配置刻意保持精简，并从依赖循环规则开始。特定框架的策略需要显式启用：

```bash
shamash init --preset spring
```

完整的高级参考配置仍然可用：

```bash
shamash init --preset reference
```

验证配置：

```bash
shamash validate
```

然后正常扫描：

```bash
shamash scan
```

默认会打印发现项。使用 `--all-findings` 查看完整列表，使用 `--verbose` 查看引擎诊断信息。

## 现有项目：一次性接受当前技术债

执行 `shamash init` 后，运行：

```bash
shamash baseline create
```

该命令会分析当前项目、写入已配置的基线，并确保 `baseline.mode` 为 `VERIFY`。已有基线会受到保护；如需替换，必须使用 `--force`。

将以下两个文件一起提交：

```text
shamash/configs/asm.yml
.shamash/baseline/asm-baseline.json
```

当基线模式为 `VERIFY` 时，后续扫描会抑制已接受的指纹，并暴露新的违规。

## GitHub Actions

先构建应用，然后使用官方 Action：

```yaml
name: Architecture

on:
  pull_request:
  push:
    branches: [main]

jobs:
  shamash:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"
      - run: ./gradlew classes
      - uses: aalsanie/shamash@v0.91.0
```

如需按配置执行架构约束：

```yaml
      - uses: aalsanie/shamash@v0.91.0
        with:
          config: shamash/configs/asm.yml
          fail-on: ERROR
```

Action 会在执行前验证发布文件的校验和。

## IntelliJ

从 JetBrains Marketplace 安装 **Shamash**，然后打开：

```text
Tools → Shamash
```

Shamash 只有一个工具窗口。一级区域包括：

- **Build Analysis** — 对已编译字节码执行架构检查，并展示发现项、角色、图和报告。
- **Source Analysis** — 提供基于源码的检查、抑制和修复。

ASM 和 PSI 在内部仍然存在，因为它们解决不同的技术问题；但用户无需理解这些引擎名称即可开始使用。

## CI 行为和退出码

配置模式下的扫描使用以下稳定退出码：

- `0` 扫描成功，且发现项低于失败阈值
- `2` 配置/输入问题（包括缺少已编译字节码）
- `3` 运行时/引擎失败
- `4` 发现项达到所选的 `--fail-on` 阈值

发现模式仅用于报告；成功完成扫描后，即使发现架构风险，也会返回 `0`。

## 高级能力

高级团队仍然可以使用：

- 架构角色依赖和包规则
- 依赖图规则和循环限制
- 耦合度/类大小指标
- API/注解限制
- JAR 来源限制
- facts 导出和 `shamash facts`
- 图/热点/评分分析和 `shamash analysis`
- JSON、SARIF、HTML 和 XML 报告格式
- 自定义规则 registry
- 例外和基线

高级引擎/配置参考请参阅 `docs/asm/` 和 `REGISTRY_GUIDE.md`。

## 安全

请不要在公开 issue 中披露漏洞。请遵循 [`SECURITY.md`](../SECURITY.md)。

## 许可证

Apache License 2.0。请参阅 [`LICENSE`](../LICENSE)。
