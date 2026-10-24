[English](../QUICK_START.md) · [Español](./QUICK_START.es.md) · [简体中文](./QUICK_START.zh-CN.md) · [Português (Brasil)](./QUICK_START.pt-BR.md) · [Русский](./QUICK_START.ru.md)

# Início rápido do Shamash

## 1. Compile o projeto JVM

```bash
./gradlew classes
# or
./mvnw package
```

## 2. Analise sem configuração

```bash
shamash scan
```

A primeira análise utiliza um perfil integrado de descoberta apenas para relatórios. Ela não altera arquivos do projeto, e os problemas encontrados não provocam falha do processo.

## 3. Ative a aplicação das regras

```bash
shamash init
shamash validate
```

Para os padrões de papéis e limites de arquitetura específicos do Spring:

```bash
shamash init --preset spring
```

Para obter a configuração completa de referência:

```bash
shamash init --preset reference
```

## 4. Projetos existentes: registre a dívida atual uma vez

```bash
shamash baseline create
```

Versione a configuração e a linha de base geradas. Depois:

```bash
shamash scan
```

identificará novas violações conforme as políticas de limite e linha de base configuradas.

## 5. Integração contínua

```yaml
- run: ./gradlew classes
- uses: aalsanie/shamash@v0.92.0
  with:
    config: shamash/configs/asm.yml
    fail-on: ERROR
```

A Action baixa `shamash-cli-0.92.0.zip`, verifica sua integridade com `SHA256SUMS.txt` e propaga o código de saída da análise.

## Opções úteis da CLI

```bash
shamash scan --all-findings
shamash scan --verbose
shamash scan --print-analysis-summary
shamash scan --fail-on WARNING
shamash scan --fail-on NONE
```

`--print-findings` continua disponível para compatibilidade com a série 0.x, mas não é mais necessário, pois os problemas são exibidos por padrão.

## IntelliJ

Instale o plugin e abra:

```text
Tools → Shamash
```

Use **Build Analysis** para verificar o código compilado e **Source Analysis** para diagnósticos e correções no código-fonte diretamente na IDE.

## Verificação de integridade

Cada versão disponibiliza um arquivo único de somas de verificação:

```text
SHA256SUMS.txt
```

Linux:

```bash
grep 'shamash-cli-0.92.0.zip' SHA256SUMS.txt | sha256sum -c -
```

macOS:

```bash
EXPECTED=$(awk '/shamash-cli-0.92.0.zip/ {print $1}' SHA256SUMS.txt)
ACTUAL=$(shasum -a 256 shamash-cli-0.92.0.zip | awk '{print $1}')
test "$EXPECTED" = "$ACTUAL"
```

Windows PowerShell:

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
