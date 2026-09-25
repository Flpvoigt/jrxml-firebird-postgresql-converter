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
}
