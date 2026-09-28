package br.com.jjw.jrxmlconverter.domain;

import java.util.List;

public record ConversionRun(ConversionSummary summary, List<QueryResult> queryResults,
                            List<GroovySqlResult> groovyResults, boolean groupedByProject) {
    public ConversionRun {
        queryResults = List.copyOf(queryResults);
        groovyResults = List.copyOf(groovyResults);
    }
}
