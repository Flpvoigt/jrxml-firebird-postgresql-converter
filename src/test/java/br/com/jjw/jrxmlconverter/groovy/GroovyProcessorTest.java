package br.com.jjw.jrxmlconverter.groovy;

import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyProcessorTest {
    private final GroovyProcessor processor =
            new GroovyProcessor(new FirebirdToPostgresSqlConverter());

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
        assertTrue(conversion.source().toLowerCase().contains("fetch next 1 rows only"),
                conversion.source());
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
        assertTrue(conversion.source().toLowerCase().contains("offset ${inicio} rows"),
                conversion.source());
        assertTrue(conversion.source().toLowerCase().contains("fetch next ${quantidade} rows only"),
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
        assertTrue(conversion.source().toLowerCase().contains("fetch next 10 rows only"),
                conversion.source());
    }
}
