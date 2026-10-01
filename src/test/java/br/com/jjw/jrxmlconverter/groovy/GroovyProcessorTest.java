package br.com.jjw.jrxmlconverter.groovy;

import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import br.com.jjw.jrxmlconverter.metadata.SchemaMetadata;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import groovy.lang.GroovyShell;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyProcessorTest {
    private final GroovyProcessor processor =
            new GroovyProcessor(new FirebirdToPostgresSqlConverter());

    @Test
    void doesNotApplyMainDatabaseKeyToGroovyWithOwnConnection() {
        var metadata = new SchemaMetadata(Map.of(
                "PRODUTOS", new SchemaMetadata.TableMetadata(List.of("COD_PRODUTO"))));
        var processorWithMetadata = new GroovyProcessor(
                new FirebirdToPostgresSqlConverter(metadata));
        String source = """
                def url = 'jdbc:firebirdsql:servidor:/dados/externo.fdb'
                def sql = \"\"\"UPDATE OR INSERT INTO PRODUTOS (COD_PRODUTO, NOME)
                    VALUES (1, 'Teste')\"\"\"
                """;

        var conversion = processorWithMetadata.convert(Path.of("integracao.groovy"), source);

        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertTrue(conversion.sqlResults().getFirst().message().contains("conexão própria"));
        assertEquals(source, conversion.source());
    }

    @Test
    void convertsCompleteSqlAndRestoresGStringExpressions() {
        String source = """
                def sql = \"\"\"
                    SELECT FIRST 1 DR.NOME
                    FROM DOCUMENTOS DR
                    WHERE DR.CODIGO = ${codigo}
                      AND DR.CHAVE = '${item.chave}'
                \"\"\"
                def resultado = queryList(sql, true)
                """;

        var conversion = processor.convert(Path.of("envio.groovy"), source);

        assertEquals(1, conversion.sqlResults().size());
        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertEquals(2, conversion.sqlResults().getFirst().dynamicExpressions());
        assertTrue(conversion.source().contains("${codigo}"), conversion.source());
        assertTrue(conversion.source().contains("'${item.chave}'"), conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("limit 1"),
                conversion.source());
    }

    @Test
    void generatesFirebirdAndPostgreSqlBranchesInDualDatabaseMode() {
        String source = "def sql = \"\"\"\n"
                + "    SELECT FIRST 1 P.CODIGO\n"
                + "    FROM PRODUTOS P\n"
                + "    WHERE P.CODIGO = ${codigo}\n"
                + "\"\"\"\n"
                + "def dados = queryList(sql)\n";

        var conversion = processor.convert(Path.of("produtos.groovy"), source, true);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertTrue(conversion.source().contains("isPostgreSql() ? \"\"\""), conversion.source());
        assertTrue(conversion.source().contains("SELECT FIRST 1 P.CODIGO"), conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("limit 1"),
                conversion.source());
        assertEquals(2, countOccurrences(conversion.source(), "${codigo}"), conversion.source());
        assertTrue(conversion.source().endsWith("def dados = queryList(sql)\n"),
                conversion.source());
    }

    @Test
    void convertsEscapedDollarInFirebirdSystemTableInsideGString() {
        String source = """
                def sql = \"\"\"
                    SELECT IIF(1 = 1, 'S', 'N') VALOR
                    FROM RDB\\$DATABASE
                \"\"\"
                """;

        var conversion = processor.convert(Path.of("sistema.groovy"), source, true);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertEquals(0, conversion.sqlResults().getFirst().dynamicExpressions());
        assertEquals(1, countOccurrences(conversion.source(), "RDB\\$DATABASE"), conversion.source());
        assertEquals(1, countOccurrences(conversion.source(), "IIF("), conversion.source());
        assertDoesNotThrow(() -> new GroovyShell().parse(conversion.source()), conversion.source());
    }

    @Test
    void usesMultilineSafeLiteralWhenOriginalSqlHasSingleQuotes() {
        String source = "def item = [sql: 'SELECT FIRST 1 COD_EMPRESA FROM EMPRESAS']\n";

        var conversion = processor.convert(Path.of("empresa.groovy"), source, true);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertTrue(conversion.source().contains("isPostgreSql() ? '''"), conversion.source());
        assertTrue(conversion.source().contains(": 'SELECT FIRST 1 COD_EMPRESA FROM EMPRESAS'"),
                conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("limit 1"),
                conversion.source());
        assertDoesNotThrow(() -> new GroovyShell().parse(conversion.source()), conversion.source());
    }

    @Test
    void keepsReviewSqlUnchangedInDualDatabaseMode() {
        String source = "def sql = \"SELECT FIRST 1 CODIGO FROM PRODUTOS \" + where\n";

        var conversion = processor.convert(Path.of("dinamico.groovy"), source, true);

        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(source, conversion.source());
    }

    @Test
    void sendsConcatenatedSqlToManualReviewWithoutChangingSource() {
        String source = """
                def sql = "SELECT FIRST 1 CODIGO FROM PRODUTOS " +
                          "WHERE ATIVO = -1"
                """;

        var conversion = processor.convert(Path.of("dinamico.groovy"), source);

        assertEquals(1, conversion.sqlResults().size());
        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(source, conversion.source());
    }

    @Test
    void ignoresSqlInsideCommentsAndReportsDynamicFragment() {
        String source = """
                // def antigo = "SELECT FIRST 1 CODIGO FROM PRODUTOS"
                sqlParcelas << "AND ((SELECT DIAS FROM PARCELAS WHERE NUMERO = ${numero}) = ${valor})"
                """;

        var conversion = processor.convert(Path.of("parcelas.groovy"), source);

        assertEquals(1, conversion.sqlResults().size());
        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(2, conversion.sqlResults().getFirst().line());
    }

    @Test
    void convertsDynamicFirstAndSkipToLimitAndOffset() {
        String source = """
                def sql = \"\"\"
                    SELECT FIRST ${quantidade} SKIP ${inicio}
                        CODIGO, NOME
                    FROM PRODUTOS
                \"\"\"
                """;

        var conversion = processor.convert(Path.of("paginacao.groovy"), source);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertTrue(conversion.source().contains("${quantidade}"), conversion.source());
        assertTrue(conversion.source().contains("${inicio}"), conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("offset ${inicio}"),
                conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("limit ${quantidade}"),
                conversion.source());
    }

    @Test
    void doesNotTreatHttpDeleteMethodAsSql() {
        String source = "def method = \"DELETE\"\nexchange(url, 'DELETE', headers)";

        var conversion = processor.convert(Path.of("http.groovy"), source);

        assertTrue(conversion.sqlResults().isEmpty());
        assertEquals(source, conversion.source());
    }

    @Test
    void sendsSqlBuiltWithLaterAppendToReview() {
        String source = "def sql = \"\"\"INSERT INTO PRODUTOS (CODIGO\"\"\"\n"
                + "sql += \", NOME) VALUES (1, 'A')\"\n";

        var conversion = processor.convert(Path.of("dynamic.groovy"), source);

        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(source, conversion.source());
    }

    @Test
    void convertsDynamicWhereWithDeterministicFirebirdPagination() {
        String source = "def sql = \"\"\"\n"
                + "    SELECT FIRST 10 CODIGO\n"
                + "    FROM PRODUTOS\n"
                + "    ${where}\n"
                + "    ORDER BY CODIGO\n"
                + "\"\"\"\n";

        var conversion = processor.convert(Path.of("where.groovy"), source);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertTrue(conversion.source().contains("${where}"), conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("limit 10"),
                conversion.source());
    }

    @Test
    void followsOptionalFilterDefinedBeforeCompatibleDynamicSql() {
        String source = "def empresa = params.empresa\n"
                + "        ? \"AND PNCNR.COD_EMPRESA IN (${listToStr(params.empresa)})\"\n"
                + "        : ''\n"
                + "def codigo = params.codigo ? params.codigo : \"cast(null as integer)\"\n\n"
                + "def dados = queryList(\"\"\"\n"
                + "    SELECT E.NOME, SUM(P.VALOR)\n"
                + "    FROM PEGAR_DADOS(${codigo}) P, EMPRESAS E\n"
                + "    WHERE P.COD_EMPRESA = E.COD_EMPRESA\n"
                + "      ${empresa}\n"
                + "    GROUP BY 1\n"
                + "\"\"\")\n";

        var conversion = processor.convert(Path.of("faturamento-empresa.groovy"), source);
        var query = conversion.sqlResults().getLast();

        assertEquals(ConversionStatus.CONVERTED, query.status(), query.message());
        assertEquals(2, query.dynamicExpressions());
        assertEquals(source, conversion.source());
    }

    @Test
    void keepsUnknownStandaloneDynamicClauseForReview() {
        String source = "def complemento = criarSqlComplexo(params)\n"
                + "def dados = queryList(\"\"\"\n"
                + "    SELECT CODIGO\n"
                + "    FROM PRODUTOS\n"
                + "    ${complemento}\n"
                + "\"\"\")\n";

        var conversion = processor.convert(Path.of("desconhecido.groovy"), source);

        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(1, conversion.sqlResults().getFirst().dynamicExpressions());
        assertEquals(source, conversion.source());
    }

    @Test
    void astFollowsBarePredicateDefinedByTernaryExpression() {
        String source = "def empresa = params.empresa\n"
                + "        ? \"PNCNR.COD_EMPRESA IN (${listToStr(params.empresa)})\"\n"
                + "        : '1=1'\n"
                + "def sql = \"\"\"\n"
                + "    SELECT PNCNR.COD_EMPRESA\n"
                + "    FROM PEDIDOS PNCNR\n"
                + "    WHERE\n"
                + "      ${empresa}\n"
                + "    GROUP BY 1\n"
                + "\"\"\"\n";

        var conversion = processor.convert(Path.of("predicado.groovy"), source);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getLast().status(),
                conversion.sqlResults().getLast().message());
        assertEquals(source, conversion.source());
    }

    @Test
    void treatsStandalonePropertyInterpolationInsideValuesAsScalar() {
        String source = "def sql = \"\"\"\n"
                + "    INSERT INTO ITENS (CODIGO, PRODUTO)\n"
                + "    VALUES (\n"
                + "      ${item.codigo},\n"
                + "      ${item.produto}\n"
                + "    )\n"
                + "\"\"\"\n";

        var conversion = processor.convert(Path.of("insert.groovy"), source);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertEquals(source, conversion.source());
    }

    @Test
    void classifiesConditionalFilterBuiltFromAndPrefixAndMethodCall() {
        String source = "def buscar(request) {\n"
                + "    def where = ''\n"
                + "    if (request.params.search) {\n"
                + "        where = ' and ' + montaWhereSuperBusca(request.params.search, "
                + "\"upper(P.NOME) like upper('%__word__%')\", \"__word__\")\n"
                + "    }\n"
                + "    def sql = \"\"\"\n"
                + "        SELECT P.CODIGO\n"
                + "        FROM PRODUTOS P\n"
                + "        WHERE P.ATIVO = -1\n"
                + "        ${where}\n"
                + "    \"\"\"\n"
                + "}\n";

        var conversion = processor.convert(Path.of("filtro-opcional.groovy"), source);

        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status(),
                conversion.sqlResults().getFirst().message());
        assertEquals(source, conversion.source());
    }

    @Test
    void movesConditionalFirebirdPaginationToTheEndOfTheQuery() {
        String source = "def buscar(page, size) {\n"
                + "    def where = ''\n"
                + "    if (page != null) {\n"
                + "        where = ' and ' + criarFiltro(page)\n"
                + "    }\n"
                + "    def sqlFirst = ''\n"
                + "    if (page != null && size != null) {\n"
                + "        sqlFirst = \"first ${size} skip ${page * size}\"\n"
                + "    }\n"
                + "    def sql = \"\"\"\n"
                + "        SELECT ${sqlFirst}\n"
                + "          P.CODIGO\n"
                + "        FROM PRODUTOS P\n"
                + "        WHERE P.ATIVO = -1\n"
                + "        ${where}\n"
                + "    \"\"\"\n"
                + "}\n";

        var conversion = processor.convert(Path.of("paginacao-condicional.groovy"), source);
        var result = conversion.sqlResults().getFirst();

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(conversion.source().contains(
                "sqlFirst = \"limit ${size} offset ${page * size}\""),
                conversion.source());
        assertTrue(conversion.source().matches(
                "(?s).*WHERE P\\.ATIVO = -1.*\\$\\{where}.*\\$\\{sqlFirst}\\s*\"\"\".*"),
                conversion.source());
        assertTrue(!conversion.source().matches("(?s).*SELECT\\s+\\$\\{sqlFirst}.*"),
                conversion.source());
    }

    @Test
    void preservesDynamicPaginationWhenTheVariableHasMoreThanOneUse() {
        String source = "def sqlFirst = ''\n"
                + "if (page != null) sqlFirst = \"first ${size}\"\n"
                + "def sql = \"\"\"SELECT ${sqlFirst} CODIGO FROM PRODUTOS\"\"\"\n"
                + "println sqlFirst\n"
                + "def log = \"Paginação: ${sqlFirst}\"\n";

        var conversion = processor.convert(Path.of("uso-multiplo.groovy"), source);

        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(source, conversion.source());
    }

    @Test
    void preservesPaginationWhoseAssignmentIsNotConditional() {
        String source = "def sqlFirst = ''\n"
                + "sqlFirst = \"first ${size}\"\n"
                + "def sql = \"\"\"SELECT ${sqlFirst} CODIGO FROM PRODUTOS\"\"\"\n";

        var conversion = processor.convert(Path.of("sem-condicao.groovy"), source);

        assertEquals(ConversionStatus.REVIEW, conversion.sqlResults().getFirst().status());
        assertEquals(source, conversion.source());
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(value, position)) >= 0) {
            count++;
            position += value.length();
        }
        return count;
    }
}
