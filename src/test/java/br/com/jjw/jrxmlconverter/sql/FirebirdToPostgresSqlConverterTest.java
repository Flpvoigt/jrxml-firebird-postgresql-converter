package br.com.jjw.jrxmlconverter.sql;

import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FirebirdToPostgresSqlConverterTest {
    private final FirebirdToPostgresSqlConverter converter = new FirebirdToPostgresSqlConverter();

    @Test
    void convertsFirstAndPreservesJasperParameter() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "select first 10 id from produto where empresa = $P{empresa}");

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().toLowerCase().contains("fetch next 10 rows only"),
                result.convertedSql());
        assertTrue(result.convertedSql().contains("$P{empresa}"));
        assertEquals(1, result.jasperTokens());
    }

    @Test
    void preservesMigratedTableFunction() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "select x.codigo from MINHA_FUNCAO_TABELA($P{grupo}) x");

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().matches(
                "(?is).*MINHA_FUNCAO_TABELA\\s*\\(\\s*\\$P\\{grupo}\\s*\\).*") ,
                result.convertedSql());
    }

    @Test
    void identifiesBlankQuery() {
        var result = converter.convert(Path.of("report.jrxml"), 1, "  \n");
        assertEquals(ConversionStatus.EMPTY, result.status());
        assertFalse(result.succeeded());
    }

    @Test
    void convertsUpdateOrInsertWithExplicitMatchingToPostgresUpsert() {
        var result = converter.convert(Path.of("script.groovy"), 1, """
                UPDATE OR INSERT INTO COMISSAO (CODIGO, TIPO, VALOR)
                VALUES (10, 'A', 5.5)
                MATCHING (CODIGO, TIPO);
                """);

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().contains("INSERT INTO COMISSAO"), result.convertedSql());
        assertTrue(result.convertedSql().contains("ON CONFLICT (CODIGO, TIPO)"), result.convertedSql());
        assertTrue(result.convertedSql().contains("VALOR = EXCLUDED.VALOR"), result.convertedSql());
        assertFalse(result.convertedSql().contains("CODIGO = EXCLUDED.CODIGO"), result.convertedSql());
    }

    @Test
    void refusesUpdateOrInsertWhenConflictKeyIsUnknown() {
        var result = converter.convert(Path.of("script.groovy"), 1,
                "UPDATE OR INSERT INTO PRODUTO (CODIGO, NOME) VALUES (1, 'Teste')");

        assertEquals(ConversionStatus.FAILED, result.status());
        assertTrue(result.message().contains("sem MATCHING"), result.message());
    }

    @Test
    void convertsLegacyFunctionsAndPreservesTerminalSemicolon() {
        var result = converter.convert(Path.of("script.groovy"), 1, """
                INSERT INTO MOVIMENTO (ID, TEXTO)
                VALUES (GEN_ID(SEQ_MOVIMENTO, 1), ASCII_CHAR(13));
                """);

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().contains("nextval('SEQ_MOVIMENTO')"), result.convertedSql());
        assertTrue(result.convertedSql().toLowerCase().contains("chr(13)"), result.convertedSql());
        assertTrue(result.convertedSql().endsWith(";"), result.convertedSql());
    }

    @Test
    void convertsFirebirdListWithDistinct() {
        var result = converter.convert(Path.of("script.groovy"), 1,
                "SELECT LIST(DISTINCT N.NUMERO) NOTAS FROM NOTAS N");

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().toLowerCase().contains("string_agg(distinct"),
                result.convertedSql());
    }
}
