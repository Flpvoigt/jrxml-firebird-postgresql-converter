package br.com.jjw.jrxmlconverter.service;

import br.com.jjw.jrxmlconverter.cli.CommandLineOptions;
import br.com.jjw.jrxmlconverter.domain.*;
import br.com.jjw.jrxmlconverter.groovy.GroovyProcessor;
import br.com.jjw.jrxmlconverter.jrxml.JrxmlProcessor;
import br.com.jjw.jrxmlconverter.xml.SecureXmlParser;
import br.com.jjw.jrxmlconverter.xml.SubreportInspector;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

public final class ConversionService {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
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
        Path discoveryRoot = Files.isDirectory(input) ? input : input.getParent();

        List<Path> jrxmlFiles = findFiles(input, ".jrxml");
        if (Files.isRegularFile(input) && input.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".jrxml")) {
            jrxmlFiles = findDirectJrxmlGraph(input, discoveryRoot);
        }
        List<Path> groovyFiles = findFiles(input, ".groovy");
        if (jrxmlFiles.isEmpty() && groovyFiles.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo .jrxml ou .groovy encontrado em " + input);
        }
        List<Path> allFiles = new ArrayList<>(jrxmlFiles);
        allFiles.addAll(groovyFiles);
        boolean groupedByProject = Files.isDirectory(input)
                && shouldGroupByProject(discoveryRoot, allFiles);
        if (!options.dryRun() && options.overwrite()) {
            clearDirectory(output);
        }
        if (!options.dryRun() && Files.isDirectory(input)) {
            copySupportingFiles(input, output, options.overwrite());
        }
        List<QueryResult> queryResults = new ArrayList<>();
        List<GroovySqlResult> groovyResults = new ArrayList<>();
        List<SubreportReferenceResult> subreportResults = new ArrayList<>();
        SubreportInspector.Catalog subreportCatalog = subreportInspector.catalog(jrxmlFiles);
        int subreportReferences = 0;
        int locatedSubreports = 0;
        int resolvedSubreports = 0;
        int ambiguousSubreports = 0;
        int dynamicSubreports = 0;
        int missingSubreports = 0;

        for (Path source : jrxmlFiles) {
            Path relative = discoveryRoot.relativize(source);
            String xml;
            try {
                xml = Files.readString(source, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                queryResults.add(QueryResult.failed(relative, 0, "", failureMessage(exception)));
                if (!options.dryRun()) {
                    copyOriginal(output, relative, source, options.overwrite());
                }
                continue;
            }

            JrxmlConversion conversion;
            try {
                Document document = xmlParser.parse(xml, source);
                SubreportInspection inspection = subreportInspector.inspect(
                        document, source, discoveryRoot, subreportCatalog);
                SubreportStats stats = inspection.stats();
                subreportResults.addAll(inspection.references());
                subreportReferences += stats.total();
                locatedSubreports += stats.located();
                resolvedSubreports += stats.resolved();
                ambiguousSubreports += stats.ambiguous();
                dynamicSubreports += stats.dynamic();
                missingSubreports += stats.missing();
                conversion = jrxmlProcessor.convert(relative, xml, document);
                queryResults.addAll(conversion.queryResults());
            } catch (Exception exception) {
                queryResults.add(QueryResult.failed(relative, 0, xml, failureMessage(exception)));
                if (!options.dryRun()) {
                    writeOutput(output, relative, xml, options.overwrite());
                }
                continue;
            }
            if (!options.dryRun()) {
                writeOutput(output, relative, conversion.xml(), options.overwrite());
            }
        }

        for (Path source : groovyFiles) {
            Path relative = discoveryRoot.relativize(source);
            DecodedSource groovy;
            GroovyConversion conversion;
            try {
                groovy = readGroovySource(source);
                conversion = groovyProcessor.convert(
                        relative, groovy.text(), options.dualDatabaseGroovy());
                groovyResults.addAll(conversion.sqlResults());
            } catch (Exception exception) {
                groovyResults.add(GroovySqlResult.failed(relative, 0, 0, "", 0,
                        failureMessage(exception)));
                if (!options.dryRun()) {
                    copyOriginal(output, relative, source, options.overwrite());
                }
                continue;
            }
            if (!options.dryRun()) {
                writeOutput(output, relative, conversion.source(),
                        options.overwrite(), groovy.charset(), groovy.utf8Bom());
            }
        }

        ConversionSummary summary = summarize(
                jrxmlFiles.size(), queryResults, subreportReferences, locatedSubreports,
                resolvedSubreports, ambiguousSubreports, dynamicSubreports, missingSubreports,
                groovyFiles.size(), groovyResults);
        return new ConversionRun(summary, queryResults, groovyResults, subreportResults,
                groupedByProject);
    }

    private static void validatePaths(Path input, Path output, boolean dryRun) throws Exception {
        if (!Files.isDirectory(input) && !Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Entrada inexistente ou inválida: " + input);
        }
        if (Files.isRegularFile(input)) {
            String name = input.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".jrxml") && !name.endsWith(".groovy")) {
                throw new IllegalArgumentException(
                        "O arquivo de entrada deve possuir extensão .jrxml ou .groovy: " + input);
            }
        }
        if (!dryRun) {
            if (output == null) {
                throw new IllegalArgumentException("Informe --output ou utilize --dry-run.");
            }
            if (input.equals(output) || (Files.isDirectory(input) && output.startsWith(input))) {
                throw new IllegalArgumentException("A saída não pode ser igual ou ficar dentro da entrada.");
            }
            Files.createDirectories(output);
        }
    }

    private static List<Path> findFiles(Path input, String extension) throws Exception {
        if (Files.isRegularFile(input)) {
            return input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)
                    ? List.of(input) : List.of();
        }
        try (Stream<Path> paths = Files.walk(input)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(input, path))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(extension))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private List<Path> findDirectJrxmlGraph(Path master, Path allowedRoot) {
        Set<Path> discovered = new LinkedHashSet<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(master);
        while (!pending.isEmpty()) {
            Path current = pending.removeFirst().toAbsolutePath().normalize();
            if (!discovered.add(current)) {
                continue;
            }
            try {
                String xml = Files.readString(current, StandardCharsets.UTF_8);
                Document document = xmlParser.parse(xml, current);
                var nodes = document.getElementsByTagNameNS("*", "subreportExpression");
                for (int index = 0; index < nodes.getLength(); index++) {
                    String expression = nodes.item(index).getTextContent().trim();
                    if (expression.length() < 2 || !expression.startsWith("\"")
                            || !expression.endsWith("\"")) {
                        continue;
                    }
                    String reference = expression.substring(1, expression.length() - 1);
                    int extension = reference.lastIndexOf('.');
                    String jrxmlReference = extension < 0
                            ? reference + ".jrxml" : reference.substring(0, extension) + ".jrxml";
                    Path candidate = current.getParent().resolve(jrxmlReference)
                            .toAbsolutePath().normalize();
                    if (candidate.startsWith(allowedRoot) && Files.isRegularFile(candidate)
                            && !discovered.contains(candidate)) {
                        pending.addLast(candidate);
                    }
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // O processamento principal registrará a falha e manterá o arquivo original.
            }
        }
        return discovered.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static boolean isIgnoredPath(Path input, Path path) {
        Path relative = input.relativize(path);
        for (Path component : relative) {
            String name = component.toString().toLowerCase(Locale.ROOT);
            if (Set.of(".git", ".idea", "target", "build", "output", "node_modules")
                    .contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldGroupByProject(Path input, List<Path> files) {
        Set<String> firstDirectories = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Path file : files) {
            Path relative = input.relativize(file);
            if (relative.getNameCount() > 1) {
                firstDirectories.add(relative.getName(0).toString());
            }
        }
        long projectDirectories = firstDirectories.stream()
                .map(input::resolve)
                .filter(ConversionService::looksLikeProjectRoot)
                .count();
        return projectDirectories > 1;
    }

    private static boolean looksLikeProjectRoot(Path directory) {
        return Files.isRegularFile(directory.resolve("pom.xml"))
                || Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"))
                || Files.isDirectory(directory.resolve(".git"))
                || Files.isDirectory(directory.resolve("src/main"));
    }

    private static void copySupportingFiles(Path input, Path output, boolean overwrite)
            throws Exception {
        try (Stream<Path> paths = Files.walk(input)) {
            for (Path source : paths.filter(Files::isRegularFile)
                    .filter(path -> !isIgnoredPath(input, path))
                    .filter(path -> !isConvertibleSource(path))
                    .toList()) {
                copyOriginal(output, input.relativize(source), source, overwrite);
            }
        }
    }

    private static boolean isConvertibleSource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jrxml") || name.endsWith(".groovy");
    }

    private static void clearDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> entries = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path entry : entries) {
                Files.delete(entry);
            }
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

    private static void writeOutput(Path output, Path relative, String content, boolean overwrite,
                                    Charset charset, boolean utf8Bom) throws Exception {
        Path destination = output.resolve(relative);
        if (Files.exists(destination) && !overwrite) {
            throw new IllegalArgumentException("Arquivo de saída já existe; use --overwrite: " + destination);
        }
        Files.createDirectories(destination.getParent());
        byte[] encoded = content.getBytes(charset);
        if (utf8Bom && charset.equals(StandardCharsets.UTF_8)) {
            byte[] withBom = new byte[encoded.length + 3];
            withBom[0] = (byte) 0xEF;
            withBom[1] = (byte) 0xBB;
            withBom[2] = (byte) 0xBF;
            System.arraycopy(encoded, 0, withBom, 3, encoded.length);
            encoded = withBom;
        }
        Files.write(destination, encoded);
    }

    private static void copyOriginal(Path output, Path relative, Path source,
                                     boolean overwrite) throws Exception {
        Path destination = output.resolve(relative);
        if (Files.exists(destination) && !overwrite) {
            throw new IllegalArgumentException("Arquivo de saída já existe; use --overwrite: " + destination);
        }
        Files.createDirectories(destination.getParent());
        Files.write(destination, Files.readAllBytes(source));
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return "Falha ao processar o arquivo: " + exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : " - " + message);
    }

    private static DecodedSource readGroovySource(Path source) throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        boolean utf8Bom = bytes.length >= 3 && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
        int offset = utf8Bom ? 3 : 0;
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            return new DecodedSource(text, StandardCharsets.UTF_8, utf8Bom);
        } catch (CharacterCodingException ignored) {
            String text = WINDOWS_1252.decode(ByteBuffer.wrap(bytes)).toString();
            return new DecodedSource(text, WINDOWS_1252, false);
        }
    }

    private static ConversionSummary summarize(int jrxmlFiles, List<QueryResult> results,
                                                int references, int located, int resolved,
                                                int ambiguous, int dynamic, int missing,
                                                int groovyFiles, List<GroovySqlResult> groovyResults) {
        long converted = results.stream().filter(r -> r.status() == ConversionStatus.CONVERTED).count();
        long empty = results.stream().filter(r -> r.status() == ConversionStatus.EMPTY).count();
        long ignored = results.stream().filter(r -> r.status() == ConversionStatus.IGNORED).count();
        long failed = results.stream().filter(r -> r.status() == ConversionStatus.FAILED).count();
        long convertedGroovy = groovyResults.stream()
                .filter(r -> r.status() == ConversionStatus.CONVERTED).count();
        long reviewGroovy = groovyResults.stream()
                .filter(r -> r.status() == ConversionStatus.REVIEW).count();
        long failedGroovy = groovyResults.stream()
                .filter(r -> r.status() == ConversionStatus.FAILED).count();
        return new ConversionSummary(jrxmlFiles, results.size(), converted, empty, ignored, failed,
                references, located, resolved, ambiguous, dynamic, missing, groovyFiles,
                groovyResults.size(), convertedGroovy, reviewGroovy, failedGroovy);
    }

    private record DecodedSource(String text, Charset charset, boolean utf8Bom) {
    }

}
