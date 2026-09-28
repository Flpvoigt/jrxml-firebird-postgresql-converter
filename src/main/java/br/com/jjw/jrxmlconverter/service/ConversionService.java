package br.com.jjw.jrxmlconverter.service;

import br.com.jjw.jrxmlconverter.cli.CommandLineOptions;
import br.com.jjw.jrxmlconverter.domain.*;
import br.com.jjw.jrxmlconverter.groovy.GroovyProcessor;
import br.com.jjw.jrxmlconverter.jrxml.JrxmlProcessor;
import br.com.jjw.jrxmlconverter.xml.SecureXmlParser;
import br.com.jjw.jrxmlconverter.xml.SubreportInspector;
import org.w3c.dom.Document;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        List<Path> groovyFiles = findFiles(input, ".groovy");
        if (jrxmlFiles.isEmpty() && groovyFiles.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo .jrxml ou .groovy encontrado em " + input);
        }
        List<Path> allFiles = new ArrayList<>(jrxmlFiles);
        allFiles.addAll(groovyFiles);
        boolean groupedByProject = Files.isDirectory(input)
                && shouldGroupByProject(discoveryRoot, allFiles);
        validateUniqueGroovyNames(groovyFiles, discoveryRoot, groupedByProject);
        if (!options.dryRun() && options.overwrite()) {
            clearGroovyOutputs(output, discoveryRoot, groovyFiles, groupedByProject);
        }
        List<QueryResult> queryResults = new ArrayList<>();
        List<GroovySqlResult> groovyResults = new ArrayList<>();
        int subreportReferences = 0;
        int resolvedSubreports = 0;

        for (Path source : jrxmlFiles) {
            Path relative = discoveryRoot.relativize(source);
            ProjectLocation location = locate(discoveryRoot, source, groupedByProject);
            String xml;
            try {
                xml = Files.readString(source, StandardCharsets.UTF_8);
            } catch (Exception exception) {
                queryResults.add(QueryResult.failed(relative, 0, "", failureMessage(exception)));
                if (!options.dryRun()) {
                    copyOriginal(output, location, "jrxml", resourceRelative(location.relative()),
                            source, options.overwrite());
                }
                continue;
            }

            JrxmlConversion conversion;
            try {
                Document document = xmlParser.parse(xml, source);
                SubreportStats stats = subreportInspector.inspect(document, source);
                subreportReferences += stats.total();
                resolvedSubreports += stats.resolved();
                conversion = jrxmlProcessor.convert(relative, xml, document);
                queryResults.addAll(conversion.queryResults());
            } catch (Exception exception) {
                queryResults.add(QueryResult.failed(relative, 0, xml, failureMessage(exception)));
                if (!options.dryRun()) {
                    Path jrxmlOutput = projectOutput(output, location, "jrxml");
                    writeOutput(jrxmlOutput, resourceRelative(location.relative()), xml,
                            options.overwrite());
                }
                continue;
            }
            if (!options.dryRun()) {
                Path jrxmlOutput = projectOutput(output, location, "jrxml");
                writeOutput(jrxmlOutput, resourceRelative(location.relative()),
                        conversion.xml(), options.overwrite());
            }
        }

        for (Path source : groovyFiles) {
            Path relative = discoveryRoot.relativize(source);
            ProjectLocation location = locate(discoveryRoot, source, groupedByProject);
            Path destination = groupedByProject
                    ? Path.of(groovyCategory(location.relative())).resolve(relative.getFileName())
                    : relative.getFileName();
            DecodedSource groovy;
            GroovyConversion conversion;
            try {
                groovy = readGroovySource(source);
                conversion = groovyProcessor.convert(relative, groovy.text());
                groovyResults.addAll(conversion.sqlResults());
            } catch (Exception exception) {
                groovyResults.add(GroovySqlResult.failed(relative, 0, 0, "", 0,
                        failureMessage(exception)));
                if (!options.dryRun()) {
                    copyOriginal(output, location, "groovy", destination, source,
                            options.overwrite());
                }
                continue;
            }
            if (!options.dryRun()) {
                Path groovyOutput = projectOutput(output, location, "groovy");
                writeOutput(groovyOutput, destination, conversion.source(),
                        options.overwrite(), groovy.charset(), groovy.utf8Bom());
            }
        }

        ConversionSummary summary = summarize(
                jrxmlFiles.size(), queryResults, subreportReferences, resolvedSubreports,
                groovyFiles.size(), groovyResults);
        return new ConversionRun(summary, queryResults, groovyResults, groupedByProject);
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
        long complementDirectories = firstDirectories.stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains("complements"))
                .count();
        return complementDirectories > 1;
    }

    private static void validateUniqueGroovyNames(List<Path> files, Path input,
                                                   boolean groupedByProject) {
        Map<String, Path> names = new HashMap<>();
        for (Path file : files) {
            ProjectLocation location = locate(input, file, groupedByProject);
            String key = (groupedByProject
                    ? location.project() + "/" + groovyCategory(location.relative()) + "/"
                    : "") + file.getFileName().toString();
            key = key.toLowerCase(Locale.ROOT);
            Path previous = names.putIfAbsent(key, file);
            if (previous != null) {
                throw new IllegalArgumentException("Dois arquivos Groovy possuem o mesmo nome e não podem "
                        + "ser reunidos na mesma saída: " + input.relativize(previous) + " e "
                        + input.relativize(file));
            }
        }
    }

    private static void clearGroovyOutputs(Path output, Path input, List<Path> groovyFiles,
                                           boolean groupedByProject) throws Exception {
        if (!groupedByProject) {
            clearDirectory(output.resolve("groovy"));
            return;
        }
        Set<String> projects = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Path file : groovyFiles) {
            projects.add(locate(input, file, true).project());
        }
        for (String project : projects) {
            clearDirectory(output.resolve(project).resolve("groovy"));
        }
    }

    private static ProjectLocation locate(Path input, Path file, boolean groupedByProject) {
        Path relative = input.relativize(file);
        if (!groupedByProject) {
            return new ProjectLocation("", relative);
        }
        String project = relative.getName(0).toString();
        Path insideProject = relative.getNameCount() == 1
                ? relative.getFileName() : relative.subpath(1, relative.getNameCount());
        return new ProjectLocation(project, insideProject);
    }

    private static Path projectOutput(Path output, ProjectLocation location, String type) {
        return location.project().isEmpty()
                ? output.resolve(type) : output.resolve(location.project()).resolve(type);
    }

    private static Path resourceRelative(Path relative) {
        for (int index = 0; index < relative.getNameCount(); index++) {
            if (relative.getName(index).toString().equalsIgnoreCase("resources")
                    && index + 1 < relative.getNameCount()) {
                return relative.subpath(index + 1, relative.getNameCount());
            }
        }
        return relative;
    }

    private static String groovyCategory(Path relative) {
        for (int index = 0; index < relative.getNameCount(); index++) {
            if (relative.getName(index).toString().equalsIgnoreCase("resources")
                    && index + 2 < relative.getNameCount()) {
                return relative.getName(index + 2).toString();
            }
        }
        Path parent = relative.getParent();
        return parent == null ? "outros" : parent.getFileName().toString();
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

    private static void copyOriginal(Path output, ProjectLocation location, String type,
                                     Path relative, Path source, boolean overwrite) throws Exception {
        Path destination = projectOutput(output, location, type).resolve(relative);
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
                                                int references, int resolved,
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
                references, resolved, groovyFiles, groovyResults.size(), convertedGroovy,
                reviewGroovy, failedGroovy);
    }

    private record DecodedSource(String text, Charset charset, boolean utf8Bom) {
    }

    private record ProjectLocation(String project, Path relative) {
    }
}
