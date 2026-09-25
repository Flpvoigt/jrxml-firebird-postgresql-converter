# Como contribuir

Obrigado pelo interesse em melhorar o JRXML Converter.

## Preparação do ambiente

1. Instale o JDK 21 e o Maven 3.6 ou superior.
2. Crie um fork do repositório.
3. Crie uma branch curta e descritiva.
4. Execute `mvn clean verify` antes de enviar alterações.

## Regras para mudanças

- Mantenha o diretório de entrada estritamente como somente leitura.
- Inclua testes para novas regras de conversão.
- Não adicione JRXML, SQL, credenciais ou nomes pertencentes a clientes reais.
- Documente limitações conhecidas e conversões que exijam revisão manual.
- Preserve parâmetros Jasper como `$P{}`, `$P!{}` e `$X{}`.

## Pull requests

Explique o problema, a solução adotada e como a mudança foi validada. Mudanças
grandes devem ser discutidas previamente em uma issue.
