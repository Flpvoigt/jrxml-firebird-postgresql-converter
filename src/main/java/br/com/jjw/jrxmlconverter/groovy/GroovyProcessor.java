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

    private final FirebirdToPostgresSqlConverter sqlConverter;

    public GroovyProcessor(FirebirdToPostgresSqlConverter sqlConverter) {
        this.sqlConverter = sqlConverter;
    }

    public GroovyConversion convert(Path relativeFile, String source) {
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
            if (fragment || isConcatenated(source, literal)) {
                String reason = fragment
                        ? "Fragmento de SQL dinâmico; conversão automática por partes seria insegura."
                        : "SQL montado por concatenação; é necessário analisar a expressão completa.";
                result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql, reason);
            } else {
                TokenizedGroovy tokenized = protectInterpolations(sql, literal.interpolated());
                QueryResult query = sqlConverter.convert(relativeFile, sqlIndex, tokenized.sql());
                if (query.succeeded()) {
                    replacement = restoreInterpolations(query.convertedSql(), tokenized.tokens());
                    replacement = preserveOuterWhitespace(sql, replacement);
                    result = GroovySqlResult.converted(relativeFile, sqlIndex, literal.line(), sql,
                            replacement, tokenized.tokens().size());
                } else if (query.message().startsWith("UPDATE OR INSERT sem MATCHING")) {
                    result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                            query.message());
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
                                "SQL dinâmico não pôde ser analisado com segurança: " + query.message());
                    }
                } else if (isDynamicallyExtended(source, literal)) {
                    result = GroovySqlResult.review(relativeFile, sqlIndex, literal.line(), sql,
                            "SQL montado em várias etapas; é necessário analisar a expressão completa.");
                } else {
                    result = GroovySqlResult.failed(relativeFile, sqlIndex, literal.line(), sql,
                            tokenized.tokens().size(), query.message());
                }
            }

            results.add(result);
            if (result.succeeded()) {
                convertedSource.append(source, copiedUntil, literal.contentStart()).append(replacement);
                copiedUntil = literal.contentEnd();
            }
        }

        convertedSource.append(source, copiedUntil, source.length());
        return new GroovyConversion(convertedSource.toString(), results);
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
}
