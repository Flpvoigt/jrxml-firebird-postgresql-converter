package br.com.jjw.jrxmlconverter.domain;

public record SubreportStats(int total, int located, int resolved, int ambiguous,
                             int dynamic, int missing) {
}
