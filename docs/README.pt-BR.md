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

**Verifique a arquitetura de projetos Java e Kotlin sem escrever testes de arquitetura.**

Detecte ciclos de dependências, aplique limites entre pacotes e papéis arquiteturais e identifique pontos de alto acoplamento. Analise código compilado pela CLI ou na CI e obtenha diagnósticos e correções no código-fonte pelo IntelliJ.

[![Release](https://img.shields.io/github/v/release/aalsanie/shamash?label=release)](https://github.com/aalsanie/shamash/releases)
![CI](https://github.com/aalsanie/shamash/actions/workflows/ci.yml/badge.svg)
[![Licença](https://img.shields.io/badge/license-Apache%202.0-4EB1BA.svg)](../LICENSE)

## Primeira análise

Requer **Java 17 ou superior** e classes compiladas. Baixe o [arquivo da CLI e as somas de verificação](https://github.com/aalsanie/shamash/releases/latest), verifique o arquivo e adicione o diretório `bin` ao `PATH` (`shamash` no Unix, `shamash.bat` no Windows).

```bash
./gradlew classes  # or ./mvnw package
shamash scan
```

Sem configuração, a análise apresenta os problemas encontrados, mas não altera arquivos do projeto nem falha apenas por detectar violações de arquitetura.

## Aplicar regras

```bash
shamash init
shamash validate
shamash scan --fail-on ERROR
```

`init` cria `shamash/configs/asm.yml` com uma regra de ciclos de dependências. Use `shamash init --preset spring` para os padrões do Spring ou `--preset reference` para o exemplo completo.

Em projetos existentes, execute `shamash baseline create` para aceitar os problemas atuais. Versione a configuração atualizada e `.shamash/baseline/asm-baseline.json`; as análises seguintes identificarão novas violações.

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

Para projetos Maven, substitua a etapa de compilação por `./mvnw package`. A Action verifica a soma de verificação da CLI antes de executar a análise.

**Códigos de saída:** `0` análise concluída abaixo do limite configurado; `2` erro de configuração ou de entrada; `3` análise com falha ou incompleta; `4` problemas encontrados atingiram ou ultrapassaram o limite.

## IntelliJ

[Instale pelo JetBrains Marketplace](https://plugins.jetbrains.com/plugin/29504-shamash) e abra **Tools → Shamash**. **Build Analysis** verifica o código compilado; **Source Analysis** oferece diagnósticos no código-fonte, supressões e correções.

## Biblioteca

Disponível no Maven Central para Java 17 ou superior.

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

`isSuccess` confirma a conclusão da análise, não a ausência de violações.

## Recursos avançados

Configure restrições de APIs e anotações, regras de origem de JARs, métricas de dependências e verificações de grafos. Exporte relatórios JSON, SARIF, HTML ou XML; examine fatos, pontos críticos e pontuações de arquitetura. Amplie o conjunto de regras com registros personalizados.

[Opções da CLI e somas de verificação](./QUICK_START.pt-BR.md) · [Exemplos de configuração](../docs/asm/schema/) · [Registros personalizados](./REGISTRY_GUIDE.pt-BR.md) · [Testes de desempenho](../benchmarks/)

[Histórico de alterações](../CHANGELOG.md) · [Segurança](../SECURITY.md) · [Como contribuir](../CONTRIBUTING.md) · [License](../LICENSE)
