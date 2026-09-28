package br.com.jjw.jrxmlconverter.jrxml;

import br.com.jjw.jrxmlconverter.domain.JrxmlConversion;
import br.com.jjw.jrxmlconverter.domain.QueryResult;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JrxmlProcessor {
    private static final Pattern QUERY_ELEMENT = Pattern.compile(
            "(?s)(<queryString\\b[^>]*>)(.*?)(</queryString>)");
    private static final Pattern CDATA_CONTENT = Pattern.compile(
            "(?s)^(\\s*<!\\[CDATA\\[)(.*?)(]]>\\s*)$");

    private final FirebirdToPostgresSqlConverter sqlConverter;

    public JrxmlProcessor(FirebirdToPostgresSqlConverter sqlConverter) {
        this.sqlConverter = sqlConverter;
    }

    public JrxmlConversion convert(Path relativeFile, String xml, Document document) {
        NodeList queryNodes = document.getElementsByTagNameNS("*", "queryString");
        Matcher matcher = QUERY_ELEMENT.matcher(xml);
        StringBuilder convertedXml = new StringBuilder(xml.length() + 256);
        List<QueryResult> results = new ArrayList<>();
        int queryIndex = 0;

        while (matcher.find()) {
            queryIndex++;
            Node queryNode = queryNodes.item(queryIndex - 1);
            String language = queryLanguage(queryNode);
            String rawContent = matcher.group(2);
            Matcher cdata = CDATA_CONTENT.matcher(rawContent);
            String originalSql = cdata.matches() ? cdata.group(2) : queryNode.getTextContent();
            QueryResult result;
            String replacementContent = rawContent;
            if (!language.equalsIgnoreCase("SQL")) {
                result = QueryResult.ignored(relativeFile, queryIndex, originalSql, language);
            } else if (!cdata.matches()) {
                if (originalSql.isBlank()) {
                    result = QueryResult.empty(relativeFile, queryIndex);
                } else {
                    result = sqlConverter.convert(relativeFile, queryIndex, originalSql);
                    if (result.succeeded()) {
                        replacementContent = escapeXmlText(result.convertedSql());
                    }
                }
            } else {
                result = sqlConverter.convert(relativeFile, queryIndex, originalSql);
                String replacementSql = result.succeeded() ? result.convertedSql() : originalSql;
                if (replacementSql.contains("]]>") ) {
                    result = QueryResult.failed(relativeFile, queryIndex, originalSql,
                            "A consulta convertida contém o terminador de CDATA ]]>.");
                    replacementSql = originalSql;
                }
                replacementContent = cdata.group(1) + replacementSql + cdata.group(3);
            }
            results.add(result);
            matcher.appendReplacement(convertedXml,
                    Matcher.quoteReplacement(matcher.group(1) + replacementContent + matcher.group(3)));
        }
        matcher.appendTail(convertedXml);

        if (queryIndex != queryNodes.getLength()) {
            results.add(QueryResult.failed(relativeFile, 0, "",
                    "Foram encontrados " + queryNodes.getLength() + " queryString no XML, mas apenas "
                            + queryIndex + " estavam no formato CDATA suportado."));
        }
        return new JrxmlConversion(convertedXml.toString(), results);
    }

    private static String queryLanguage(Node queryNode) {
        Node language = queryNode.getAttributes().getNamedItem("language");
        return language == null || language.getNodeValue().isBlank() ? "SQL" : language.getNodeValue();
    }

    private static String escapeXmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
