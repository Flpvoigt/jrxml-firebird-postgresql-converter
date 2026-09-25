package br.com.jjw.jrxmlconverter.sql;

import br.com.jjw.jrxmlconverter.domain.QueryResult;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.conf.ParseUnknownFunctions;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FirebirdToPostgresSqlConverter {
    private static final Pattern JASPER_EXPRESSION = Pattern.compile("\\$(P!?|X)\\{([^{}]*)}");
    private static final Pattern TABLE_FUNCTION_START = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN)\\s+([A-Z_][A-Z0-9_$]*(?:\\s*\\.\\s*[A-Z_][A-Z0-9_$]*)?)\\s*\\(");
    private static final Pattern LAST_DAY_OF_MONTH = Pattern.compile(
            "(?i)DATEADD\\s*\\(\\s*-\\s*EXTRACT\\s*\\(\\s*DAY\\s+FROM\\s+"
                    + "DATEADD\\s*\\(\\s*1\\s+MONTH\\s+TO\\s+([A-Z_][A-Z0-9_$.]*)\\s*\\)\\s*\\)\\s+"
                    + "DAY\\s+TO\\s+DATEADD\\s*\\(\\s*1\\s+MONTH\\s+TO\\s+\\1\\s*\\)\\s*\\)");
    private static final Pattern NESTED_SELECT = Pattern.compile("(?i)\\(\\s*SELECT\\b");

    private final DSLContext postgres;

    public FirebirdToPostgresSqlConverter() {
        Settings settings = new Settings()
                .withParseDialect(SQLDialect.FIREBIRD)
                .withParseUnknownFunctions(ParseUnknownFunctions.IGNORE)
                .withRenderFormatted(true);
        postgres = DSL.using(SQLDialect.POSTGRES, settings);
    }

    public QueryResult convert(Path file, int queryIndex, String originalSql) {
        if (originalSql.isBlank()) {
            return QueryResult.empty(file, queryIndex);
        }

        ProtectedSql protectedSql = protect(originalSql);
        try {
            Query parsed = postgres.parser().parseQuery(protectedSql.sql());
            String converted = postgres.render(parsed);
            converted = restore(converted, protectedSql.tableFunctions());
            converted = restore(converted, protectedSql.jasperExpressions());
            converted = restore(converted, protectedSql.postgresExpressions());
            converted = rewriteFirebirdList(converted);
            return QueryResult.converted(
                    file, queryIndex, originalSql, converted, protectedSql.jasperExpressions().size());
        } catch (Exception exception) {
            return QueryResult.failed(file, queryIndex, originalSql, conciseMessage(exception));
        }
    }

    private ProtectedSql protect(String sql) {
        TokenizedSql postgresExpressions = protectLastDayOfMonth(sql);
        Matcher matcher = JASPER_EXPRESSION.matcher(postgresExpressions.sql());
        StringBuilder text = new StringBuilder(sql.length());
        Map<String, String> jasperExpressions = new LinkedHashMap<>();
        int sequence = 0;
        while (matcher.find()) {
            String token = "__jasper_token_" + (++sequence) + "__";
            jasperExpressions.put(token, matcher.group());
            matcher.appendReplacement(text, Matcher.quoteReplacement(" " + token + " "));
        }
        matcher.appendTail(text);

        TokenizedSql tableFunctions = protectTableFunctions(text.toString());
        return new ProtectedSql(tableFunctions.sql(), jasperExpressions,
                tableFunctions.tokens(), postgresExpressions.tokens());
    }

    private static TokenizedSql protectLastDayOfMonth(String sql) {
        Matcher matcher = LAST_DAY_OF_MONTH.matcher(sql);
        StringBuilder text = new StringBuilder(sql.length());
        Map<String, String> tokens = new LinkedHashMap<>();
        int sequence = 0;
        while (matcher.find()) {
            String date = matcher.group(1);
            String token = "__postgres_expression_" + (++sequence) + "__";
            tokens.put(token, "(date_trunc('month', " + date
                    + ") + interval '1 month' - interval '1 day')::date");
            matcher.appendReplacement(text, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(text);
        return new TokenizedSql(text.toString(), tokens);
    }

    private TokenizedSql protectTableFunctions(String sql) {
        Matcher matcher = TABLE_FUNCTION_START.matcher(sql);
        StringBuilder text = new StringBuilder(sql.length());
        Map<String, String> tokens = new LinkedHashMap<>();
        int sequence = 0;
        int searchFrom = 0;
        int copiedUntil = 0;
        while (matcher.find(searchFrom)) {
            String functionName = matcher.group(1).replaceAll("\\s+", "");
            if (isSqlSyntaxFunction(functionName)) {
                searchFrom = matcher.end();
                continue;
            }
            int functionStart = matcher.start(1);
            int opening = matcher.end() - 1;
            int closing = findClosingParenthesis(sql, opening);
            if (closing < 0) {
                break;
            }
            String token = "__table_function_" + (++sequence) + "__";
            tokens.put(token, translateNestedSelects(sql.substring(functionStart, closing + 1)));
            text.append(sql, copiedUntil, functionStart).append(token);
            copiedUntil = closing + 1;
            searchFrom = closing + 1;
        }
        text.append(sql, copiedUntil, sql.length());
        return new TokenizedSql(text.toString(), tokens);
    }

    private String translateNestedSelects(String fragment) {
        Matcher matcher = NESTED_SELECT.matcher(fragment);
        StringBuilder translated = new StringBuilder(fragment.length());
        int cursor = 0;
        while (matcher.find(cursor)) {
            int opening = matcher.start();
            int closing = findClosingParenthesis(fragment, opening);
            if (closing < 0) {
                break;
            }
            String nestedSql = fragment.substring(opening + 1, closing);
            try {
                translated.append(fragment, cursor, opening + 1)
                        .append(postgres.render(postgres.parser().parseQuery(nestedSql)))
                        .append(')');
            } catch (Exception ignored) {
                translated.append(fragment, cursor, closing + 1);
            }
            cursor = closing + 1;
        }
        translated.append(fragment, cursor, fragment.length());
        return translated.toString();
    }

    private static String rewriteFirebirdList(String sql) {
        String rewritten = sql.replaceAll(
                "(?is)(\\bIN\\s*\\(\\s*SELECT\\s+)LIST\\s*\\(\\s*([^()]+?)\\s*\\)", "$1$2");
        return rewritten.replaceAll(
                "(?is)\\bLIST\\s*\\(\\s*([^()]+?)\\s*\\)",
                "string_agg(cast($1 as varchar), ',')");
    }

    private static boolean isSqlSyntaxFunction(String name) {
        String simple = name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        return switch (simple) {
            case "DATEADD", "EXTRACT", "CAST", "TRIM", "SUBSTRING" -> true;
            default -> false;
        };
    }

    static int findClosingParenthesis(String sql, int openingIndex) {
        int depth = 0;
        boolean inString = false;
        for (int index = openingIndex; index < sql.length(); index++) {
            char current = sql.charAt(index);
            if (current == '\'' && inString && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String restore(String sql, Map<String, String> tokens) {
        String restored = sql;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            restored = restored.replaceAll(
                    "(?i)" + Pattern.quote(token.getKey()), Matcher.quoteReplacement(token.getValue()));
        }
        return restored;
    }

    private static String conciseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName()
                + (current.getMessage() == null ? "" : ": " + current.getMessage());
    }

    private record ProtectedSql(String sql, Map<String, String> jasperExpressions,
                                Map<String, String> tableFunctions,
                                Map<String, String> postgresExpressions) {
    }

    private record TokenizedSql(String sql, Map<String, String> tokens) {
    }
}
