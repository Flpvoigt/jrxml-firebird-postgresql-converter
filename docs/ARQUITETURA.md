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
- `report`: gera o relatório CSV e o resumo textual.

## Regras de segurança

- Nunca modificar arquivos do diretório de entrada.
- Recusar um diretório de saída igual ou interno ao diretório de entrada.
- Desabilitar entidades externas e DTDs durante a leitura XML.
- Preservar o SQL original quando uma consulta não puder ser convertida.
- Não converter isoladamente fragmentos ou SQLs montados por concatenação.
- Registrar arquivo e linha dos scripts que exigem revisão manual.
- Retornar código de saída 3 quando existir falha ou revisão pendente.

## Limites atuais

A análise confirma a conversão sintática e preserva a estrutura dos arquivos.
A homologação funcional exige executar relatórios e scripts contra o PostgreSQL
real, com schema, functions, parâmetros e dados utilizados pelo cliente.
