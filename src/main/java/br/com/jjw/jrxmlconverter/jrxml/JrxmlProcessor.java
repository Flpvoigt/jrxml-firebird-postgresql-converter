package br.com.jjw.jrxmlconverter.jrxml;

import br.com.jjw.jrxmlconverter.domain.JrxmlConversion;
import br.com.jjw.jrxmlconverter.domain.QueryResult;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JrxmlProcessor {
    private static final Pattern QUERY_CDATA = Pattern.compile(
            "(?s)(<queryString\\b[^>]*>\\s*<!\\[CDATA\\[)(.*?)(]]>\\s*</queryString>)");

    private final FirebirdToPostgresSqlConverter sqlConverter;

    public JrxmlProcessor(FirebirdToPostgresSqlConverter sqlConverter) {
        this.sqlConverter = sqlConverter;
    }

    public JrxmlConversion convert(Path relativeFile, String xml, Document document) {
        NodeList queryNodes = document.getElementsByTagNameNS("*", "queryString");
        Matcher matcher = QUERY_CDATA.matcher(xml);
        StringBuilder convertedXml = new StringBuilder(xml.length() + 256);
        List<QueryResult> results = new ArrayList<>();
        int queryIndex = 0;

        while (matcher.find()) {
            queryIndex++;
            String originalSql = matcher.group(2);
            QueryResult result = sqlConverter.convert(relativeFile, queryIndex, originalSql);
            results.add(result);

            String replacementSql = result.succeeded() ? result.convertedSql() : originalSql;
            if (replacementSql.contains("]]>") ) {
                result = QueryResult.failed(relativeFile, queryIndex, originalSql,
                        "A consulta convertida contém o terminador de CDATA ]]>.");
                results.set(results.size() - 1, result);
                replacementSql = originalSql;
            }
            matcher.appendReplacement(convertedXml,
                    Matcher.quoteReplacement(matcher.group(1) + replacementSql + matcher.group(3)));
        }
        matcher.appendTail(convertedXml);

        if (queryIndex != queryNodes.getLength()) {
            results.add(QueryResult.failed(relativeFile, 0, "",
                    "Foram encontrados " + queryNodes.getLength() + " queryString no XML, mas apenas "
                            + queryIndex + " estavam no formato CDATA suportado."));
        }
        return new JrxmlConversion(convertedXml.toString(), results);
    }
}
