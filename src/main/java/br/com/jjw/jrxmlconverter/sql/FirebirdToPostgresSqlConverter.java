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
import java.util.Optional;
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
    private static final Pattern GEN_ID_ONE = Pattern.compile(
            "(?i)\\bGEN_ID\\s*\\(\\s*([A-Z_][A-Z0-9_$]*)\\s*,\\s*1\\s*\\)");
    private static final Pattern GEN_ID_ZERO = Pattern.compile(
            "(?i)\\bGEN_ID\\s*\\(\\s*([A-Z_][A-Z0-9_$]*)\\s*,\\s*0\\s*\\)");
    private static final Pattern TRAILING_SEMICOLON = Pattern.compile("(?s);\\s*$");
    private static final String PAGINATION_VALUE = "(?:__[A-Z]+_TOKEN_\\d+__|\\d+)";
    private static final Pattern TOP_LEVEL_FIRST_SKIP = Pattern.compile(
            "(?is)^(\\s*SELECT\\s+)FIRST\\s+(" + PAGINATION_VALUE + ")\\s+SKIP\\s+("
                    + PAGINATION_VALUE + ")\\s+");
    private static final Pattern TOP_LEVEL_SKIP_FIRST = Pattern.compile(
            "(?is)^(\\s*SELECT\\s+)SKIP\\s+(" + PAGINATION_VALUE + ")\\s+FIRST\\s+("
                    + PAGINATION_VALUE + ")\\s+");
    private static final Pattern TOP_LEVEL_FIRST = Pattern.compile(
            "(?is)^(\\s*SELECT\\s+)FIRST\\s+(" + PAGINATION_VALUE + ")\\s+");
    private static final Pattern TOP_LEVEL_SKIP = Pattern.compile(
            "(?is)^(\\s*SELECT\\s+)SKIP\\s+(" + PAGINATION_VALUE + ")\\s+");
    private static final Pattern NESTED_FIRST_SKIP = Pattern.compile(
            "(?is)\\(\\s*SELECT\\s+FIRST\\s+(\\d+)\\s+SKIP\\s+(\\d+)\\s+");
    private static final Pattern NESTED_SKIP_FIRST = Pattern.compile(
            "(?is)\\(\\s*SELECT\\s+SKIP\\s+(\\d+)\\s+FIRST\\s+(\\d+)\\s+");
    private static final Pattern NESTED_FIRST = Pattern.compile(
            "(?is)\\(\\s*SELECT\\s+FIRST\\s+(\\d+)\\s+");
    private static final Pattern DATEADD_START = Pattern.compile("(?i)\\bDATEADD\\s*\\(");
    private static final Pattern DATEDIFF_START = Pattern.compile("(?i)\\bDATEDIFF\\s*\\(");
    private static final Pattern COMMA_TABLE_FUNCTION_START = Pattern.compile(
            "(?i),\\s*([A-Z_][A-Z0-9_$]*(?:\\s*\\.\\s*[A-Z_][A-Z0-9_$]*)?)\\s*\\(");
    private static final Pattern WITH_LOCK = Pattern.compile("(?is)\\s+WITH\\s+LOCK\\s*$");
    private static final Pattern REMAINING_FIREBIRD_SYNTAX = Pattern.compile(
            "(?is)\\b(?:FIRST|SKIP|DATEADD|DATEDIFF|GEN_ID|ASCII_CHAR|IIF|CONTAINING)\\b"
                    + "|\\bSTARTING\\s+WITH\\b|\\bUPDATE\\s+OR\\s+INSERT\\b"
                    + "|\\bEXECUTE\\s+BLOCK\\b|RDB\\$DATABASE|\\bLIST\\s*\\(");

    private final DSLContext postgres;
    private final FirebirdUpsertConverter upsertConverter = new FirebirdUpsertConverter();

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

        boolean terminated = TRAILING_SEMICOLON.matcher(originalSql).find();
        String sqlToConvert = terminated
                ? TRAILING_SEMICOLON.matcher(originalSql).replaceFirst("") : originalSql;
        ProtectedSql protectedSql = protect(sqlToConvert);
        try {
            if (startsWithUpdateOrInsert(protectedSql.sql())) {
                Optional<String> upsert = upsertConverter.convert(protectedSql.sql());
                if (upsert.isEmpty()) {
                    return QueryResult.failed(file, queryIndex, originalSql,
                            "UPDATE OR INSERT sem MATCHING explícito; a chave de conflito não pode ser inferida com segurança.");
                }
                String converted = restore(upsert.get(), protectedSql.tableFunctions());
                converted = restore(converted, protectedSql.jasperExpressions());
                converted = restore(converted, protectedSql.postgresExpressions());
                if (terminated) {
                    converted += ";";
                }
                return QueryResult.converted(file, queryIndex, originalSql, converted,
                        protectedSql.jasperExpressions().size());
            }
            Query parsed = postgres.parser().parseQuery(protectedSql.sql());
            String converted = postgres.renderInlined(parsed);
            converted = restore(converted, protectedSql.tableFunctions());
            converted = appendPagination(converted, protectedSql.first(), protectedSql.skip());
            converted = restore(converted, protectedSql.jasperExpressions());
            converted = restore(converted, protectedSql.postgresExpressions());
            converted = rewriteFirebirdList(converted);
            if (terminated) {
                converted += ";";
            }
            return QueryResult.converted(
                    file, queryIndex, originalSql, converted, protectedSql.jasperExpressions().size());
        } catch (Exception exception) {
            return QueryResult.failed(file, queryIndex, originalSql, conciseMessage(exception));
        }
    }

    public QueryResult convertLenientDynamic(Path file, int queryIndex, String originalSql) {
        if (originalSql.isBlank()) {
            return QueryResult.empty(file, queryIndex);
        }
        boolean terminated = TRAILING_SEMICOLON.matcher(originalSql).find();
        String converted = terminated
                ? TRAILING_SEMICOLON.matcher(originalSql).replaceFirst("") : originalSql;
        String before = converted;
        converted = rewriteLegacyFunctions(rewriteFirebirdList(converted));
        converted = converted.replaceAll("(?i)\\s+FROM\\s+RDB\\$DATABASE\\b", "");
        converted = WITH_LOCK.matcher(converted).replaceFirst(" FOR UPDATE");
        converted = rewriteDateAdds(converted);
        converted = rewriteDateDiffs(converted);
        converted = converted.replaceAll(
                "(?is)([A-Z_][A-Z0-9_$.]*)\\s+STARTING\\s+WITH\\s+"
                        + "(CAST\\s*\\((?:[^()]|\\([^()]*\\))*\\)|[A-Z_][A-Z0-9_$.]*)",
                "$1 LIKE ($2 || '%')");
        PaginationSql pagination = protectTopLevelPagination(converted);
        converted = rewriteNestedPaginationDirect(pagination.sql());
        converted = appendPagination(converted, pagination.first(), pagination.skip());

        if (converted.equals(before) || REMAINING_FIREBIRD_SYNTAX.matcher(converted).find()) {
            return QueryResult.failed(file, queryIndex, originalSql,
                    "O SQL dinâmico ainda contém estrutura que exige análise completa.");
        }
        if (terminated) {
            converted += ";";
        }
        return QueryResult.converted(file, queryIndex, originalSql, converted, 0);
    }

    private static boolean startsWithUpdateOrInsert(String sql) {
        return sql.matches("(?is)^\\s*UPDATE\\s+OR\\s+INSERT\\b.*");
    }

    private ProtectedSql protect(String sql) {
        String compatibleSql = rewriteLegacyFunctions(rewriteFirebirdList(sql));
        compatibleSql = compatibleSql.replaceAll("(?i)\\s+FROM\\s+RDB\\$DATABASE\\b", "");
        compatibleSql = WITH_LOCK.matcher(compatibleSql).replaceFirst(" FOR UPDATE");
        TokenizedSql postgresExpressions = protectLastDayOfMonth(compatibleSql);
        compatibleSql = rewriteDateAdds(postgresExpressions.sql());
        compatibleSql = rewriteDateDiffs(compatibleSql);
        compatibleSql = compatibleSql.replaceAll(
                "(?is)([A-Z_][A-Z0-9_$.]*)\\s+STARTING\\s+WITH\\s+"
                        + "(CAST\\s*\\((?:[^()]|\\([^()]*\\))*\\)|[A-Z_][A-Z0-9_$.]*)",
                "$1 LIKE ($2 || '%')");
        Matcher matcher = JASPER_EXPRESSION.matcher(compatibleSql);
        StringBuilder text = new StringBuilder(sql.length());
        Map<String, String> jasperExpressions = new LinkedHashMap<>();
        int sequence = 0;
        while (matcher.find()) {
            String token = "__jasper_token_" + (++sequence) + "__";
            jasperExpressions.put(token, matcher.group());
            matcher.appendReplacement(text, Matcher.quoteReplacement(" " + token + " "));
        }
        matcher.appendTail(text);

        PaginationSql pagination = protectTopLevelPagination(text.toString());
        pagination = new PaginationSql(rewriteNestedPagination(pagination.sql()),
                pagination.first(), pagination.skip());
        TokenizedSql tableFunctions = protectTableFunctions(pagination.sql());
        return new ProtectedSql(tableFunctions.sql(), jasperExpressions,
                tableFunctions.tokens(), postgresExpressions.tokens(), pagination.first(), pagination.skip());
    }

    private static PaginationSql protectTopLevelPagination(String sql) {
        Matcher firstSkip = TOP_LEVEL_FIRST_SKIP.matcher(sql);
        if (firstSkip.find()) {
            return new PaginationSql(firstSkip.replaceFirst(Matcher.quoteReplacement(firstSkip.group(1))),
                    firstSkip.group(2), firstSkip.group(3));
        }
        Matcher skipFirst = TOP_LEVEL_SKIP_FIRST.matcher(sql);
        if (skipFirst.find()) {
            return new PaginationSql(skipFirst.replaceFirst(Matcher.quoteReplacement(skipFirst.group(1))),
                    skipFirst.group(3), skipFirst.group(2));
        }
        Matcher first = TOP_LEVEL_FIRST.matcher(sql);
        if (first.find()) {
            return new PaginationSql(first.replaceFirst(Matcher.quoteReplacement(first.group(1))),
                    first.group(2), null);
        }
        Matcher skip = TOP_LEVEL_SKIP.matcher(sql);
        if (skip.find()) {
            return new PaginationSql(skip.replaceFirst(Matcher.quoteReplacement(skip.group(1))),
                    null, skip.group(2));
        }
        return new PaginationSql(sql, null, null);
    }

    private static String appendPagination(String sql, String first, String skip) {
        StringBuilder paginated = new StringBuilder(sql);
        if (skip != null) {
            paginated.append("\noffset ").append(skip).append(" rows");
        }
        if (first != null) {
            paginated.append("\nfetch next ").append(first).append(" rows only");
        }
        return paginated.toString();
    }

    private static String rewriteNestedPagination(String sql) {
        String rewritten = sql;
        boolean changed;
        do {
            changed = false;
            NestedPagination match = lastNestedPagination(rewritten);
            if (match != null) {
                int closing = findClosingParenthesis(rewritten, match.opening());
                if (closing > match.contentStart()) {
                    String body = rewritten.substring(match.contentStart(), closing).stripTrailing();
                    String rows;
                    if (match.skip() == 0) {
                        rows = " ROWS " + match.first();
                    } else {
                        rows = " ROWS " + (match.skip() + 1) + " TO "
                                + (match.skip() + match.first());
                    }
                    rewritten = rewritten.substring(0, match.opening()) + "(SELECT " + body + rows
                            + rewritten.substring(closing);
                    changed = true;
                }
            }
        } while (changed);
        return rewritten;
    }

    private static String rewriteNestedPaginationDirect(String sql) {
        String rewritten = sql;
        while (true) {
            NestedPagination match = lastNestedPagination(rewritten);
            if (match == null) {
                return rewritten;
            }
            int closing = findClosingParenthesis(rewritten, match.opening());
            if (closing <= match.contentStart()) {
                return rewritten;
            }
            String body = rewritten.substring(match.contentStart(), closing).stripTrailing();
            StringBuilder pagination = new StringBuilder();
            if (match.skip() > 0) {
                pagination.append(" OFFSET ").append(match.skip()).append(" ROWS");
            }
            pagination.append(" FETCH NEXT ").append(match.first()).append(" ROWS ONLY");
            rewritten = rewritten.substring(0, match.opening()) + "(SELECT " + body + pagination
                    + rewritten.substring(closing);
        }
    }

    private static NestedPagination lastNestedPagination(String sql) {
        NestedPagination last = lastNestedPagination(sql, NESTED_FIRST_SKIP, false, true);
        NestedPagination candidate = lastNestedPagination(sql, NESTED_SKIP_FIRST, true, true);
        if (candidate != null && (last == null || candidate.opening() > last.opening())) {
            last = candidate;
        }
        candidate = lastNestedPagination(sql, NESTED_FIRST, false, false);
        if (candidate != null && (last == null || candidate.opening() > last.opening())) {
            last = candidate;
        }
        return last;
    }

    private static NestedPagination lastNestedPagination(String sql, Pattern pattern,
                                                          boolean skipFirst, boolean hasSkip) {
        Matcher matcher = pattern.matcher(sql);
        NestedPagination last = null;
        while (matcher.find()) {
            int first = Integer.parseInt(matcher.group(skipFirst ? 2 : 1));
            int skip = hasSkip ? Integer.parseInt(matcher.group(skipFirst ? 1 : 2)) : 0;
            last = new NestedPagination(matcher.start(), matcher.end(), first, skip);
        }
        return last;
    }

    private static String rewriteDateAdds(String sql) {
        String rewritten = sql;
        while (true) {
            Matcher matcher = DATEADD_START.matcher(rewritten);
            int opening = -1;
            while (matcher.find()) {
                opening = matcher.end() - 1;
            }
            if (opening < 0) {
                return rewritten;
            }
            int closing = findClosingParenthesis(rewritten, opening);
            if (closing < 0) {
                return rewritten;
            }
            String body = rewritten.substring(opening + 1, closing);
            int to = findTopLevelKeyword(body, "TO");
            if (to < 0) {
                return rewritten;
            }
            String amountAndUnit = body.substring(0, to).trim();
            int unitStart = lastWhitespace(amountAndUnit);
            if (unitStart < 0) {
                return rewritten;
            }
            String amount = amountAndUnit.substring(0, unitStart).trim();
            String unit = amountAndUnit.substring(unitStart).trim().toLowerCase(Locale.ROOT);
            if (!isDatePart(unit) || amount.isEmpty()) {
                return rewritten;
            }
            String date = body.substring(to + 2).trim();
            String replacement = "(" + date + " + (" + amount + ") * INTERVAL '1 " + unit + "')";
            int functionStart = rewritten.substring(0, opening).toUpperCase(Locale.ROOT)
                    .lastIndexOf("DATEADD");
            rewritten = rewritten.substring(0, functionStart) + replacement
                    + rewritten.substring(closing + 1);
        }
    }

    private static String rewriteDateDiffs(String sql) {
        String rewritten = sql;
        while (true) {
            Matcher matcher = DATEDIFF_START.matcher(rewritten);
            int opening = -1;
            while (matcher.find()) {
                opening = matcher.end() - 1;
            }
            if (opening < 0) {
                return rewritten;
            }
            int closing = findClosingParenthesis(rewritten, opening);
            if (closing < 0) {
                return rewritten;
            }
            String body = rewritten.substring(opening + 1, closing);
            int from = findTopLevelKeyword(body, "FROM");
            int to = findTopLevelKeyword(body, "TO");
            if (from < 0 || to <= from) {
                return rewritten;
            }
            String unit = body.substring(0, from).trim().toLowerCase(Locale.ROOT);
            String start = body.substring(from + 4, to).trim();
            String end = body.substring(to + 2).trim();
            String replacement;
            if (unit.equals("day")) {
                replacement = "(CAST(" + end + " AS DATE) - CAST(" + start + " AS DATE))";
            } else if (switch (unit) {
                case "week", "hour", "minute", "second", "millisecond" -> true;
                default -> false;
            }) {
                long divisor = switch (unit) {
                    case "week" -> 604800;
                    case "hour" -> 3600;
                    case "minute" -> 60;
                    default -> 1;
                };
                String epoch = "EXTRACT(EPOCH FROM (CAST(" + end + " AS TIMESTAMP) - CAST("
                        + start + " AS TIMESTAMP)))";
                replacement = unit.equals("millisecond")
                        ? "TRUNC((" + epoch + ") * 1000)"
                        : "TRUNC((" + epoch + ") / " + divisor + ")";
            } else {
                return rewritten;
            }
            int functionStart = rewritten.substring(0, opening).toUpperCase(Locale.ROOT)
                    .lastIndexOf("DATEDIFF");
            rewritten = rewritten.substring(0, functionStart) + replacement
                    + rewritten.substring(closing + 1);
        }
    }

    private static int findTopLevelKeyword(String text, String keyword) {
        int depth = 0;
        boolean inString = false;
        for (int index = 0; index <= text.length() - keyword.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && inString && index + 1 < text.length() && text.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
            } else if (!inString && depth == 0 && text.regionMatches(true, index, keyword, 0, keyword.length())
                    && (index == 0 || Character.isWhitespace(text.charAt(index - 1)))
                    && (index + keyword.length() == text.length()
                    || Character.isWhitespace(text.charAt(index + keyword.length())))) {
                return index;
            }
        }
        return -1;
    }

    private static int lastWhitespace(String text) {
        for (int index = text.length() - 1; index >= 0; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isDatePart(String unit) {
        return switch (unit) {
            case "year", "month", "week", "day", "hour", "minute", "second",
                    "millisecond" -> true;
            default -> false;
        };
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
        return protectCommaTableFunctions(text.toString(), tokens, sequence);
    }

    private TokenizedSql protectCommaTableFunctions(String sql, Map<String, String> existingTokens,
                                                     int initialSequence) {
        Matcher matcher = COMMA_TABLE_FUNCTION_START.matcher(sql);
        StringBuilder text = new StringBuilder(sql.length());
        Map<String, String> tokens = new LinkedHashMap<>(existingTokens);
        int sequence = initialSequence;
        int searchFrom = 0;
        int copiedUntil = 0;
        while (matcher.find(searchFrom)) {
            if (!isInsideFromClause(sql, matcher.start())) {
                searchFrom = matcher.end();
                continue;
            }
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

    private static boolean isInsideFromClause(String sql, int position) {
        int targetDepth = depthAt(sql, position);
        int depth = 0;
        boolean inString = false;
        boolean inFrom = false;
        int index = 0;
        while (index < position) {
            char current = sql.charAt(index);
            if (current == '\'' && inString && index + 1 < position && sql.charAt(index + 1) == '\'') {
                index += 2;
                continue;
            }
            if (current == '\'') {
                inString = !inString;
                index++;
                continue;
            }
            if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
            } else if (!inString && depth == targetDepth && Character.isLetter(current)) {
                int end = index + 1;
                while (end < position && (Character.isLetterOrDigit(sql.charAt(end))
                        || sql.charAt(end) == '_')) {
                    end++;
                }
                String word = sql.substring(index, end).toUpperCase(Locale.ROOT);
                if (word.equals("FROM")) {
                    inFrom = true;
                } else if (switch (word) {
                    case "SELECT", "WHERE", "GROUP", "HAVING", "ORDER", "UNION", "RETURNING" -> true;
                    default -> false;
                }) {
                    inFrom = false;
                }
                index = end;
                continue;
            }
            index++;
        }
        return inFrom;
    }

    private static int depthAt(String sql, int position) {
        int depth = 0;
        boolean inString = false;
        for (int index = 0; index < position; index++) {
            char current = sql.charAt(index);
            if (current == '\'' && inString && index + 1 < position && sql.charAt(index + 1) == '\'') {
                index++;
            } else if (current == '\'') {
                inString = !inString;
            } else if (!inString && current == '(') {
                depth++;
            } else if (!inString && current == ')') {
                depth--;
            }
        }
        return depth;
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
                "(?is)(\\bIN\\s*\\(\\s*SELECT\\s+)LIST\\s*\\(\\s*(?:DISTINCT\\s+)?([^()]+?)\\s*\\)", "$1$2");
        rewritten = rewritten.replaceAll(
                "(?is)\\bLIST\\s*\\(\\s*DISTINCT\\s+([^(),]+?)\\s*,\\s*([^()]+?)\\s*\\)",
                "string_agg(DISTINCT cast($1 as varchar), $2)");
        rewritten = rewritten.replaceAll(
                "(?is)\\bLIST\\s*\\(\\s*([^(),]+?)\\s*,\\s*([^()]+?)\\s*\\)",
                "string_agg(cast($1 as varchar), $2)");
        rewritten = rewritten.replaceAll(
                "(?is)\\bLIST\\s*\\(\\s*DISTINCT\\s+([^()]+?)\\s*\\)",
                "string_agg(DISTINCT cast($1 as varchar), ',')");
        return rewritten.replaceAll(
                "(?is)\\bLIST\\s*\\(\\s*([^()]+?)\\s*\\)",
                "string_agg(cast($1 as varchar), ',')");
    }

    private static String rewriteLegacyFunctions(String sql) {
        Matcher one = GEN_ID_ONE.matcher(sql);
        String rewritten = one.replaceAll(match -> "nextval('" + match.group(1) + "')");
        Matcher zero = GEN_ID_ZERO.matcher(rewritten);
        rewritten = zero.replaceAll(match -> "currval('" + match.group(1) + "')");
        return rewritten.replaceAll("(?i)\\bASCII_CHAR\\s*\\(", "CHR(");
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
                                Map<String, String> postgresExpressions,
                                String first, String skip) {
    }

    private record TokenizedSql(String sql, Map<String, String> tokens) {
    }

    private record PaginationSql(String sql, String first, String skip) {
    }

    private record NestedPagination(int opening, int contentStart, int first, int skip) {
    }
}
