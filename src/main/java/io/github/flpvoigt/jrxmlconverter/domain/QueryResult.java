package io.github.flpvoigt.jrxmlconverter.domain;

import java.nio.file.Path;

public record QueryResult(
        Path file,
        int queryIndex,
        ConversionStatus status,
        String originalSql,
        String convertedSql,
        int jasperTokens,
        String message) {

    public boolean succeeded() {
        return status == ConversionStatus.CONVERTED;
    }

    public static QueryResult converted(Path file, int index, String original, String converted, int tokens) {
        return new QueryResult(file, index, ConversionStatus.CONVERTED, original, converted, tokens, "");
    }

    public static QueryResult empty(Path file, int index) {
        return new QueryResult(file, index, ConversionStatus.EMPTY, "", "", 0, "QueryString vazia");
    }

    public static QueryResult failed(Path file, int index, String original, String message) {
        return new QueryResult(file, index, ConversionStatus.FAILED, original, original, 0, message);
    }
}
