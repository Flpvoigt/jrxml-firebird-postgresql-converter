package br.com.jjw.jrxmlconverter.sql;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FirebirdUpsertConverter {
    private static final Pattern PREFIX = Pattern.compile(
            "(?is)^\\s*UPDATE\\s+OR\\s+INSERT\\s+INTO\\s+([A-Z_][A-Z0-9_$.]*|\"[^\"]+\")\\s*\\(");
    private static final Pattern VALUES = Pattern.compile("(?is)\\G\\s*VALUES\\s*\\(");
    private static final Pattern MATCHING = Pattern.compile("(?is)\\G\\s*MATCHING\\s*\\(");

    Optional<String> convert(String sql) {
        Matcher prefix = PREFIX.matcher(sql);
        if (!prefix.find()) {
            return Optional.empty();
        }

        int columnsOpening = prefix.end() - 1;
        int columnsClosing = FirebirdToPostgresSqlConverter.findClosingParenthesis(sql, columnsOpening);
        if (columnsClosing < 0) {
            return Optional.empty();
        }

        Matcher valuesMatcher = VALUES.matcher(sql);
        valuesMatcher.region(columnsClosing + 1, sql.length());
        if (!valuesMatcher.find()) {
            return Optional.empty();
        }
        int valuesOpening = valuesMatcher.end() - 1;
        int valuesClosing = FirebirdToPostgresSqlConverter.findClosingParenthesis(sql, valuesOpening);
        if (valuesClosing < 0) {
            return Optional.empty();
        }

        Matcher matchingMatcher = MATCHING.matcher(sql);
        matchingMatcher.region(valuesClosing + 1, sql.length());
        if (!matchingMatcher.find()) {
            return Optional.empty();
        }
        int matchingOpening = matchingMatcher.end() - 1;
        int matchingClosing = FirebirdToPostgresSqlConverter.findClosingParenthesis(sql, matchingOpening);
        if (matchingClosing < 0 || !onlyTerminatorAfter(sql, matchingClosing + 1)) {
            return Optional.empty();
        }

        List<String> columns = splitTopLevel(sql.substring(columnsOpening + 1, columnsClosing));
        List<String> values = splitTopLevel(sql.substring(valuesOpening + 1, valuesClosing));
        List<String> matching = splitTopLevel(sql.substring(matchingOpening + 1, matchingClosing));
        if (columns.isEmpty() || columns.size() != values.size() || matching.isEmpty()) {
            return Optional.empty();
        }

        Set<String> matchingNames = new HashSet<>();
        matching.forEach(column -> matchingNames.add(normalizeIdentifier(column)));
        List<String> updateColumns = columns.stream()
                .filter(column -> !matchingNames.contains(normalizeIdentifier(column)))
                .toList();

        StringBuilder converted = new StringBuilder();
        converted.append("INSERT INTO ").append(prefix.group(1)).append(" (\n    ")
                .append(String.join(",\n    ", columns)).append("\n)\nVALUES (\n    ")
                .append(String.join(",\n    ", values)).append("\n)\nON CONFLICT (")
                .append(String.join(", ", matching)).append(") ");
        if (updateColumns.isEmpty()) {
            converted.append("DO NOTHING");
        } else {
            List<String> assignments = new ArrayList<>();
            for (String column : updateColumns) {
                assignments.add(column + " = EXCLUDED." + column);
            }
            converted.append("DO UPDATE SET\n    ").append(String.join(",\n    ", assignments));
        }
        if (sql.substring(matchingClosing + 1).contains(";")) {
            converted.append(';');
        }
        return Optional.of(converted.toString());
    }

    private static boolean onlyTerminatorAfter(String sql, int start) {
        return sql.substring(start).matches("(?s)\\s*;?\\s*");
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier.trim().replace("\"", "").toUpperCase(Locale.ROOT);
    }

    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        boolean inString = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && inString && index + 1 < text.length()
                    && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
            } else if (!inString && depth == 0 && current == ',') {
                parts.add(text.substring(start, index).trim());
                start = index + 1;
            }
        }
        parts.add(text.substring(start).trim());
        if (parts.stream().anyMatch(String::isEmpty)) {
            return List.of();
        }
        return parts;
    }
}
