<p align="center">
  <a href="../README.md">English</a> •
  <a href="./README.zh-Hans.md">简体中文</a>
</p>

<p align="center">
  <img src="../assets/shamash_logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.90.0-green)](https://github.com/aalsanie/shamash/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-blue)](https://plugins.jetbrains.com/plugin/29504-shamash) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html) | ![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
| ![Plugin Verify](https://github.com/aalsanie/shamash/actions/workflows/plugin.yml/badge.svg)


# Shamash
Shamash 是一个 JVM 架构执行工具，可帮助团队定义、验证和持续执行架构边界。
它通过及早发现禁止的依赖项和循环来防止 JVM 代码库中的架构漂移。

### Shamash 的适用场景

- 阻止层违规（控制器 → 存储库，服务 → Web 等）
- 检测依赖循环并显示代表性的循环路径
- 在重构/迁移期间捕获模块边界中断
- 为 CI/PR 可见性生成 SARIF/HTML/JSON/XML 报告

### 两个引擎

- PSI（来源）：仪表板、抑制、引导式修复
- ASM（字节码）：确定性的“发布内容”验证 + CI 门禁 + 导出


### 在 60 秒内尝试（IntelliJ）

- [下载](https://plugins.jetbrains.com/plugin/29504-shamash)
- 工具 → Shamash ASM 仪表板
- 创建 ASM 配置（从参考）
- 构建 (./gradlew assemble)
- 运行 ASM 扫描 → 结果 + 导出在 .shamash/ 中

---

## 为什么需要两个引擎？

当您需要感知源代码的反馈时，请使用 PSI（IDE 原生仪表板、抑制、引导式修复）。
当您需要构建工件的真实信息时，请使用 ASM（字节码级别的真实性、JAR 可见性、CI 友好的扫描）。

大多数团队同时运行两者：

- PSI 用于日常开发反馈
- ASM 用于“实际发布的内容”的字节码验证

### 两个引擎都可以通过以下方式配置
- ASM 读取 asm.yml
- PSI 读取 psi.yml

这些 YAML 配置文件定义了角色、规则、范围、验证行为、导出，以及（启用时）分析输出，如图形/热点/分数。

---

<details>
  <summary><b>Demo (GIF)</b></summary>

  <br/>

![Shamash IntelliJ demo](../assets/shamash-demo.gif)
</details>

---

## 它涵盖的内容

<details>
  <summary><b>显示详情</b></summary>
  <br/>

### 架构执行

- 角色（例如，控制器/服务/存储库）和放置规则
- 禁止的依赖关系（角色 → 角色，包 → 包，模块 → 模块）
- 依赖循环（带有代表性的循环路径）
- 使用清晰的、路径感知的错误进行配置验证

### 分析输出
- 依赖/调用图分析（可配置粒度）
- 热点和评分（架构健康指标）
- 可导出的报告（JSON / SARIF / HTML / XML）

### 字节码分析
- 死代码检测
- 弃用/影子使用检测
- 额外的 JVM 内部可见性和高级检查

</details>

---

## 文档和示例

文档 + 测试平台应用程序: [`./docs/`](../docs)

---

## Gradle kotlin DSL
看: [Quick Start — Gradle Kotlin DSL](../QUICK_START.md#gradle-kotlin-dsl)

```shell
# one-time: generate the starter config in shamash/configs/asm.yml
gradlew shamashInit

# validate config
gradlew shamashValidate

# run scan gate (also runs on ./gradlew check now)
gradlew shamashScan
gradlew check
```

---

## 快速开始 (CLI + intellij plugin + gradle DSL)
看 [QUICK_START](../QUICK_START.md)

## More
[JVM architecture enforcement in IDE + CI](https://open.substack.com/pub/aalsanie/p/shamash-architecture-enforcement?utm_campaign=post-expanded-share&utm_medium=web)