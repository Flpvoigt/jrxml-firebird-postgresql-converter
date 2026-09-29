package br.com.jjw.jrxmlconverter.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reconhece operações de conjunto em que cada ramo possui paginação Firebird
 * própria. Casos que não comprovam esse escopo são recusados e continuam no
 * fluxo conservador do conversor principal.
 */
final class SetOperationPaginationConverter {
    private static final String VALUE = "(?:\\$\\{[^{}]+}|\\$P!?\\{[^{}]+}|\\d+)";
    private static final Pattern BRANCH_PAGINATION = Pattern.compile(
            "(?is)^\\s*SELECT\\s+(?:(?:FIRST\\s+" + VALUE + "(?:\\s+SKIP\\s+" + VALUE + ")?)"
                    + "|(?:SKIP\\s+" + VALUE + "(?:\\s+FIRST\\s+" + VALUE + ")?))\\s+");

    Optional<SetOperation> analyze(String sql) {
        List<OperatorPosition> positions = topLevelOperators(sql);
        if (positions.isEmpty()) {
            return Optional.empty();
        }

        List<String> branches = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        boolean hasPagination = false;
        int start = 0;
        for (OperatorPosition position : positions) {
            String branch = sql.substring(start, position.start()).trim();
            if (!isSafeBranch(branch)) {
                return Optional.empty();
            }
            hasPagination |= BRANCH_PAGINATION.matcher(branch).find();
            branches.add(branch);
            operators.add(position.operator());
            start = position.end();
        }

        String lastBranch = sql.substring(start).trim();
        if (!isSafeBranch(lastBranch)) {
            return Optional.empty();
        }
        hasPagination |= BRANCH_PAGINATION.matcher(lastBranch).find();
        if (!hasPagination) {
            return Optional.empty();
        }
        branches.add(lastBranch);
        return Optional.of(new SetOperation(List.copyOf(branches), List.copyOf(operators)));
    }

    boolean hasTopLevelSetOperator(String sql) {
        return !topLevelOperators(sql).isEmpty();
    }

    String combine(SetOperation operation, List<String> convertedBranches) {
        if (convertedBranches.size() != operation.branches().size()) {
            throw new IllegalArgumentException("Quantidade de ramos convertidos incompatível.");
        }
        StringBuilder sql = new StringBuilder();
        for (int index = 0; index < convertedBranches.size(); index++) {
            if (index > 0) {
                sql.append('\n').append(operation.operators().get(index - 1)).append('\n');
            }
            sql.append("(\n").append(convertedBranches.get(index).strip()).append("\n)");
        }
        return sql.toString();
    }

    private static boolean isSafeBranch(String branch) {
        return !branch.isBlank()
                && branch.matches("(?is)^\\s*SELECT\\b.+")
                && !hasUnsafeTopLevelClause(branch);
    }

    private static boolean hasUnsafeTopLevelClause(String sql) {
        for (WordPosition word : topLevelWords(sql)) {
            if (switch (word.word()) {
                case "ORDER", "ROWS", "LIMIT", "OFFSET", "FETCH", "FOR" -> true;
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    private static List<OperatorPosition> topLevelOperators(String sql) {
        List<WordPosition> words = topLevelWords(sql);
        List<OperatorPosition> operators = new ArrayList<>();
        for (int index = 0; index < words.size(); index++) {
            WordPosition word = words.get(index);
            if (!word.word().equals("UNION") && !word.word().equals("INTERSECT")
                    && !word.word().equals("EXCEPT")) {
                continue;
            }
            int end = word.end();
            String operator = word.word();
            if (index + 1 < words.size()) {
                WordPosition modifier = words.get(index + 1);
                if (onlyWhitespace(sql, end, modifier.start())
                        && (modifier.word().equals("ALL") || modifier.word().equals("DISTINCT"))) {
                    operator += " " + modifier.word();
                    end = modifier.end();
                    index++;
                }
            }
            operators.add(new OperatorPosition(word.start(), end, operator));
        }
        return operators;
    }

    private static List<WordPosition> topLevelWords(String sql) {
        List<WordPosition> words = new ArrayList<>();
        int depth = 0;
        boolean singleQuote = false;
        boolean doubleQuote = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

            if (lineComment) {
                lineComment = current != '\n' && current != '\r';
                index++;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index += 2;
                } else {
                    index++;
                }
                continue;
            }
            if (singleQuote) {
                if (current == '\'' && next == '\'') {
                    index += 2;
                } else {
                    if (current == '\'') {
                        singleQuote = false;
                    }
                    index++;
                }
                continue;
            }
            if (doubleQuote) {
                if (current == '"' && next == '"') {
                    index += 2;
                } else {
                    if (current == '"') {
                        doubleQuote = false;
                    }
                    index++;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                index += 2;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                index += 2;
                continue;
            }
            if (current == '\'') {
                singleQuote = true;
                index++;
                continue;
            }
            if (current == '"') {
                doubleQuote = true;
                index++;
                continue;
            }
            if (current == '(') {
                depth++;
                index++;
                continue;
            }
            if (current == ')') {
                depth--;
                if (depth < 0) {
                    return List.of();
                }
                index++;
                continue;
            }
            if (depth == 0 && (Character.isLetter(current) || current == '_')) {
                int end = index + 1;
                while (end < sql.length()
                        && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                words.add(new WordPosition(index, end,
                        sql.substring(index, end).toUpperCase(Locale.ROOT)));
                index = end;
                continue;
            }
            index++;
        }
        return depth == 0 && !singleQuote && !doubleQuote && !blockComment ? words : List.of();
    }

    private static boolean onlyWhitespace(String sql, int start, int end) {
        return sql.substring(start, end).isBlank();
    }

    record SetOperation(List<String> branches, List<String> operators) {
    }

    private record OperatorPosition(int start, int end, String operator) {
    }

    private record WordPosition(int start, int end, String word) {
    }
}
