package br.com.jjw.jrxmlconverter.report;

import br.com.jjw.jrxmlconverter.domain.ConversionRun;
import br.com.jjw.jrxmlconverter.domain.ConversionSummary;
import br.com.jjw.jrxmlconverter.domain.QueryResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ConversionReportWriter {
    public void write(Path output, ConversionRun run) throws IOException {
        writeCsv(output.resolve("conversion-report.csv"), run.queryResults());
        writeSummary(output.resolve("conversion-summary.txt"), run.summary());
    }

    private static void writeCsv(Path destination, List<QueryResult> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("file;query_index;status;jasper_tokens;message");
        for (QueryResult result : results) {
            lines.add(csv(result.file().toString()) + ";" + result.queryIndex() + ";"
                    + result.status() + ";" + result.jasperTokens() + ";" + csv(result.message()));
        }
        Files.write(destination, lines, StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path destination, ConversionSummary summary) throws IOException {
        String text = """
                JRXML Converter - resumo
                Gerado em: %s
                Arquivos JRXML: %d
                QueryStrings registradas: %d
                Convertidas: %d
                Vazias: %d
                Falhas: %d
                Referências de subreport: %d
                Subreports resolvidos localmente: %d
                """.formatted(Instant.now(), summary.files(), summary.queryEntries(),
                summary.convertedQueries(), summary.emptyQueries(), summary.failedQueries(),
                summary.subreportReferences(), summary.resolvedSubreports());
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
