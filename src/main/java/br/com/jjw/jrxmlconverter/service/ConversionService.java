package br.com.jjw.jrxmlconverter.service;

import br.com.jjw.jrxmlconverter.cli.CommandLineOptions;
import br.com.jjw.jrxmlconverter.domain.*;
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

    public ConversionService(SecureXmlParser xmlParser, SubreportInspector subreportInspector,
                             JrxmlProcessor jrxmlProcessor) {
        this.xmlParser = xmlParser;
        this.subreportInspector = subreportInspector;
        this.jrxmlProcessor = jrxmlProcessor;
    }

    public ConversionRun execute(CommandLineOptions options) throws Exception {
        Path input = options.input().toAbsolutePath().normalize();
        Path output = options.output() == null ? null : options.output().toAbsolutePath().normalize();
        validatePaths(input, output, options.dryRun());

        List<Path> files = findJrxmlFiles(input);
        List<QueryResult> queryResults = new ArrayList<>();
        int subreportReferences = 0;
        int resolvedSubreports = 0;

        for (Path source : files) {
            Path relative = input.relativize(source);
            String xml = Files.readString(source, StandardCharsets.UTF_8);
            Document document = xmlParser.parse(xml, source);
            SubreportStats stats = subreportInspector.inspect(document, source);
            subreportReferences += stats.total();
            resolvedSubreports += stats.resolved();

            JrxmlConversion conversion = jrxmlProcessor.convert(relative, xml, document);
            queryResults.addAll(conversion.queryResults());
            if (!options.dryRun()) {
                writeOutput(output, relative, conversion.xml(), options.overwrite());
            }
        }

        ConversionSummary summary = summarize(
                files.size(), queryResults, subreportReferences, resolvedSubreports);
        return new ConversionRun(summary, queryResults);
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

    private static List<Path> findJrxmlFiles(Path input) throws Exception {
        try (Stream<Path> paths = Files.walk(input)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jrxml"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (files.isEmpty()) {
                throw new IllegalArgumentException("Nenhum arquivo .jrxml encontrado em " + input);
            }
            return files;
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

    private static ConversionSummary summarize(int files, List<QueryResult> results,
                                                int references, int resolved) {
        long converted = results.stream().filter(r -> r.status() == ConversionStatus.CONVERTED).count();
        long empty = results.stream().filter(r -> r.status() == ConversionStatus.EMPTY).count();
        long failed = results.stream().filter(r -> r.status() == ConversionStatus.FAILED).count();
        return new ConversionSummary(files, results.size(), converted, empty, failed, references, resolved);
    }
}
