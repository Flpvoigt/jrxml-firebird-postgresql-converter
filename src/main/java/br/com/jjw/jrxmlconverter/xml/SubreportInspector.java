package br.com.jjw.jrxmlconverter.xml;

import br.com.jjw.jrxmlconverter.domain.SubreportInspection;
import br.com.jjw.jrxmlconverter.domain.SubreportReferenceResult;
import br.com.jjw.jrxmlconverter.domain.SubreportResolutionStatus;
import br.com.jjw.jrxmlconverter.domain.SubreportStats;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SubreportInspector {
    private static final Pattern STRING_LITERAL = Pattern.compile("^\\\"(?:\\\\.|[^\\\"\\\\])*\\\"$");

    public Catalog catalog(List<Path> jrxmlFiles) {
        Set<Path> available = new HashSet<>();
        Map<String, List<Path>> byBaseName = new HashMap<>();
        for (Path file : jrxmlFiles) {
            Path normalized = file.toAbsolutePath().normalize();
            available.add(normalized);
            byBaseName.computeIfAbsent(baseName(normalized), ignored -> new ArrayList<>())
                    .add(normalized);
        }
        return new Catalog(Set.copyOf(available), immutableIndex(byBaseName));
    }

    public SubreportInspection inspect(Document document, Path jrxml, Path discoveryRoot,
                                       Catalog catalog) {
        NodeList nodes = document.getElementsByTagNameNS("*", "subreportExpression");
        List<SubreportReferenceResult> references = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            String expression = node.getTextContent().trim();
            references.add(resolve(jrxml, discoveryRoot, index + 1, expression, catalog));
        }

        int located = (int) references.stream().filter(result -> result.status().isLocated()).count();
        int resolved = (int) references.stream().filter(result -> result.status().isResolved()).count();
        int ambiguous = count(references, SubreportResolutionStatus.AMBIGUOUS);
        int dynamic = count(references, SubreportResolutionStatus.DYNAMIC);
        int missing = count(references, SubreportResolutionStatus.MISSING);
        return new SubreportInspection(
                new SubreportStats(references.size(), located, resolved, ambiguous, dynamic, missing),
                references);
    }

    private static SubreportReferenceResult resolve(Path jrxml, Path discoveryRoot,
                                                    int referenceIndex, String expression,
                                                    Catalog catalog) {
        Path relativeOwner = relative(discoveryRoot, jrxml);
        if (!isLiteral(expression)) {
            return new SubreportReferenceResult(relativeOwner, referenceIndex, expression,
                    SubreportResolutionStatus.DYNAMIC, List.of());
        }

        String reference = expression.substring(1, expression.length() - 1);
        String jrxmlReference = withJrxmlExtension(reference);
        Path exact = jrxml.getParent().resolve(jrxmlReference).toAbsolutePath().normalize();
        if (catalog.available().contains(exact) && Files.isRegularFile(exact)) {
            return new SubreportReferenceResult(relativeOwner, referenceIndex, expression,
                    SubreportResolutionStatus.LOCAL, List.of(relative(discoveryRoot, exact)));
        }

        String referencedBaseName = baseName(Path.of(jrxmlReference));
        List<Path> matches = catalog.byBaseName().getOrDefault(referencedBaseName, List.of());
        List<Path> relativeMatches = matches.stream()
                .map(candidate -> relative(discoveryRoot, candidate))
                .toList();
        SubreportResolutionStatus status = switch (matches.size()) {
            case 0 -> SubreportResolutionStatus.MISSING;
            case 1 -> SubreportResolutionStatus.SHARED;
            default -> SubreportResolutionStatus.AMBIGUOUS;
        };
        return new SubreportReferenceResult(relativeOwner, referenceIndex, expression,
                status, relativeMatches);
    }

    private static boolean isLiteral(String expression) {
        return STRING_LITERAL.matcher(expression).matches();
    }

    private static String withJrxmlExtension(String reference) {
        int slash = Math.max(reference.lastIndexOf('/'), reference.lastIndexOf('\\'));
        int extension = reference.lastIndexOf('.');
        return extension > slash ? reference.substring(0, extension) + ".jrxml"
                : reference + ".jrxml";
    }

    private static String baseName(Path path) {
        String fileName = path.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        return (extension < 0 ? fileName : fileName.substring(0, extension))
                .toLowerCase(Locale.ROOT);
    }

    private static Path relative(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedPath) : normalizedPath;
    }

    private static int count(List<SubreportReferenceResult> references,
                             SubreportResolutionStatus status) {
        return (int) references.stream().filter(result -> result.status() == status).count();
    }

    private static Map<String, List<Path>> immutableIndex(Map<String, List<Path>> source) {
        Map<String, List<Path>> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Map.copyOf(result);
    }

    public record Catalog(Set<Path> available, Map<String, List<Path>> byBaseName) {
    }
}
