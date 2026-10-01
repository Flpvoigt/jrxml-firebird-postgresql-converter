package br.com.jjw.jrxmlconverter.groovy;

import br.com.jjw.jrxmlconverter.domain.GroovyConversion;
import br.com.jjw.jrxmlconverter.domain.GroovySqlResult;
import br.com.jjw.jrxmlconverter.domain.QueryResult;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GroovyProcessor {
    private static final Pattern COMPLETE_SQL = Pattern.compile(
            "(?is)^\\s*(?:"
                    + "SELECT\\s+.+|"
                    + "WITH\\s+.+|"
                    + "INSERT\\s+INTO\\s+.+|"
                    + "UPDATE(?:\\s+OR\\s+INSERT\\s+INTO)?\\s+.+|"
                    + "DELETE\\s+FROM\\s+.+|"
                    + "MERGE\\s+INTO\\s+.+|"
                    + "EXECUTE\\s+BLOCK\\s+.+)$");
    private static final Pattern SQL_FRAGMENT = Pattern.compile(
            "(?is)^\\s*(?:AND|OR|WHERE|JOIN|LEFT\\s+JOIN|RIGHT\\s+JOIN|INNER\\s+JOIN|"
                    + "ORDER\\s+BY|GROUP\\s+BY|HAVING|SET|VALUES)\\b.*\\b(?:SELECT|INSERT|UPDATE|DELETE)\\b");
    private static final Pattern EXPLICIT_DATABASE_CONNECTION = Pattern.compile(
            "(?i)jdbc:(?:firebirdsql|postgresql):");

    private final FirebirdToPostgresSqlConverter sqlConverter;
    private final GroovyAstAnalyzer astAnalyzer = new GroovyAstAnalyzer();
    private final GroovyDynamicPaginationRewriter paginationRewriter =
            new GroovyDynamicPaginationRewriter();

    public GroovyProcessor(FirebirdToPostgresSqlConverter sqlConverter) {
        this.sqlConverter = sqlConverter;
    }

    public GroovyConversion convert(Path relativeFile, String source) {
        return convert(relativeFile, source, false);
    }

    public GroovyConversion convert(Path relativeFile, String source,
                                    boolean dualDatabaseMode) {
        if (!dualDatabaseMode) {
            source = paginationRewriter.rewrite(source);
        }
        GroovyAstAnalyzer.Analysis ast = astAnalyzer.analyze(source);
        boolean hasExplicitDatabaseConnection = EXPLICIT_DATABASE_CONNECTION.matcher(source).find();
        List<StringLiteral> literals = findStringLiterals(source);
        List<GroovySqlResult> results = new ArrayList<>();
        StringBuilder convertedSource = new StringBuilder(source.length() + 256);
        int copiedUntil = 0;
        int sqlIndex = 0;

        for (StringLiteral literal : literals) {
            String sql = source.substring(literal.contentStart(), literal.contentEnd());
            boolean completeSql = COMPLETE_SQL.matcher(sql).find();
            boolean fragment = !completeSql && SQL_FRAGMENT.matcher(sql).find();
            if (!completeSql && !fragment) {
                continue;
            }

            sqlIndex++;
            GroovySqlResult result;
            String replacement = sql;
            int dynamicExpressions = countInterpolations(sql, literal.interpolated());
            if (fragment || isConcatenated(source, literal)) {
                String reason = fragment
                        ? "Fragmento de SQL dinâmico; conversão automática por partes seria insegura."
                        : "SQL montado por concatenação; é necessário analisar a expressão completa.";
                result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                        dynamicExpressions, reason);
            } else if (dynamicExpressions > 0 && !sqlConverter.containsKnownFirebirdSyntax(sql)) {
                String unresolvedExpression = unresolvedDynamicStructure(source, literal, sql, ast);
                if (unresolvedExpression == null) {
                    result = GroovySqlResult.converted(relativeFile, sqlIndex, literal.line(), sql,
                            sql, dynamicExpressions);
                } else {
                    result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                            dynamicExpressions,
                            unresolvedExpression);
                }
            } else {
                TokenizedGroovy tokenized = protectInterpolations(sql, literal.interpolated());
                QueryResult query = sqlConverter.convert(
                        relativeFile, sqlIndex, tokenized.sql(), !hasExplicitDatabaseConnection);
                if (query.succeeded()) {
                    replacement = restoreInterpolations(query.convertedSql(), tokenized.tokens());
                    replacement = preserveOuterWhitespace(sql, replacement);
                    result = GroovySqlResult.converted(relativeFile, sqlIndex, literal.line(), sql,
                            replacement, tokenized.tokens().size());
                } else if (query.message().startsWith("UPDATE OR INSERT sem MATCHING")) {
                    result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                            tokenized.tokens().size(), query.message());
                } else if (!tokenized.tokens().isEmpty()) {
                    QueryResult lenient = sqlConverter.convertLenientDynamic(
                            relativeFile, sqlIndex, tokenized.sql());
                    if (lenient.succeeded()) {
                        replacement = restoreInterpolations(lenient.convertedSql(), tokenized.tokens());
                        replacement = preserveOuterWhitespace(sql, replacement);
                        result = GroovySqlResult.converted(relativeFile, sqlIndex, literal.line(), sql,
                                replacement, tokenized.tokens().size());
                    } else {
                        result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                                tokenized.tokens().size(),
                                "SQL dinâmico não pôde ser analisado com segurança: " + query.message());
                    }
                } else if (isDynamicallyExtended(source, literal)) {
                    result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                            tokenized.tokens().size(),
                            "SQL montado em várias etapas; é necessário analisar a expressão completa.");
                } else {
                    result = GroovySqlResult.failed(relativeFile, sqlIndex, literal.line(), sql,
                            tokenized.tokens().size(), query.message());
                }
            }

            results.add(result);
            if (result.succeeded()) {
                if (dualDatabaseMode && !replacement.equals(sql)) {
                    convertedSource.append(source, copiedUntil, literal.delimiterStart())
                            .append(dualDatabaseExpression(source, literal, replacement));
                    copiedUntil = literal.delimiterEnd();
                } else {
                    convertedSource.append(source, copiedUntil, literal.contentStart())
                            .append(replacement);
                    copiedUntil = literal.contentEnd();
                }
            }
        }

        convertedSource.append(source, copiedUntil, source.length());
        return new GroovyConversion(convertedSource.toString(), results);
    }

    private static String dualDatabaseExpression(String source, StringLiteral literal,
                                                 String postgresSql) {
        String firebirdLiteral = source.substring(
                literal.delimiterStart(), literal.delimiterEnd());
        char quote = source.charAt(literal.delimiterStart());
        String postgresDelimiter = String.valueOf(quote).repeat(3);
        String escapedPostgresSql = postgresSql.replace(
                postgresDelimiter, "\\" + postgresDelimiter);
        String postgresLiteral = postgresDelimiter + escapedPostgresSql + postgresDelimiter;

        // O PostgreSQL precisa ser reconhecido explicitamente. Caso a propriedade esteja
        // ausente ou tenha um valor desconhecido, preservamos o SQL Firebird original.
        return "(isPostgreSql() ? " + postgresLiteral + " : " + firebirdLiteral + ")";
    }

    private static String unresolvedDynamicStructure(String source, StringLiteral literal, String sql,
                                                     GroovyAstAnalyzer.Analysis ast) {
        int index = 0;
        while (index < sql.length()) {
            Interpolation interpolation = interpolationAt(sql, index);
            if (interpolation == null) {
                index++;
                continue;
            }
            String variable = simpleVariable(interpolation.expression());
            int useLine = literal.line() + countNewlines(sql, interpolation.start());
            if (isSelectModifierPosition(sql, interpolation.start())) {
                if (variable != null && ast.isOptionalFirebirdPagination(variable, useLine)) {
                    return "A expressão " + interpolation.expression()
                            + " produz FIRST/SKIP condicional. No PostgreSQL, a paginação precisa ser movida para o final da consulta.";
                }
                return "A expressão " + interpolation.expression()
                        + " ocupa a posição de modificador do SELECT e não pôde ser classificada com segurança.";
            }
            if (isStandaloneOnLine(sql, interpolation.start(), interpolation.end())
                    && !isInsideValuesClause(sql, interpolation.start())) {
                if (variable != null && ast.isOptionalPostgresPagination(variable, useLine)
                        && isAtEndOfQuery(sql, interpolation.end())) {
                    index = interpolation.end();
                    continue;
                }
                boolean understoodByAst = variable != null && ast.isOptionalCondition(variable, useLine);
                if (!understoodByAst
                        && !isKnownOptionalClause(source, literal.delimiterStart(), interpolation.expression())) {
                    return "A expressão " + interpolation.expression()
                            + " não pôde ser classificada como cláusula SQL opcional.";
                }
            }
            index = interpolation.end();
        }
        return null;
    }

    private static boolean isAtEndOfQuery(String sql, int position) {
        return sql.substring(position).isBlank();
    }

    private static boolean isSelectModifierPosition(String sql, int position) {
        String before = sql.substring(0, position);
        return before.matches("(?is)^\\s*SELECT\\s*$");
    }

    private static int countNewlines(String text, int endExclusive) {
        int count = 0;
        for (int index = 0; index < endExclusive; index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static boolean isInsideValuesClause(String sql, int position) {
        Matcher matcher = Pattern.compile("(?i)\\bVALUES\\b").matcher(sql.substring(0, position));
        int valuesEnd = -1;
        while (matcher.find()) {
            valuesEnd = matcher.end();
        }
        if (valuesEnd < 0) {
            return false;
        }

        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        for (int index = valuesEnd; index < position; index++) {
            char current = sql.charAt(index);
            if (quoted) {
                if (current == quote && (index == 0 || sql.charAt(index - 1) != '\\')) {
                    quoted = false;
                }
                continue;
            }
            switch (current) {
                case '\'', '"' -> {
                    quoted = true;
                    quote = current;
                }
                case '(' -> depth++;
                case ')' -> depth--;
                default -> {
                    // Os demais caracteres não alteram o nível de parênteses.
                }
            }
        }
        return depth > 0;
    }

    private static boolean isStandaloneOnLine(String sql, int start, int end) {
        int lineStart = sql.lastIndexOf('\n', start - 1) + 1;
        int lineEnd = sql.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = sql.length();
        }
        return sql.substring(lineStart, start).isBlank() && sql.substring(end, lineEnd).isBlank();
    }

    private static boolean isKnownOptionalClause(String source, int beforePosition, String expression) {
        String variable = simpleVariable(expression);
        if (variable == null) {
            return false;
        }

        String[] lines = source.substring(0, beforePosition).split("\\R", -1);
        Pattern assignment = Pattern.compile("^\\s*(?:def\\s+)?" + Pattern.quote(variable) + "\\s*=");
        Pattern anyAssignment = Pattern.compile(
                "^\\s*(?:def\\s+)?[A-Za-z_$][A-Za-z0-9_$]*\\s*=");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index];
            if (!assignment.matcher(line).find()) {
                continue;
            }
            StringBuilder definition = new StringBuilder(line);
            for (int next = index + 1; next < lines.length && definition.indexOf(";") < 0; next++) {
                if (anyAssignment.matcher(lines[next]).find()) {
                    break;
                }
                definition.append('\n').append(lines[next]);
            }
            String definitionText = definition.toString();
            List<StringLiteral> values = findStringLiterals(definitionText);
            if (values.isEmpty()) {
                return false;
            }
            boolean ternary = definitionText.indexOf('?') >= 0 && definitionText.indexOf(':') >= 0;
            if (ternary && values.size() < 2) {
                return false;
            }
            for (StringLiteral value : values) {
                String content = definitionText.substring(value.contentStart(), value.contentEnd()).trim();
                if (!content.isEmpty() && !content.matches("(?is)^(?:AND|OR|WHERE)\\b.+")) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String simpleVariable(String expression) {
        String value = expression;
        if (value.startsWith("${") && value.endsWith("}")) {
            value = value.substring(2, value.length() - 1).trim();
        } else if (value.startsWith("$")) {
            value = value.substring(1);
        }
        return value.matches("[A-Za-z_$][A-Za-z0-9_$]*") ? value : null;
    }

    private static int countInterpolations(String sql, boolean interpolated) {
        if (!interpolated) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index < sql.length()) {
            Interpolation interpolation = interpolationAt(sql, index);
            if (interpolation == null) {
                index++;
            } else {
                count++;
                index = interpolation.end();
            }
        }
        return count;
    }

    private static Interpolation interpolationAt(String text, int index) {
        if (text.charAt(index) != '$' || index + 1 >= text.length()) {
            return null;
        }
        if (text.charAt(index + 1) == '{') {
            int end = skipBalancedExpression(text, index + 1);
            return end > index + 2 ? new Interpolation(index, end, text.substring(index, end)) : null;
        }
        if (!Character.isJavaIdentifierStart(text.charAt(index + 1))) {
            return null;
        }
        int end = index + 2;
        while (end < text.length()) {
            char current = text.charAt(end);
            if (Character.isJavaIdentifierPart(current) || current == '.') {
                end++;
            } else {
                break;
            }
        }
        return new Interpolation(index, end, text.substring(index, end));
    }

    private static boolean isConcatenated(String source, StringLiteral literal) {
        int before = previousNonWhitespace(source, literal.delimiterStart() - 1);
        int after = nextNonWhitespace(source, literal.delimiterEnd());
        return (before >= 0 && source.charAt(before) == '+')
                || (after < source.length() && source.charAt(after) == '+');
    }

    private static boolean isDynamicallyExtended(String source, StringLiteral literal) {
        int lineStart = source.lastIndexOf('\n', literal.delimiterStart()) + 1;
        String prefix = source.substring(lineStart, literal.delimiterStart());
        Matcher assignment = Pattern.compile("(?:def\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*$")
                .matcher(prefix);
        if (!assignment.find()) {
            return false;
        }
        String variable = assignment.group(1);
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(variable) + "\\s*\\+=")
                .matcher(source.substring(literal.delimiterEnd())).find();
    }

    private static int previousNonWhitespace(String source, int index) {
        while (index >= 0 && Character.isWhitespace(source.charAt(index))) {
            index--;
        }
        return index;
    }

    private static int nextNonWhitespace(String source, int index) {
        while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String preserveOuterWhitespace(String original, String converted) {
        int start = 0;
        while (start < original.length() && Character.isWhitespace(original.charAt(start))) {
            start++;
        }
        int end = original.length();
        while (end > start && Character.isWhitespace(original.charAt(end - 1))) {
            end--;
        }
        String prefix = original.substring(0, start);
        int lastNewline = Math.max(prefix.lastIndexOf('\n'), prefix.lastIndexOf('\r'));
        String indentation = lastNewline >= 0 ? prefix.substring(lastNewline + 1) : "";
        String body = converted.strip().replace("\n", "\n" + indentation);
        return prefix + body + original.substring(end);
    }

    private static TokenizedGroovy protectInterpolations(String sql, boolean interpolated) {
        if (!interpolated) {
            return new TokenizedGroovy(sql, Map.of());
        }

        StringBuilder protectedSql = new StringBuilder(sql.length());
        Map<String, String> tokens = new LinkedHashMap<>();
        int index = 0;
        int sequence = 0;
        while (index < sql.length()) {
            if (sql.charAt(index) == '$' && index + 1 < sql.length() && sql.charAt(index + 1) == '{') {
                int expressionEnd = skipBalancedExpression(sql, index + 1);
                if (expressionEnd > index + 2) {
                    String token = "__groovy_token_" + (++sequence) + "__";
                    tokens.put(token, sql.substring(index, expressionEnd));
                    protectedSql.append(token);
                    index = expressionEnd;
                    continue;
                }
            }
            if (sql.charAt(index) == '$' && index + 1 < sql.length()
                    && Character.isJavaIdentifierStart(sql.charAt(index + 1))) {
                int end = index + 2;
                while (end < sql.length()) {
                    char current = sql.charAt(end);
                    if (Character.isJavaIdentifierPart(current) || current == '.') {
                        end++;
                    } else {
                        break;
                    }
                }
                String token = "__groovy_token_" + (++sequence) + "__";
                tokens.put(token, sql.substring(index, end));
                protectedSql.append(token);
                index = end;
                continue;
            }
            protectedSql.append(sql.charAt(index++));
        }
        return new TokenizedGroovy(protectedSql.toString(), tokens);
    }

    private static String restoreInterpolations(String sql, Map<String, String> tokens) {
        String restored = sql;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            restored = restored.replaceAll("(?i)\\\"?" + Pattern.quote(token.getKey()) + "\\\"?",
                    Matcher.quoteReplacement(token.getValue()));
        }
        return restored;
    }

    private static List<StringLiteral> findStringLiterals(String source) {
        List<StringLiteral> literals = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                int newline = source.indexOf('\n', index + 2);
                index = newline < 0 ? source.length() : newline + 1;
                continue;
            }
            if (source.startsWith("/*", index)) {
                int closing = source.indexOf("*/", index + 2);
                index = closing < 0 ? source.length() : closing + 2;
                continue;
            }

            char quote = source.charAt(index);
            if (quote != '\'' && quote != '"') {
                index++;
                continue;
            }

            boolean triple = index + 2 < source.length()
                    && source.charAt(index + 1) == quote && source.charAt(index + 2) == quote;
            int delimiterLength = triple ? 3 : 1;
            int contentStart = index + delimiterLength;
            int cursor = contentStart;
            int contentEnd = -1;
            while (cursor < source.length()) {
                if (quote == '"' && source.charAt(cursor) == '$'
                        && cursor + 1 < source.length() && source.charAt(cursor + 1) == '{') {
                    int expressionEnd = skipBalancedExpression(source, cursor + 1);
                    if (expressionEnd > cursor + 2) {
                        cursor = expressionEnd;
                        continue;
                    }
                }
                if (source.charAt(cursor) == '\\') {
                    cursor = Math.min(source.length(), cursor + 2);
                    continue;
                }
                if (triple ? source.startsWith(String.valueOf(quote).repeat(3), cursor)
                        : source.charAt(cursor) == quote) {
                    contentEnd = cursor;
                    break;
                }
                cursor++;
            }

            if (contentEnd < 0) {
                break;
            }
            int delimiterEnd = contentEnd + delimiterLength;
            literals.add(new StringLiteral(index, contentStart, contentEnd, delimiterEnd,
                    lineAt(source, index), quote == '"'));
            index = delimiterEnd;
        }
        return literals;
    }

    private static int skipBalancedExpression(String text, int openingBrace) {
        int depth = 0;
        int index = openingBrace;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\'' || current == '"') {
                index = skipQuotedText(text, index, current);
                continue;
            }
            if (text.startsWith("//", index)) {
                int newline = text.indexOf('\n', index + 2);
                index = newline < 0 ? text.length() : newline + 1;
                continue;
            }
            if (text.startsWith("/*", index)) {
                int closing = text.indexOf("*/", index + 2);
                index = closing < 0 ? text.length() : closing + 2;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index + 1;
            }
            index++;
        }
        return -1;
    }

    private static int skipQuotedText(String text, int opening, char quote) {
        boolean triple = opening + 2 < text.length()
                && text.charAt(opening + 1) == quote && text.charAt(opening + 2) == quote;
        int delimiterLength = triple ? 3 : 1;
        int index = opening + delimiterLength;
        while (index < text.length()) {
            if (text.charAt(index) == '\\') {
                index = Math.min(text.length(), index + 2);
            } else if (triple ? text.startsWith(String.valueOf(quote).repeat(3), index)
                    : text.charAt(index) == quote) {
                return index + delimiterLength;
            } else {
                index++;
            }
        }
        return text.length();
    }

    private static int lineAt(String source, int position) {
        int line = 1;
        for (int index = 0; index < position; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private record StringLiteral(int delimiterStart, int contentStart, int contentEnd,
                                 int delimiterEnd, int line, boolean interpolated) {
    }

    private record TokenizedGroovy(String sql, Map<String, String> tokens) {
    }

    private record Interpolation(int start, int end, String expression) {
    }
}
