package br.com.jjw.jrxmlconverter.domain;

public record ConversionSummary(
        int jrxmlFiles,
        int queryEntries,
        long convertedQueries,
        long emptyQueries,
        long ignoredQueries,
        long failedQueries,
        int subreportReferences,
        int locatedSubreports,
        int resolvedSubreports,
        int ambiguousSubreports,
        int dynamicSubreports,
        int missingSubreports,
        int groovyFiles,
        int groovySqlEntries,
        long convertedGroovySql,
        long reviewGroovySql,
        long failedGroovySql) {

    public ConversionSummary(int jrxmlFiles, int queryEntries, long convertedQueries,
                             long emptyQueries, long ignoredQueries, long failedQueries,
                             int subreportReferences, int resolvedSubreports,
                             int groovyFiles, int groovySqlEntries, long convertedGroovySql,
                             long reviewGroovySql, long failedGroovySql) {
        this(jrxmlFiles, queryEntries, convertedQueries, emptyQueries, ignoredQueries,
                failedQueries, subreportReferences, resolvedSubreports, resolvedSubreports,
                0, 0, Math.max(0, subreportReferences - resolvedSubreports), groovyFiles,
                groovySqlEntries, convertedGroovySql, reviewGroovySql, failedGroovySql);
    }

    public boolean hasProblems() {
        return failedQueries > 0 || reviewGroovySql > 0 || failedGroovySql > 0
                || ambiguousSubreports > 0 || dynamicSubreports > 0 || missingSubreports > 0;
    }
}
