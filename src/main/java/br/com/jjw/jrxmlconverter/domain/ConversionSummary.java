package br.com.jjw.jrxmlconverter.domain;

public record ConversionSummary(
        int files,
        int queryEntries,
        long convertedQueries,
        long emptyQueries,
        long failedQueries,
        int subreportReferences,
        int resolvedSubreports) {
}
