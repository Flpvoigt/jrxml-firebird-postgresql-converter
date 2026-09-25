package io.github.flpvoigt.jrxmlconverter.domain;

import java.util.List;

public record JrxmlConversion(String xml, List<QueryResult> queryResults) {
    public JrxmlConversion {
        queryResults = List.copyOf(queryResults);
    }
}
