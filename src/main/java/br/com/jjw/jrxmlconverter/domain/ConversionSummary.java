package br.com.jjw.jrxmlconverter.domain;

public record ConversionSummary(
        int jrxmlFiles,
        int queryEntries,
        long convertedQueries,
        long emptyQueries,
        long ignoredQueries,
        long failedQueries,
        int subreportReferences,
        int resolvedSubreports,
        int groovyFiles,
        int groovySqlEntries,
        long convertedGroovySql,
        long reviewGroovySql,
        long failedGroovySql) {

    public boolean hasProblems() {
        return failedQueries > 0 || reviewGroovySql > 0 || failedGroovySql > 0;
    }
}
