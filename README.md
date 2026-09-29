# Conversor JRXML e Groovy

Ferramenta Java 21 para localizar consultas SQL em relatórios JasperReports
(`.jrxml`) e scripts (`.groovy`) e convertê-las do dialeto Firebird para
PostgreSQL usando o parser do jOOQ. A entrada é sempre somente leitura; os
arquivos convertidos são gravados em outro diretório.

## Metadados de schema

O conversor aceita opcionalmente `--schema-metadata <arquivo.json>`. Esse arquivo permite
converter `UPDATE OR INSERT` sem `MATCHING`, usando a chave primária extraída do Firebird
pela ferramenta de migração.

```json
{
  "formatVersion": 1,
  "tables": {
    "PRODUTOS": {
      "primaryKey": ["COD_EMPRESA", "COD_PRODUTO"]
    }
  }
}
```

Sem os metadados, ou quando um Groovy declara uma conexão JDBC própria, a consulta é
preservada e enviada para revisão.

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

O `--input` aceita tanto uma pasta quanto um único arquivo `.jrxml` ou `.groovy`.
Ao receber uma pasta, a busca é recursiva e pode processar os dois tipos na mesma
execução. Quando a entrada é um JRXML master isolado, subreports literais locais
(`"arquivo.jrxml"` ou `"arquivo.jasper"`) também são descobertos recursivamente.

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
├── jrxml\                         # JRXML convertidos e relatórios próprios
│   ├── conversion-report.csv
│   └── review-required.txt         # pendências JRXML em formato legível
├── groovy\                        # Groovy com estrutura relativa e relatório próprio
│   ├── conversion-report.csv
│   └── review-required.txt         # pendências Groovy em formato legível
└── conversion-summary.txt         # resumo geral
```

Os arquivos `conversion-report.csv` contêm somente itens que exigem atenção
(`REVIEW` ou `FAILED`). Conversões concluídas, consultas vazias e linguagens não
SQL continuam contabilizadas no `conversion-summary.txt`, mas não poluem os
CSVs. O arquivo `groovy/review-required.txt` descreve cada pendência Groovy em
linguagem orientada à correção: o que foi encontrado, por que não houve
conversão, qual o risco no PostgreSQL, como resolver, o detalhe técnico e um
trecho limitado do SQL original.

O diretório `jrxml` também recebe um `review-required.txt` com a mesma estrutura
explicativa para cada queryString que falhou ou exige revisão.

SQLs Groovy montados por concatenação ou em fragmentos são preservados sem
alteração e recebem o estado `REVIEW` no CSV, com arquivo e linha. Falhas e
itens para revisão fazem o processo encerrar com código 3.

Uma falha de leitura ou um JRXML inválido é registrada no CSV sem interromper os
demais arquivos do lote. Nesse caso, o arquivo problemático é copiado sem
alteração para a saída, permitindo corrigir a origem posteriormente.

As `queryString` cuja linguagem declarada seja `JSON`, `xPath` ou outra que não
seja SQL são preservadas e registradas como `IGNORED`; elas não representam
falha de conversão. Consultas SQL sem CDATA também são aceitas, com o conteúdo
escapado novamente como XML depois da conversão.

Para SQLs Groovy com trechos dinâmicos, o conversor possui uma etapa conservadora
de contingência. Ela altera somente construções Firebird determinísticas, como
`FIRST/SKIP`, `DATEADD`, `DATEDIFF`, `LIST`, `GEN_ID`, `ASCII_CHAR`,
`RDB$DATABASE`, `STARTING WITH`, `CONTAINING` e `WITH LOCK`, mantendo as
interpolações Groovy.
Se não for possível provar que a alteração é segura, o conteúdo original é
preservado e recebe `REVIEW`.

Uma análise por AST do Groovy acompanha definições anteriores usadas nas
GStrings, sem executar o script. Isso permite distinguir filtros opcionais e
valores escalares de cláusulas realmente desconhecidas. Se a AST não conseguir
ler um arquivo, a análise textual anterior continua disponível como fallback.
O jOOQ permanece sendo o responsável pela conversão do SQL.

Quando uma GString já é compatível com PostgreSQL, o processador acompanha
definições anteriores de filtros opcionais simples, como
`def empresa = condição ? "AND ..." : ""`. Se todas as cláusulas dinâmicas
isoladas forem compreendidas, a consulta é preservada exatamente e não entra no
relatório de revisão. Definições produzidas por métodos ou fluxos desconhecidos
continuam em `REVIEW`.

Os Groovy e JRXML preservam seus caminhos relativos, evitando colisões entre
arquivos homônimos e mantendo as relações entre relatórios master e subreports.
Com `--overwrite`, as árvores `groovy` e `jrxml` dos projetos processados são
limpas antes da nova geração, impedindo que arquivos antigos permaneçam na saída.

Quando a entrada contém vários projetos, o conversor identifica raízes por
marcadores comuns (`pom.xml`, Gradle, `.git` ou `src/main`) e ativa
automaticamente o modo agrupado. Cada primeira subpasta ganha uma saída própria.
Dentro de cada tipo, o caminho abaixo de `resources` é preservado, evitando
conflito entre arquivos homônimos:

```text
output\conversao-geral\
├── besser-complements-102\
│   ├── groovy\besser-core\endpoints\tray\
│   └── jrxml\besser-core\reports\
└── besser-complements-1068\
    ├── groovy\besser-core\endpoints\
    └── jrxml\besser-core\reports\
```

Pastas geradas ou de ferramentas (`target`, `build`, `output`, `.git`, `.idea` e
`node_modules`) não participam da busca.

## Limite da validação

A conversão garante estrutura XML/Groovy e tradução sintática dos SQLs completos
identificados. A validação final ainda deve executar as consultas com parâmetros
reais no PostgreSQL, compilar/renderizar os JRXML e exercitar os scripts Groovy
no ambiente utilizado pelo cliente.

Depois da renderização, o conversor procura construções Firebird conhecidas que
tenham permanecido fora de textos e comentários. Nesses casos o resultado é
`FAILED` e o SQL original é mantido. Paginação `FIRST/SKIP` combinada com
`UNION`, `INTERSECT` ou `EXCEPT` também exige revisão, pois o limite pode
pertencer a um ramo específico da operação.

`UPDATE OR INSERT` sem `MATCHING` permanece para revisão: no Firebird a chave
pode ser obtida da chave primária da tabela, enquanto o PostgreSQL exige um alvo
de conflito para `DO UPDATE`. Automatizar esse caso corretamente requer os
metadados do schema ou um arquivo de mapeamento de chaves. SQLs montados por
concatenação e erros sintáticos já existentes na origem também não são
consertados por suposição.

## Uso

Código de uso interno. A publicação do repositório não concede licença para
copiar, modificar ou redistribuir o projeto.
