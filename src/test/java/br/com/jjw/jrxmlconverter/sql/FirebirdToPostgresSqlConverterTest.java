package br.com.jjw.jrxmlconverter.sql;

import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import br.com.jjw.jrxmlconverter.metadata.SchemaMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FirebirdToPostgresSqlConverterTest {
    @Test
    void convertsUpdateOrInsertWithoutMatchingUsingPrimaryKeyMetadata() {
        var metadata = new SchemaMetadata(Map.of(
                "PRODUTOS", new SchemaMetadata.TableMetadata(List.of("COD_EMPRESA", "COD_PRODUTO"))));
        var converterWithMetadata = new FirebirdToPostgresSqlConverter(metadata);

        var result = converterWithMetadata.convert(Path.of("rotina.groovy"), 1, """
                UPDATE OR INSERT INTO PRODUTOS (COD_EMPRESA, COD_PRODUTO, DESCRICAO)
                VALUES (1, 10, 'Produto')
                """);

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().contains("ON CONFLICT (COD_EMPRESA, COD_PRODUTO)"));
        assertTrue(result.convertedSql().contains("DESCRICAO = EXCLUDED.DESCRICAO"));
        assertTrue(!result.convertedSql().contains("COD_EMPRESA = EXCLUDED.COD_EMPRESA"));
    }

    private final FirebirdToPostgresSqlConverter converter = new FirebirdToPostgresSqlConverter();

    @Test
    void convertsFirstAndPreservesJasperParameter() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "select first 10 id from produto where empresa = $P{empresa}");

        assertEquals(ConversionStatus.CONVERTED, result.status());
        assertTrue(result.convertedSql().toLowerCase().contains("limit 10"),
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
        assertTrue(result.convertedSql().toLowerCase().contains("limit 1"),
                result.convertedSql());
        assertFalse(result.convertedSql().contains("?"), result.convertedSql());
    }

    @Test
    void preservesExactFirstAndSkipAmountsInsideNestedSelect() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "select (select first 10 skip 5 i.id from itens i order by i.id) id from produtos p");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().toLowerCase().contains("offset 5"), result.convertedSql());
        assertTrue(result.convertedSql().toLowerCase().contains("limit 10"),
                result.convertedSql());
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
        assertTrue(starting.convertedSql().toLowerCase().contains("position"), starting.convertedSql());
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
        assertTrue(result.convertedSql().toLowerCase().contains("limit 10"),
                result.convertedSql());
    }

    @Test
    void convertsFirebirdListWithCustomDelimiter() {
        var result = converter.convertLenientDynamic(Path.of("script.groovy"), 1,
                "select list(codigo, ' ') from produtos __groovy_token_1__");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().toLowerCase().contains("string_agg"), result.convertedSql());
    }

    @Test
    void refusesConvertedSqlWhenKnownFirebirdSyntaxRemains() {
        var generator = converter.convert(Path.of("script.groovy"), 1,
                "SELECT GEN_ID(MINHA_SEQ, 2) FROM RDB$DATABASE");
        var nestedList = converter.convert(Path.of("report.jrxml"), 1, """
                SELECT (SELECT LIST(COALESCE(X.NOME, X.APELIDO))
                        FROM CLIENTES X) NOMES
                FROM PEDIDOS P
                """);

        assertEquals(ConversionStatus.FAILED, generator.status());
        assertTrue(generator.message().contains("sintaxe específica"), generator.message());
        assertEquals(ConversionStatus.FAILED, nestedList.status());
        assertTrue(nestedList.message().contains("sintaxe específica"), nestedList.message());
    }

    @Test
    void doesNotRewriteFirebirdWordsInsideTextLiterals() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "SELECT 'ASCII_CHAR(65) LIST(COL) FROM RDB$DATABASE' TEXTO FROM RDB$DATABASE");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().contains("'ASCII_CHAR(65) LIST(COL) FROM RDB$DATABASE'"),
                result.convertedSql());
    }

    @Test
    void refusesFirebirdSqlHiddenInsideJasperXExpression() {
        var result = converter.convert(Path.of("report.jrxml"), 1, """
                SELECT P.ID
                FROM PEDIDOS P
                WHERE $X{[BETWEEN],(SELECT FIRST 1 I.DATA FROM ITENS I), inicio, fim}
                """);

        assertEquals(ConversionStatus.FAILED, result.status());
        assertTrue(result.message().contains("sintaxe específica"), result.message());
    }

    @Test
    void placesPaginationBeforePostgresLockClause() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "SELECT FIRST 10 ID FROM PEDIDOS WITH LOCK");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        String converted = result.convertedSql().toLowerCase();
        assertTrue(converted.indexOf("limit 10") < converted.indexOf("for update"),
                result.convertedSql());
    }

    @Test
    void convertsPaginationAppliedToOnlyOneSetOperationBranch() {
        var result = converter.convert(Path.of("report.jrxml"), 1,
                "SELECT FIRST 10 ID FROM PEDIDOS UNION ALL SELECT ID FROM HISTORICO");

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().toLowerCase().contains("limit 10"),
                result.convertedSql());
        assertTrue(result.convertedSql().contains("UNION ALL"), result.convertedSql());
    }

    @Test
    void convertsPaginationWhenEverySetOperationBranchHasAnExplicitScope() {
        var result = converter.convert(Path.of("report.jrxml"), 1, """
                SELECT FIRST 1 ID FROM PEDIDOS
                UNION ALL
                SELECT SKIP 2 FIRST 3 ID FROM HISTORICO
                """);

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        String converted = result.convertedSql().toLowerCase();
        assertTrue(converted.contains("union all"), result.convertedSql());
        assertTrue(converted.contains("limit 1"), result.convertedSql());
        assertTrue(converted.contains("offset 2"), result.convertedSql());
        assertTrue(converted.contains("limit 3"), result.convertedSql());
        assertFalse(converted.contains(" first "), result.convertedSql());
        assertFalse(converted.contains(" skip "), result.convertedSql());
    }

    @Test
    void keepsSetPaginationForReviewWhenThereIsAGlobalOrderBy() {
        var result = converter.convert(Path.of("report.jrxml"), 1, """
                SELECT FIRST 1 ID FROM PEDIDOS
                UNION ALL
                SELECT FIRST 1 ID FROM HISTORICO
                ORDER BY ID
                """);

        assertEquals(ConversionStatus.FAILED, result.status());
        assertTrue(result.message().contains("revisão de escopo"), result.message());
    }

    @Test
    void convertsUniformSetOperatorsAndJasperPaginationValues() {
        for (String operator : List.of("UNION", "INTERSECT", "EXCEPT")) {
            var result = converter.convert(Path.of("report.jrxml"), 1, """
                    SELECT FIRST $P{limite} ID FROM PEDIDOS
                    %s
                    SELECT FIRST 2 ID FROM HISTORICO
                    """.formatted(operator));

            assertEquals(ConversionStatus.CONVERTED, result.status(),
                    operator + ": " + result.message());
            assertTrue(result.convertedSql().contains(operator), result.convertedSql());
            assertTrue(result.convertedSql().contains("$P{limite}"), result.convertedSql());
            assertFalse(result.convertedSql().toLowerCase().contains(" first "), result.convertedSql());
        }
    }

    @Test
    void ignoresSetOperationWordsInsideTextAndComments() {
        var result = converter.convert(Path.of("report.jrxml"), 1, """
                SELECT FIRST 1 'UNION ALL' TEXTO
                FROM PEDIDOS
                /* UNION SELECT FIRST 1 ID FROM OUTRA */
                """);

        assertEquals(ConversionStatus.CONVERTED, result.status(), result.message());
        assertTrue(result.convertedSql().contains("'UNION ALL'"), result.convertedSql());
    }

    @Test
    void convertsContainingAndStartingWithWithoutLikeWildcards() {
        var containing = converter.convert(Path.of("report.jrxml"), 1,
                "SELECT ID FROM PRODUTOS WHERE NOME CONTAINING 'A_%'");
        var starting = converter.convert(Path.of("report.jrxml"), 1,
                "SELECT ID FROM PRODUTOS WHERE NOME STARTING WITH $P{prefixo}");

        assertEquals(ConversionStatus.CONVERTED, containing.status(), containing.message());
        assertTrue(containing.convertedSql().toLowerCase().contains("position"), containing.convertedSql());
        assertEquals(ConversionStatus.CONVERTED, starting.status(), starting.message());
        assertTrue(starting.convertedSql().contains("$P{prefixo}"), starting.convertedSql());
        assertFalse(starting.convertedSql().toLowerCase().contains(" like "), starting.convertedSql());
    }
}
