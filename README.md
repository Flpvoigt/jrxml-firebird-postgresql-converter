# JRXML Converter

Ferramenta Java 21 para localizar consultas SQL em relatórios JasperReports
(`.jrxml`) e convertê-las do dialeto Firebird para PostgreSQL usando o parser do
jOOQ. A entrada é sempre somente leitura; os arquivos convertidos são gravados
em outro diretório, mantendo a estrutura relativa dos relatórios e subreports.

## Estrutura

```text
src/main/java/io/github/flpvoigt/jrxmlconverter/
├── JrxmlConverterApplication.java   # entrada da aplicação e composição
├── cli/                             # interpretação da linha de comando
├── domain/                          # resultados e estados do domínio
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

## Gerar relatórios convertidos

```powershell
java -jar target\jrxml-converter-0.2.0-SNAPSHOT.jar `
  --input "C:\relatorios\firebird" `
  --output ".\output\postgresql"
```

Nas execuções seguintes, acrescente `--overwrite`. O programa nunca altera a
entrada. Falhas individuais preservam o SQL original, aparecem em
`conversion-report.csv` e fazem o processo encerrar com código 3.

## Limite da validação

A conversão garante estrutura XML e tradução sintática. A validação final ainda
deve executar as consultas com parâmetros reais no PostgreSQL de destino e
compilar/renderizar os JRXML com a versão de Jasper utilizada no ambiente.

## Contribuição

Contribuições são bem-vindas. Consulte o
[`CONTRIBUTING.md`](CONTRIBUTING.md) antes de abrir uma issue ou pull request.

## Licença

Distribuído sob a licença MIT. Consulte [`LICENSE`](LICENSE).
