# Conversor JRXML e Groovy

Ferramenta Java 21 para localizar consultas SQL em relatórios JasperReports
(`.jrxml`) e scripts (`.groovy`) e convertê-las do dialeto Firebird para
PostgreSQL usando o parser do jOOQ. A entrada é sempre somente leitura; os
arquivos convertidos são gravados em outro diretório.

## Estrutura

```text
src/main/java/br/com/jjw/jrxmlconverter/
├── JrxmlConverterApplication.java   # entrada da aplicação e composição
├── cli/                             # interpretação da linha de comando
├── domain/                          # resultados e estados do domínio
├── groovy/                          # localização e alteração segura de SQL em Groovy
├── jrxml/                           # alteração controlada do JRXML
├── report/                          # CSV e resumo da execução
├── service/                         # orquestração do processamento
├── sql/                             # conversão Firebird → PostgreSQL
└── xml/                             # XML seguro e inspeção de subreports
```

Os testes ficam em `src/test/java`, seguindo a mesma organização de pacotes.
Uma descrição das responsabilidades e decisões técnicas está disponível em
[`docs/ARQUITETURA.md`](docs/ARQUITETURA.md).

## Requisitos

- JDK 21
- Maven 3.6 ou superior

## Compilar e testar

```powershell
mvn clean verify
```

O JAR executável será criado em:

```text
target\jrxml-converter-0.2.0-SNAPSHOT.jar
```

## Analisar sem escrever arquivos

```powershell
java -jar target\jrxml-converter-0.2.0-SNAPSHOT.jar `
  --input "C:\relatorios\firebird" `
  --dry-run
```

## Gerar arquivos convertidos

```powershell
java -jar target\jrxml-converter-0.2.0-SNAPSHOT.jar `
  --input "C:\relatorios\firebird" `
  --output ".\output\postgresql"
```

Nas execuções seguintes, acrescente `--overwrite`. O programa nunca altera a
entrada. A saída é organizada assim:

```text
output\postgresql\
├── jrxml\                         # JRXML convertidos e relatório próprio
│   └── conversion-report.csv
├── groovy\                        # Groovy convertidos e relatório próprio
│   └── conversion-report.csv
└── conversion-summary.txt         # resumo geral
```

SQLs Groovy montados por concatenação ou em fragmentos são preservados sem
alteração e recebem o estado `REVIEW` no CSV, com arquivo e linha. Falhas e
itens para revisão fazem o processo encerrar com código 3.

## Limite da validação

A conversão garante estrutura XML/Groovy e tradução sintática dos SQLs completos
identificados. A validação final ainda deve executar as consultas com parâmetros
reais no PostgreSQL, compilar/renderizar os JRXML e exercitar os scripts Groovy
no ambiente utilizado pelo cliente.

## Uso

Código de uso interno. A publicação do repositório não concede licença para
copiar, modificar ou redistribuir o projeto.
