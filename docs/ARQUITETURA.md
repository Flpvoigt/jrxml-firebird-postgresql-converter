# Arquitetura do conversor JRXML e Groovy

## Objetivo

O projeto converte consultas SQL presentes em relatórios JasperReports e scripts
Groovy do dialeto Firebird para PostgreSQL. O diretório de origem é tratado como
somente leitura e cada tipo de arquivo é gravado em uma saída separada.

## Responsabilidades

- `cli`: interpreta e valida os argumentos da linha de comando.
- `service`: coordena a descoberta, leitura, conversão e gravação dos arquivos.
- `xml`: faz a leitura segura do XML e localiza referências de subreports.
- `jrxml`: substitui exclusivamente o conteúdo das tags `queryString`.
- `groovy`: identifica literais que contêm SQL, protege interpolações Groovy e
  altera somente os SQLs completos que puderem ser analisados com segurança.
- `sql`: protege expressões Jasper, converte o SQL e restaura os elementos preservados.
- `domain`: representa resultados, estados e resumos da execução.
- `report`: gera CSVs apenas com pendências, um relatório legível de revisão
  dos Groovys e o resumo textual completo da execução.

## Regras de segurança

- Nunca modificar arquivos do diretório de entrada.
- Aceitar como entrada um arquivo isolado ou uma árvore inteira de diretórios.
- Recusar um diretório de saída igual ou interno ao diretório de entrada.
- Desabilitar entidades externas e DTDs durante a leitura XML.
- Preservar o SQL original quando uma consulta não puder ser convertida.
- Isolar falhas por arquivo para que um arquivo defeituoso não interrompa o lote.
- Ignorar explicitamente linguagens Jasper que não sejam SQL, como JSON e XPath.
- Não converter isoladamente fragmentos ou SQLs montados por concatenação.
- Preservar a estrutura relativa de Groovy e JRXML, inclusive para arquivos com
  nomes iguais em diretórios diferentes.
- Ao receber um master isolado, seguir subreports literais locais sem sair da
  pasta permitida pela entrada.
- No modo multiprojeto, separar a saída pela primeira pasta da entrada.
- Limpar as árvores JRXML e Groovy processadas quando `--overwrite` for usado.
- Ignorar diretórios gerados e metadados de ferramentas durante a descoberta.
- Registrar arquivo e linha dos scripts que exigem revisão manual.
- Manter nos CSVs somente estados acionáveis (`REVIEW` e `FAILED`); os totais
  dos demais estados permanecem no resumo da execução.
- Retornar código de saída 3 quando existir falha ou revisão pendente.

## Estratégias de conversão

A estratégia principal usa o parser do jOOQ com entrada Firebird e renderização
PostgreSQL. Antes das transformações, textos, comentários, parâmetros Jasper e
interpolações Groovy recebem tokens temporários; depois da conversão, os
conteúdos originais são restaurados.

Uma segunda estratégia, usada somente quando um SQL Groovy dinâmico não pode ser
analisado integralmente, aplica transformações determinísticas sem reorganizar a
consulta. Essa contingência somente produz saída quando remove construções
Firebird conhecidas e não encontra outra construção incompatível restante.

Para GStrings sem sintaxe Firebird, uma AST do Groovy acompanha declarações e
atribuições anteriores de filtros opcionais. Ela reconhece cláusulas como
`"AND ..."`, predicados como `"CAMPO IN (...)"`, alternativas vazias e valores
escalares interpolados dentro de `VALUES`. A AST é criada apenas até a fase de
conversão sintática: o script não é compilado nem executado. Se o arquivo não
puder ser analisado, o processador volta à análise textual conservadora; uma
definição ainda desconhecida permanece para revisão manual.

A AST não substitui o jOOQ. Ela entende a estrutura do código Groovy e fornece
contexto para as interpolações; o jOOQ continua responsável pela análise e pela
conversão do SQL Firebird para PostgreSQL.

As regras adicionais cobrem paginação no nível principal e em subconsultas,
funções selecionáveis no `FROM`, `DATEADD`, `DATEDIFF`, `LIST`, `GEN_ID`,
`ASCII_CHAR`, `STARTING WITH`, `CONTAINING`, `WITH LOCK`, `RDB$DATABASE` e o
upsert com `MATCHING` explícito. Uma validação posterior impede o estado
`CONVERTED` quando ainda existir sintaxe Firebird conhecida fora de textos e
comentários.

## Limites atuais

A análise confirma a conversão sintática e preserva a estrutura dos arquivos.
A homologação funcional exige executar relatórios e scripts contra o PostgreSQL
real, com schema, functions, parâmetros e dados utilizados pelo cliente.

Upserts sem `MATCHING` precisam de metadados de chave primária ou única. Fluxos
Groovy que constroem um SQL por concatenação ou em várias atribuições exigem uma
etapa futura de análise de fluxo do código; converter cada literal isoladamente
poderia mudar a posição de cláusulas e gerar SQL inválido.

`FIRST/SKIP` em ramos de `UNION`, `INTERSECT` ou `EXCEPT` permanece para revisão
até que a árvore da operação de conjunto seja transformada sem alterar o escopo
do limite. Expressões Jasper `$X{...}` que contenham SQL Firebird interno também
são recusadas quando a construção incompatível não puder ser convertida com
segurança.
