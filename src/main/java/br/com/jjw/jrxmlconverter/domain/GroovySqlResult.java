package br.com.jjw.jrxmlconverter.domain;

import java.nio.file.Path;

public record GroovySqlResult(
        Path file,
        int sqlIndex,
        int line,
        ConversionStatus status,
        String originalSql,
        String convertedSql,
        int dynamicExpressions,
        String message) {

    public boolean succeeded() {
        return status == ConversionStatus.CONVERTED;
    }

    public static GroovySqlResult converted(Path file, int index, int line, String original,
                                             String converted, int expressions) {
        return new GroovySqlResult(file, index, line, ConversionStatus.CONVERTED,
                original, converted, expressions, "");
    }

    public static GroovySqlResult review(Path file, int index, int line, String original,
                                          String message) {
        return review(file, index, line, original, 0, message);
    }

    public static GroovySqlResult review(Path file, int index, int line, String original,
                                          int expressions, String message) {
        return new GroovySqlResult(file, index, line, ConversionStatus.REVIEW,
                original, original, expressions, message);
    }

    public static GroovySqlResult failed(Path file, int index, int line, String original,
                                          int expressions, String message) {
        return new GroovySqlResult(file, index, line, ConversionStatus.FAILED,
                original, original, expressions, message);
    }
}
