package br.com.jjw.jrxmlconverter.report;

import br.com.jjw.jrxmlconverter.domain.ConversionRun;
import br.com.jjw.jrxmlconverter.domain.ConversionSummary;
import br.com.jjw.jrxmlconverter.domain.GroovySqlResult;
import br.com.jjw.jrxmlconverter.domain.QueryResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ConversionReportWriter {
    public void write(Path output, ConversionRun run) throws IOException {
        if (run.groupedByProject()) {
            writeGroupedReports(output, run);
            writeSummary(output.resolve("conversion-summary.txt"), run.summary());
            return;
        }
        Path jrxmlOutput = output.resolve("jrxml");
        Path groovyOutput = output.resolve("groovy");
        Files.createDirectories(jrxmlOutput);
        Files.createDirectories(groovyOutput);
        writeJrxmlCsv(jrxmlOutput.resolve("conversion-report.csv"), run.queryResults());
        writeGroovyCsv(groovyOutput.resolve("conversion-report.csv"), run.groovyResults());
        writeSummary(output.resolve("conversion-summary.txt"), run.summary());
    }

    private static void writeGroupedReports(Path output, ConversionRun run) throws IOException {
        Map<String, List<QueryResult>> jrxmlByProject = new LinkedHashMap<>();
        Map<String, List<GroovySqlResult>> groovyByProject = new LinkedHashMap<>();
        Set<String> projects = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (QueryResult result : run.queryResults()) {
            String project = projectName(result.file());
            projects.add(project);
            jrxmlByProject.computeIfAbsent(project, ignored -> new ArrayList<>()).add(result);
        }
        for (GroovySqlResult result : run.groovyResults()) {
            String project = projectName(result.file());
            projects.add(project);
            groovyByProject.computeIfAbsent(project, ignored -> new ArrayList<>()).add(result);
        }
        for (String project : projects) {
            Path jrxmlOutput = output.resolve(project).resolve("jrxml");
            Path groovyOutput = output.resolve(project).resolve("groovy");
            Files.createDirectories(jrxmlOutput);
            Files.createDirectories(groovyOutput);
            writeJrxmlCsv(jrxmlOutput.resolve("conversion-report.csv"),
                    jrxmlByProject.getOrDefault(project, List.of()));
            writeGroovyCsv(groovyOutput.resolve("conversion-report.csv"),
                    groovyByProject.getOrDefault(project, List.of()));
        }
    }

    private static String projectName(Path file) {
        return file.getNameCount() == 0 ? "_raiz" : file.getName(0).toString();
    }

    private static void writeJrxmlCsv(Path destination, List<QueryResult> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("file;query_index;status;jasper_tokens;message");
        for (QueryResult result : results) {
            lines.add(csv(result.file().toString()) + ";" + result.queryIndex() + ";"
                    + result.status() + ";" + result.jasperTokens() + ";" + csv(result.message()));
        }
        Files.write(destination, lines, StandardCharsets.UTF_8);
    }

    private static void writeGroovyCsv(Path destination, List<GroovySqlResult> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("file;sql_index;line;status;dynamic_expressions;message");
        for (GroovySqlResult result : results) {
            lines.add(csv(result.file().toString()) + ";" + result.sqlIndex() + ";" + result.line()
                    + ";" + result.status() + ";" + result.dynamicExpressions() + ";"
                    + csv(result.message()));
        }
        Files.write(destination, lines, StandardCharsets.UTF_8);
    }

    private static void writeSummary(Path destination, ConversionSummary summary) throws IOException {
        String text = """
                Conversor JRXML/Groovy - resumo
                Gerado em: %s
                Arquivos JRXML: %d
                QueryStrings JRXML registradas: %d
                QueryStrings JRXML convertidas: %d
                QueryStrings JRXML vazias: %d
                QueryStrings JRXML não SQL ignoradas: %d
                Falhas em JRXML: %d
                Referências de subreport: %d
                Subreports resolvidos localmente: %d
                Arquivos Groovy: %d
                SQLs Groovy identificados: %d
                SQLs Groovy convertidos: %d
                SQLs Groovy para revisão: %d
                Falhas em Groovy: %d
                """.formatted(Instant.now(), summary.jrxmlFiles(), summary.queryEntries(),
                summary.convertedQueries(), summary.emptyQueries(), summary.ignoredQueries(), summary.failedQueries(),
                summary.subreportReferences(), summary.resolvedSubreports(), summary.groovyFiles(),
                summary.groovySqlEntries(), summary.convertedGroovySql(), summary.reviewGroovySql(),
                summary.failedGroovySql());
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
