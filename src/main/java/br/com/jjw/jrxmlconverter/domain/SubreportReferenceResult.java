package br.com.jjw.jrxmlconverter.domain;

import java.nio.file.Path;
import java.util.List;

public record SubreportReferenceResult(Path file, int referenceIndex, String expression,
                                       SubreportResolutionStatus status,
                                       List<Path> candidates) {
    public SubreportReferenceResult {
        candidates = List.copyOf(candidates);
    }
}
