package br.com.jjw.jrxmlconverter.domain;

import java.util.List;

public record GroovyConversion(String source, List<GroovySqlResult> sqlResults) {
    public GroovyConversion {
        sqlResults = List.copyOf(sqlResults);
    }
}
