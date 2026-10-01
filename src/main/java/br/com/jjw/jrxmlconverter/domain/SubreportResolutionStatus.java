package br.com.jjw.jrxmlconverter.domain;

public enum SubreportResolutionStatus {
    LOCAL,
    SHARED,
    AMBIGUOUS,
    DYNAMIC,
    MISSING;

    public boolean isLocated() {
        return this == LOCAL || this == SHARED || this == AMBIGUOUS;
    }

    public boolean isResolved() {
        return this == LOCAL || this == SHARED;
    }

    public boolean requiresAttention() {
        return this == AMBIGUOUS || this == DYNAMIC || this == MISSING;
    }
}
