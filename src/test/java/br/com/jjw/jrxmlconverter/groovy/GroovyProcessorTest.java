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
        assertEquals(ConversionStatus.CONVERTED, conversion.sqlResults().getFirst().status());
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
}
