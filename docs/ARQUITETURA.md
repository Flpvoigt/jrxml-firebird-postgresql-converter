# Arquitetura do JRXML Converter

## Objetivo

O projeto converte consultas SQL presentes em relatórios JasperReports do
dialeto Firebird para PostgreSQL. O diretório de origem é tratado como somente
leitura e toda conversão é gravada em um diretório de saída separado.

## Responsabilidades

- `cli`: interpreta e valida os argumentos da linha de comando.
- `service`: coordena a descoberta, leitura, conversão e gravação dos arquivos.
- `xml`: faz a leitura segura do XML e localiza referências de subreports.
- `jrxml`: substitui exclusivamente o conteúdo das tags `queryString`.
- `sql`: protege expressões Jasper, converte o SQL e restaura os elementos preservados.
- `domain`: representa resultados, estados e resumos da execução.
- `report`: gera o relatório CSV e o resumo textual.

## Regras de segurança

- Nunca modificar arquivos do diretório de entrada.
- Recusar um diretório de saída igual ou interno ao diretório de entrada.
- Desabilitar entidades externas e DTDs durante a leitura XML.
- Preservar o SQL original quando uma consulta não puder ser convertida.
- Retornar código de saída 3 quando existir alguma falha de conversão.

## Limites atuais

A análise confirma a conversão sintática e a integridade estrutural do JRXML.
A homologação funcional exige executar os relatórios contra o PostgreSQL real,
com o schema, as functions e os parâmetros utilizados pelo cliente.
