[English](../QUICK_START.md) · [Español](./QUICK_START.es.md) · [简体中文](./QUICK_START.zh-CN.md) · [Português (Brasil)](./QUICK_START.pt-BR.md) · [Русский](./QUICK_START.ru.md)

# Shamash 快速入门

## 1. 构建 JVM 项目

```bash
./gradlew classes
# or
./mvnw package
```

## 2. 无需配置即可扫描

```bash
shamash scan
```

首次扫描使用内置的仅报告模式，不会修改项目文件，发现架构问题也不会导致进程失败。

## 3. 启用规则检查

```bash
shamash init
shamash validate
```

如需使用 Spring 专用的角色和边界默认规则：

```bash
shamash init --preset spring
```

如需完整参考配置：

```bash
shamash init --preset reference
```

## 4. 为现有项目建立一次性基线

```bash
shamash baseline create
```

将生成的配置和基线提交到版本控制，然后运行：

```bash
shamash scan
```

即可根据所配置的阈值与基线策略识别新增违规。

## 5. 持续集成

```yaml
- run: ./gradlew classes
- uses: aalsanie/shamash@v0.92.0
  with:
    config: shamash/configs/asm.yml
    fail-on: ERROR
```

此 Action 会下载 `shamash-cli-0.92.0.zip`，使用 `SHA256SUMS.txt` 验证其完整性，并透传扫描的退出码。

## 常用命令行选项

```bash
shamash scan --all-findings
shamash scan --verbose
shamash scan --print-analysis-summary
shamash scan --fail-on WARNING
shamash scan --fail-on NONE
```

为兼容 0.x 版本，仍支持 `--print-findings`，但当前默认会输出发现的问题，无需额外指定。

## IntelliJ

安装插件后打开：

```text
Tools → Shamash
```

使用 **Build Analysis** 检查已编译代码，使用 **Source Analysis** 在 IDE 中获取源码级诊断与修复功能。

## 校验和验证

每个发布版本都提供统一的校验和文件：

```text
SHA256SUMS.txt
```

Linux：

```bash
grep 'shamash-cli-0.92.0.zip' SHA256SUMS.txt | sha256sum -c -
```

macOS：

```bash
EXPECTED=$(awk '/shamash-cli-0.92.0.zip/ {print $1}' SHA256SUMS.txt)
ACTUAL=$(shasum -a 256 shamash-cli-0.92.0.zip | awk '{print $1}')
test "$EXPECTED" = "$ACTUAL"
```

Windows PowerShell：

```powershell
$line = Get-Content .\SHA256SUMS.txt |
  Where-Object { $_ -match '\s+shamash-cli-0\.92\.0\.zip$' } |
  Select-Object -First 1
if (-not $line) { throw "Checksum entry not found" }
$expected = ($line -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash .\shamash-cli-0.92.0.zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($expected -ne $actual) { throw "Checksum mismatch" }
"Checksum OK"
```
