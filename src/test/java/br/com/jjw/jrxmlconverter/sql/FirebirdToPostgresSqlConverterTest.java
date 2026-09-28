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

    @Test
    void convertsFirstInsideNestedSelect() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "select (select first 1 -1 from itens i where i.id = p.id) ativo from produtos p");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().toLowerCase().contains("fetch next 1 rows only"),
                result.convertedSql());
        assertFalse(result.convertedSql().contains("?"), result.convertedSql());
    }

    @Test
    void convertsFirebirdDateAddSyntax() {
        var result = converter.convert(Path.of("script.groovy"), 1,
                "UPDATE PARAMETROS SET ULTIMA_SINC = DATEADD(-1 HOUR TO CURRENT_TIMESTAMP)");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().toLowerCase().contains("interval"), result.convertedSql());
        assertFalse(result.convertedSql().toLowerCase().contains("dateadd"), result.convertedSql());
    }

    @Test
    void convertsWithLockAndRemovesFirebirdDummyTable() {
        var lock = converter.convert(Path.of("script.groovy"), 1,
                "select codigo from pedidos where codigo = 10 with lock");
        var dummy = converter.convert(Path.of("report.jrxml"), 1,
                "select 1 valor from RDB$DATABASE");

        assertEquals(ConversionStatus.CONVERTED, lock.status(), lock.message());
        assertTrue(lock.convertedSql().toLowerCase().contains("for update"), lock.convertedSql());
        assertEquals(ConversionStatus.CONVERTED, dummy.status(), dummy.message());
        assertFalse(dummy.convertedSql().toUpperCase().contains("RDB$DATABASE"), dummy.convertedSql());
    }

    @Test
    void convertsDateDiffDayAndStartingWith() {
        var datediff = converter.convert(Path.of("script.groovy"), 1,
                "select datediff(day from inicio to current_timestamp) dias from parametros");
        var starting = converter.convert(Path.of("report.jrxml"), 1,
                "select first 1 codigo from itens where descricao starting with cast(10 as varchar(20))");

        assertEquals(ConversionStatus.CONVERTED, datediff.status(), datediff.message());
        assertFalse(datediff.convertedSql().toLowerCase().contains("datediff"), datediff.convertedSql());
        assertEquals(ConversionStatus.CONVERTED, starting.status(), starting.message());
        assertTrue(starting.convertedSql().toLowerCase().contains("like"), starting.convertedSql());
    }

    @Test
    void preservesTableFunctionAfterCommaInFromClause() {
        var result = converter.convert(Path.of("report.jrxml"), 1, """
                select p.codigo, f.descricao
                from produtos p,
                     PEGAR_GRUPOS_PAIS(p.grupo, 99) f
                where f.codigo = p.grupo
                """);

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().contains("PEGAR_GRUPOS_PAIS"), result.convertedSql());
    }

    @Test
    void convertsKnownFirebirdSyntaxInDynamicSqlWithoutReformattingUnknownClause() {
        var result = converter.convertLenientDynamic(Path.of("script.groovy"), 1,
                "select first 10 codigo from produtos __groovy_token_1__ order by codigo");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().contains("__groovy_token_1__"), result.convertedSql());
        assertTrue(result.convertedSql().toLowerCase().contains("fetch next 10 rows only"),
                result.convertedSql());
    }

    @Test
    void convertsFirebirdListWithCustomDelimiter() {
        var result = converter.convertLenientDynamic(Path.of("script.groovy"), 1,
                "select list(codigo, ' ') from produtos __groovy_token_1__");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().toLowerCase().contains("string_agg"), result.convertedSql());
    }
}
