package br.com.jjw.jrxmlconverter.service;

import br.com.jjw.jrxmlconverter.cli.CommandLineOptions;
import br.com.jjw.jrxmlconverter.domain.*;
import br.com.jjw.jrxmlconverter.groovy.GroovyProcessor;
import br.com.jjw.jrxmlconverter.jrxml.JrxmlProcessor;
import br.com.jjw.jrxmlconverter.xml.SecureXmlParser;
import br.com.jjw.jrxmlconverter.xml.SubreportInspector;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ConversionService {
    private final SecureXmlParser xmlParser;
    private final SubreportInspector subreportInspector;
    private final JrxmlProcessor jrxmlProcessor;
    private final GroovyProcessor groovyProcessor;

    public ConversionService(SecureXmlParser xmlParser, SubreportInspector subreportInspector,
                             JrxmlProcessor jrxmlProcessor, GroovyProcessor groovyProcessor) {
        this.xmlParser = xmlParser;
        this.subreportInspector = subreportInspector;
        this.jrxmlProcessor = jrxmlProcessor;
        this.groovyProcessor = groovyProcessor;
    }

    public ConversionRun execute(CommandLineOptions options) throws Exception {
        Path input = options.input().toAbsolutePath().normalize();
        Path output = options.output() == null ? null : options.output().toAbsolutePath().normalize();
        validatePaths(input, output, options.dryRun());

        List<Path> jrxmlFiles = findFiles(input, ".jrxml");
        List<Path> groovyFiles = findFiles(input, ".groovy");
        if (jrxmlFiles.isEmpty() && groovyFiles.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo .jrxml ou .groovy encontrado em " + input);
        }
        List<QueryResult> queryResults = new ArrayList<>();
        List<GroovySqlResult> groovyResults = new ArrayList<>();
        int subreportReferences = 0;
        int resolvedSubreports = 0;

        for (Path source : jrxmlFiles) {
            Path relative = input.relativize(source);
            String xml = Files.readString(source, StandardCharsets.UTF_8);
            Document document = xmlParser.parse(xml, source);
            SubreportStats stats = subreportInspector.inspect(document, source);
            subreportReferences += stats.total();
            resolvedSubreports += stats.resolved();

            JrxmlConversion conversion = jrxmlProcessor.convert(relative, xml, document);
            queryResults.addAll(conversion.queryResults());
            if (!options.dryRun()) {
                writeOutput(output.resolve("jrxml"), relative, conversion.xml(), options.overwrite());
            }
        }

        for (Path source : groovyFiles) {
            Path relative = input.relativize(source);
            String groovy = Files.readString(source, StandardCharsets.UTF_8);
            GroovyConversion conversion = groovyProcessor.convert(relative, groovy);
            groovyResults.addAll(conversion.sqlResults());
            if (!options.dryRun()) {
                writeOutput(output.resolve("groovy"), relative, conversion.source(), options.overwrite());
            }
        }

        ConversionSummary summary = summarize(
                jrxmlFiles.size(), queryResults, subreportReferences, resolvedSubreports,
                groovyFiles.size(), groovyResults);
        return new ConversionRun(summary, queryResults, groovyResults);
    }

    private static void validatePaths(Path input, Path output, boolean dryRun) throws Exception {
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Diretório de entrada inexistente: " + input);
        }
        if (!dryRun) {
            if (output == null) {
                throw new IllegalArgumentException("Informe --output ou utilize --dry-run.");
            }
            if (input.equals(output) || output.startsWith(input)) {
                throw new IllegalArgumentException("A saída não pode ser igual ou ficar dentro da entrada.");
            }
            Files.createDirectories(output);
        }
    }

    private static List<Path> findFiles(Path input, String extension) throws Exception {
        try (Stream<Path> paths = Files.walk(input)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(extension))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static void writeOutput(Path output, Path relative, String xml, boolean overwrite) throws Exception {
        Path destination = output.resolve(relative);
        if (Files.exists(destination) && !overwrite) {
            throw new IllegalArgumentException("Arquivo de saída já existe; use --overwrite: " + destination);
        }
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, xml, StandardCharsets.UTF_8);
    }

    private static ConversionSummary summarize(int jrxmlFiles, List<QueryResult> results,
                                                int references, int resolved,
                                                int groovyFiles, List<GroovySqlResult> groovyResults) {
        long converted = results.stream().filter(r -> r.status() == ConversionStatus.CONVERTED).count();
        long empty = results.stream().filter(r -> r.status() == ConversionStatus.EMPTY).count();
        long failed = results.stream().filter(r -> r.status() == ConversionStatus.FAILED).count();
        long convertedGroovy = groovyResults.stream()
                .filter(r -> r.status() == ConversionStatus.CONVERTED).count();
        long reviewGroovy = groovyResults.stream()
                .filter(r -> r.status() == ConversionStatus.REVIEW).count();
        long failedGroovy = groovyResults.stream()
                .filter(r -> r.status() == ConversionStatus.FAILED).count();
        return new ConversionSummary(jrxmlFiles, results.size(), converted, empty, failed,
                references, resolved, groovyFiles, groovyResults.size(), convertedGroovy,
                reviewGroovy, failedGroovy);
    }
}
