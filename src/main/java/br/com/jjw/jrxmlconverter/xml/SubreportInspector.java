package br.com.jjw.jrxmlconverter.xml;

import br.com.jjw.jrxmlconverter.domain.SubreportStats;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;

public final class SubreportInspector {
    public SubreportStats inspect(Document document, Path jrxml) {
        NodeList nodes = document.getElementsByTagNameNS("*", "subreportExpression");
        int resolved = 0;
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            String expression = node.getTextContent().trim();
            if (isLiteral(expression) && resolvesLocally(expression, jrxml)) {
                resolved++;
            }
        }
        return new SubreportStats(nodes.getLength(), resolved);
    }

    private static boolean isLiteral(String expression) {
        return expression.length() >= 2 && expression.startsWith("\"") && expression.endsWith("\"");
    }

    private static boolean resolvesLocally(String expression, Path jrxml) {
        String reference = expression.substring(1, expression.length() - 1);
        String fileName = Path.of(reference).getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String baseName = extension < 0 ? fileName : fileName.substring(0, extension);
        return Files.isRegularFile(jrxml.getParent().resolve(baseName + ".jrxml"));
    }
}
