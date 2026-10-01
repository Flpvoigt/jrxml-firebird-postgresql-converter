package br.com.jjw.jrxmlconverter.domain;

import java.util.List;

public record ConversionRun(ConversionSummary summary, List<QueryResult> queryResults,
                            List<GroovySqlResult> groovyResults,
                            List<SubreportReferenceResult> subreportReferences,
                            boolean groupedByProject) {
    public ConversionRun {
        queryResults = List.copyOf(queryResults);
        groovyResults = List.copyOf(groovyResults);
        subreportReferences = List.copyOf(subreportReferences);
    }

    public ConversionRun(ConversionSummary summary, List<QueryResult> queryResults,
                         List<GroovySqlResult> groovyResults, boolean groupedByProject) {
        this(summary, queryResults, groovyResults, List.of(), groupedByProject);
    }
}
