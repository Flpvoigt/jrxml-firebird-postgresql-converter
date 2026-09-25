package io.github.flpvoigt.jrxmlconverter.domain;

import java.util.List;

public record ConversionRun(ConversionSummary summary, List<QueryResult> queryResults) {
    public ConversionRun {
        queryResults = List.copyOf(queryResults);
    }
}
