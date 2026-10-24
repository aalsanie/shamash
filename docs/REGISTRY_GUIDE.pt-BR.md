[English](../REGISTRY_GUIDE.md) · [Español](./REGISTRY_GUIDE.es.md) · [简体中文](./REGISTRY_GUIDE.zh-CN.md) · [Português (Brasil)](./REGISTRY_GUIDE.pt-BR.md) · [Русский](./REGISTRY_GUIDE.ru.md)

# Guia de registros de regras

O registro de regras ASM integrado é o `default`. Selecionar um registro é opcional; na ausência de uma seleção, o registro integrado é utilizado.

Um registro define quais regras ASM estão disponíveis e como são executadas. Ele é independente de `shamash/configs/asm.yml`, responsável por selecionar e configurar as regras de um projeto.

## Dependência

Os provedores de registros são compilados com `shamash-asm-core`, publicado no Maven Central.

Gradle:

```kotlin
dependencies {
    compileOnly("io.github.aalsanie:shamash-asm-core:0.92.0")
}
```

Maven:

```xml
<dependency>
    <groupId>io.github.aalsanie</groupId>
    <artifactId>shamash-asm-core</artifactId>
    <version>0.92.0</version>
    <scope>provided</scope>
</dependency>
```

Utilize a versão de destino do seu provedor. A CLI ou o plugin IntelliJ fornece `shamash-asm-core` em tempo de execução; o JAR do provedor não deve incluir outra cópia.

## Provedores de registros para a CLI

A CLI descobre registros externos por meio do `ServiceLoader` do Java.

Implemente:

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

Registre o provedor em:

```text
src/main/resources/META-INF/services/io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

com o nome completo da classe do provedor:

```text
com.acme.shamash.AcmeRuleRegistryProvider
```

O JAR do provedor deve estar no classpath de execução da CLI. Na distribuição empacotada, coloque o JAR no diretório `lib` antes de iniciar a ferramenta.

Liste os registros disponíveis:

```bash
shamash registry list
```

Selecione um registro para a análise:

```bash
shamash scan --registry acme
```

Um identificador de registro desconhecido gera um erro de configuração e exibe os identificadores disponíveis.

## Provedores de registros para IntelliJ

O plugin IntelliJ expõe o seguinte ponto de extensão:

```text
io.shamash.asmRuleRegistryProvider
```

Ele utiliza a mesma interface de provedor da CLI:

```text
io.shamash.asm.core.engine.rules.spi.AsmRuleRegistryProvider
```

Um plugin complementar do IntelliJ pode implementar essa interface e registrar sua implementação:

```xml
<idea-plugin>
    <depends>io.shamash</depends>

    <extensions defaultExtensionNs="io.shamash">
        <asmRuleRegistryProvider implementation="com.acme.shamash.AcmeRuleRegistryProvider"/>
    </extensions>
</idea-plugin>
```

Os registros adicionados aparecem no seletor de registros. Identificadores duplicados ou vazios são ignorados e informados pelo plugin.

## Compatibilidade

Os provedores dependem da compatibilidade binária com a API de registro de regras utilizada na compilação.

Compile com uma versão explícita de `shamash-asm-core` e execute o provedor com uma versão compatível. Se uma atualização modificar a API de registro, recompile e teste novamente o provedor.

O `ServiceLoader` e o ponto de extensão do IntelliJ permitem descobrir provedores em tempo de execução. O Maven Central fornece a dependência de API para a compilação.
