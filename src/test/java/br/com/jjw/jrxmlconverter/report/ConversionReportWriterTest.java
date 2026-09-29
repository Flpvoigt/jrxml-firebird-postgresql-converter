package br.com.jjw.jrxmlconverter.report;

import br.com.jjw.jrxmlconverter.domain.ConversionRun;
import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import br.com.jjw.jrxmlconverter.domain.ConversionSummary;
import br.com.jjw.jrxmlconverter.domain.GroovySqlResult;
import br.com.jjw.jrxmlconverter.domain.QueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionReportWriterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesOnlyReviewAndFailedEntriesToCsvAndCreatesReadableReviewFile() throws Exception {
        Path jrxml = Path.of("reports/relatorio.jrxml");
        Path groovy = Path.of("charts/grafico.groovy");
        var run = new ConversionRun(emptySummary(), List.of(
                QueryResult.converted(jrxml, 1, "SELECT FIRST 1 ID FROM T", "SELECT ID FROM T", 0),
                QueryResult.empty(jrxml, 2),
                QueryResult.failed(jrxml, 3, "SELECT P.NOME P.CODIGO FROM PRODUTOS P",
                        "ParserException: Unexpected clause: [2:15] SELECT P.NOME P[*].CODIGO FROM PRODUTOS P")), List.of(
                GroovySqlResult.converted(groovy, 1, 10, "SELECT FIRST 1 ID FROM T", "SELECT ID FROM T", 0),
                new GroovySqlResult(groovy, 2, 20, ConversionStatus.REVIEW,
                        "AND COD_EMPRESA = ${empresa}", "AND COD_EMPRESA = ${empresa}", 1,
                        "Fragmento de SQL dinâmico; conversão automática por partes seria insegura."),
                GroovySqlResult.failed(groovy, 3, 30, "UPDATE OR INSERT INTO T VALUES (1)", 0,
                        "UPDATE OR INSERT sem MATCHING explícito")), false);

        new ConversionReportWriter().write(temporaryDirectory, run);

        String jrxmlCsv = Files.readString(temporaryDirectory.resolve("jrxml/conversion-report.csv"));
        assertTrue(jrxmlCsv.contains("FAILED"), jrxmlCsv);
        assertFalse(jrxmlCsv.contains("CONVERTED"), jrxmlCsv);
        assertFalse(jrxmlCsv.contains("EMPTY"), jrxmlCsv);

        String jrxmlReview = Files.readString(temporaryDirectory.resolve("jrxml/review-required.txt"));
        assertTrue(jrxmlReview.contains("PENDÊNCIAS DE CONVERSÃO — JRXML"), jrxmlReview);
        assertTrue(jrxmlReview.contains("Local: QueryString 3"), jrxmlReview);
        assertTrue(jrxmlReview.contains("Problema: PROVÁVEL VÍRGULA AUSENTE NO SELECT"), jrxmlReview);
        assertTrue(jrxmlReview.contains("Ação:"), jrxmlReview);
        assertFalse(jrxmlReview.contains("Impacto:"), jrxmlReview);
        assertTrue(jrxmlReview.contains("linha 2, coluna 15"), jrxmlReview);
        assertFalse(jrxmlReview.contains("[*]"), jrxmlReview);

        String groovyCsv = Files.readString(temporaryDirectory.resolve("groovy/conversion-report.csv"));
        assertTrue(groovyCsv.contains("REVIEW"), groovyCsv);
        assertTrue(groovyCsv.contains("FAILED"), groovyCsv);
        assertFalse(groovyCsv.contains("CONVERTED"), groovyCsv);

        String review = Files.readString(temporaryDirectory.resolve("groovy/review-required.txt"));
        assertTrue(review.contains("Arquivo: charts\\grafico.groovy")
                || review.contains("Arquivo: charts/grafico.groovy"), review);
        assertTrue(review.contains("PENDÊNCIA 1/2"), review);
        assertTrue(review.contains("Problema: FRAGMENTO SQL DINÂMICO"), review);
        assertTrue(review.contains("Ação:"), review);
        assertTrue(review.contains("SQL original: AND COD_EMPRESA"), review);
        assertFalse(review.contains("Motivo:"), review);
        assertFalse(review.contains("Impacto:"), review);
    }

    @Test
    void writesAnExplicitMessageWhenThereAreNoGroovyIssues() throws Exception {
        Path groovy = Path.of("charts/ok.groovy");
        var run = new ConversionRun(emptySummary(), List.of(), List.of(
                GroovySqlResult.converted(groovy, 1, 1, "SELECT 1", "SELECT 1", 0)), false);

        new ConversionReportWriter().write(temporaryDirectory, run);

        String csv = Files.readString(temporaryDirectory.resolve("groovy/conversion-report.csv"));
        assertFalse(csv.contains("CONVERTED"), csv);
        String review = Files.readString(temporaryDirectory.resolve("groovy/review-required.txt"));
        assertTrue(review.contains("Nenhuma pendência encontrada."), review);
        String jrxmlReview = Files.readString(temporaryDirectory.resolve("jrxml/review-required.txt"));
        assertTrue(jrxmlReview.contains("Nenhuma pendência encontrada."), jrxmlReview);
    }

    private static ConversionSummary emptySummary() {
        return new ConversionSummary(0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0);
    }
}
