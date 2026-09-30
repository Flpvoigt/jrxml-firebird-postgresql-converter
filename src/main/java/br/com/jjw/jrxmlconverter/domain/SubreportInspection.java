package br.com.jjw.jrxmlconverter.domain;

import java.util.List;

public record SubreportInspection(SubreportStats stats,
                                  List<SubreportReferenceResult> references) {
    public SubreportInspection {
        references = List.copyOf(references);
    }
}
