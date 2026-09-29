package br.com.jjw.jrxmlconverter.sql;

import br.com.jjw.jrxmlconverter.domain.QueryResult;
import br.com.jjw.jrxmlconverter.metadata.SchemaMetadata;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.SQLDialect;
import org.jooq.conf.ParseUnknownFunctions;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static final Pattern STARTING_WITH = Pattern.compile(
            "(?is)([A-Z_][A-Z0-9_$.]*)\\s+(NOT\\s+)?STARTING\\s+WITH\\s+"
                    + "(CAST\\s*\\((?:[^()]|\\([^()]*\\))*\\)|__[A-Z]+_TOKEN_\\d+__|"
                    + "'(?:''|[^'])*'|[A-Z_][A-Z0-9_$.]*)");
    private static final Pattern CONTAINING = Pattern.compile(
            "(?is)([A-Z_][A-Z0-9_$.]*)\\s+(NOT\\s+)?CONTAINING\\s+"
                    + "(CAST\\s*\\((?:[^()]|\\([^()]*\\))*\\)|__[A-Z]+_TOKEN_\\d+__|"
                    + "'(?:''|[^'])*'|[A-Z_][A-Z0-9_$.]*)");
    private static final Pattern REMAINING_FIREBIRD_SYNTAX = Pattern.compile(
            "(?is)\\bSELECT\\s+(?:DISTINCT\\s+)?FIRST\\b|\\bSKIP\\b(?!\\s+LOCKED)"
                    + "|\\b(?:DATEADD|DATEDIFF|GEN_ID|ASCII_CHAR|IIF)\\s*\\("
                    + "|\\b(?:STARTING\\s+WITH|CONTAINING)\\b|\\bUPDATE\\s+OR\\s+INSERT\\b"
                    + "|\\bEXECUTE\\s+BLOCK\\b|RDB\\$DATABASE|\\bLIST\\s*\\("
                    + "|\\bWITH\\s+LOCK\\b");

    private final DSLContext postgres;
    private final FirebirdUpsertConverter upsertConverter = new FirebirdUpsertConverter();
    private final SetOperationPaginationConverter setPaginationConverter =
            new SetOperationPaginationConverter();
    private final SchemaMetadata schemaMetadata;

    public FirebirdToPostgresSqlConverter() {
        this(SchemaMetadata.empty());
    }

    public FirebirdToPostgresSqlConverter(SchemaMetadata schemaMetadata) {
        this.schemaMetadata = schemaMetadata == null ? SchemaMetadata.empty() : schemaMetadata;
        Settings settings = new Settings()
                .withParseDialect(SQLDialect.FIREBIRD)
                .withParseUnknownFunctions(ParseUnknownFunctions.IGNORE)
                .withRenderFormatted(true);
        postgres = DSL.using(SQLDialect.POSTGRES, settings);
    }

    public QueryResult convert(Path file, int queryIndex, String originalSql) {
        return convert(file, queryIndex, originalSql, true);
    }

    public QueryResult convert(Path file, int queryIndex, String originalSql,
                               boolean useSchemaMetadata) {
        if (originalSql.isBlank()) {
            return QueryResult.empty(file, queryIndex);
        }

        boolean terminated = TRAILING_SEMICOLON.matcher(originalSql).find();
        String sqlToConvert = terminated
                ? TRAILING_SEMICOLON.matcher(originalSql).replaceFirst("") : originalSql;
        Optional<SetOperationPaginationConverter.SetOperation> setOperation =
                setPaginationConverter.analyze(sqlToConvert);
        if (setOperation.isPresent()) {
            Optional<QueryResult> convertedSet = convertPaginatedSetOperation(
                    file, queryIndex, originalSql, setOperation.get(), terminated);
            if (convertedSet.isPresent()) {
                return convertedSet.get();
            }
        }
        ProtectedSql protectedSql = protect(sqlToConvert);
        try {
            if (protectedSql.ambiguousSetPagination()) {
                return QueryResult.failed(file, queryIndex, originalSql,
                        "FIRST/SKIP combinado com UNION, INTERSECT ou EXCEPT exige revisão de escopo.");
            }
            if (startsWithUpdateOrInsert(protectedSql.sql())) {
                Optional<String> upsert = upsertConverter.convert(
                        protectedSql.sql(), useSchemaMetadata ? schemaMetadata : SchemaMetadata.empty());
                if (upsert.isEmpty()) {
                    String reason = useSchemaMetadata
                            ? "UPDATE OR INSERT sem MATCHING explícito; a chave de conflito não pode ser inferida com segurança."
                            : "UPDATE OR INSERT sem MATCHING explícito em arquivo com conexão própria; "
                            + "a chave do banco principal não será aplicada automaticamente.";
                    return QueryResult.failed(file, queryIndex, originalSql,
                            reason);
                }
                String converted = restore(upsert.get(), protectedSql.tableFunctions());
                converted = restore(converted, protectedSql.jasperExpressions());
                converted = restore(converted, protectedSql.postgresExpressions());
                if (hasRemainingFirebirdSyntax(converted)) {
                    return QueryResult.failed(file, queryIndex, originalSql,
                            "A consulta convertida ainda contém sintaxe específica do Firebird.");
                }
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
            if (protectedSql.withLock()) {
                converted += "\nfor update";
            }
            converted = restore(converted, protectedSql.jasperExpressions());
            converted = restore(converted, protectedSql.postgresExpressions());
            converted = rewriteFirebirdListSafely(converted);
            if (hasRemainingFirebirdSyntax(converted)) {
                return QueryResult.failed(file, queryIndex, originalSql,
                        "A consulta convertida ainda contém sintaxe específica do Firebird.");
            }
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
        TokenizedSql textRegions = protectTextRegions(converted);
        converted = rewriteLegacyFunctions(rewriteFirebirdList(textRegions.sql()));
        converted = converted.replaceAll("(?i)\\s+FROM\\s+RDB\\$DATABASE\\b", "");
        boolean withLock = WITH_LOCK.matcher(converted).find();
        converted = WITH_LOCK.matcher(converted).replaceFirst("");
        converted = rewriteDateAdds(converted);
        converted = rewriteDateDiffs(converted);
        converted = rewriteStartingWith(converted);
        converted = rewriteContaining(converted);
        PaginationSql pagination = protectTopLevelPagination(converted);
        converted = rewriteNestedPaginationDirect(pagination.sql());
        converted = appendPagination(converted, pagination.first(), pagination.skip());
        if (withLock) {
            converted += " FOR UPDATE";
        }
        converted = restore(converted, textRegions.tokens());

        if (converted.equals(before) || hasRemainingFirebirdSyntax(converted)) {
            return QueryResult.failed(file, queryIndex, originalSql,
                    "O SQL dinâmico ainda contém estrutura que exige análise completa.");
        }
        if (terminated) {
            converted += ";";
        }
        return QueryResult.converted(file, queryIndex, originalSql, converted, 0);
    }

    public boolean containsKnownFirebirdSyntax(String sql) {
        return sql != null && hasRemainingFirebirdSyntax(sql);
    }

    private Optional<QueryResult> convertPaginatedSetOperation(
            Path file, int queryIndex, String originalSql,
            SetOperationPaginationConverter.SetOperation operation, boolean terminated) {
        List<String> convertedBranches = new ArrayList<>();
        int jasperTokens = 0;
        for (String branch : operation.branches()) {
            QueryResult convertedBranch = convert(file, queryIndex, branch);
            if (!convertedBranch.succeeded()) {
                return Optional.empty();
            }
            convertedBranches.add(convertedBranch.convertedSql());
            jasperTokens += convertedBranch.jasperTokens();
        }

        String converted = setPaginationConverter.combine(operation, convertedBranches);
        if (hasRemainingFirebirdSyntax(converted)) {
            return Optional.empty();
        }
        if (terminated) {
            converted += ";";
        }
        return Optional.of(QueryResult.converted(
                file, queryIndex, originalSql, converted, jasperTokens));
    }

    private static boolean startsWithUpdateOrInsert(String sql) {
        return sql.matches("(?is)^\\s*UPDATE\\s+OR\\s+INSERT\\b.*");
    }

    private ProtectedSql protect(String sql) {
        TokenizedSql textRegions = protectTextRegions(sql);
        String compatibleSql = rewriteLegacyFunctions(rewriteFirebirdList(textRegions.sql()));
        compatibleSql = compatibleSql.replaceAll("(?i)\\s+FROM\\s+RDB\\$DATABASE\\b", "");
        boolean withLock = WITH_LOCK.matcher(compatibleSql).find();
        compatibleSql = WITH_LOCK.matcher(compatibleSql).replaceFirst("");
        TokenizedSql postgresExpressions = protectLastDayOfMonth(compatibleSql);
        compatibleSql = rewriteDateAdds(postgresExpressions.sql());
        compatibleSql = rewriteDateDiffs(compatibleSql);
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
        String tokenizedSql = rewriteContaining(rewriteStartingWith(text.toString()));
        tokenizedSql = restore(tokenizedSql, textRegions.tokens());

        PaginationSql pagination = protectTopLevelPagination(tokenizedSql);
        boolean ambiguousSetPagination = (pagination.first() != null || pagination.skip() != null)
                && setPaginationConverter.hasTopLevelSetOperator(tokenizedSql);
        pagination = new PaginationSql(rewriteNestedPaginationDirect(pagination.sql()),
                pagination.first(), pagination.skip());
        TokenizedSql tableFunctions = protectTableFunctions(pagination.sql());
        return new ProtectedSql(tableFunctions.sql(), jasperExpressions,
                tableFunctions.tokens(), postgresExpressions.tokens(), pagination.first(), pagination.skip(),
                withLock, ambiguousSetPagination);
    }

    private static String rewriteStartingWith(String sql) {
        Matcher matcher = STARTING_WITH.matcher(sql);
        StringBuilder rewritten = new StringBuilder(sql.length());
        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(3);
            String comparison = "POSITION(CAST(" + right + " AS VARCHAR) IN CAST(" + left
                    + " AS VARCHAR)) " + (matcher.group(2) == null ? "= 1" : "<> 1");
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(comparison));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String rewriteContaining(String sql) {
        Matcher matcher = CONTAINING.matcher(sql);
        StringBuilder rewritten = new StringBuilder(sql.length());
        while (matcher.find()) {
            String left = matcher.group(1);
            String right = matcher.group(3);
            String comparison = "POSITION(LOWER(CAST(" + right + " AS VARCHAR)) IN LOWER(CAST("
                    + left + " AS VARCHAR))) " + (matcher.group(2) == null ? "> 0" : "= 0");
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(comparison));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
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

    private static String rewriteFirebirdListSafely(String sql) {
        TokenizedSql textRegions = protectTextRegions(sql);
        return restore(rewriteFirebirdList(textRegions.sql()), textRegions.tokens());
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

    private static TokenizedSql protectTextRegions(String sql) {
        StringBuilder protectedSql = new StringBuilder(sql.length());
        Map<String, String> tokens = new LinkedHashMap<>();
        int index = 0;
        int sequence = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\'' || current == '"') {
                char quote = current;
                int contentStart = index + 1;
                int cursor = contentStart;
                while (cursor < sql.length()) {
                    if (sql.charAt(cursor) == quote) {
                        if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) {
                            cursor += 2;
                            continue;
                        }
                        break;
                    }
                    cursor++;
                }
                if (cursor >= sql.length()) {
                    protectedSql.append(sql, index, sql.length());
                    break;
                }
                String token = "__sql_text_token_" + (++sequence) + "__";
                tokens.put(token, sql.substring(contentStart, cursor));
                protectedSql.append(quote).append(token).append(quote);
                index = cursor + 1;
                continue;
            }
            if (sql.startsWith("--", index)) {
                int end = sql.indexOf('\n', index + 2);
                if (end < 0) {
                    end = sql.length();
                }
                String token = "__sql_text_token_" + (++sequence) + "__";
                tokens.put(token, sql.substring(index + 2, end));
                protectedSql.append("--").append(token);
                if (end < sql.length()) {
                    protectedSql.append('\n');
                    end++;
                }
                index = end;
                continue;
            }
            if (sql.startsWith("/*", index)) {
                int closing = sql.indexOf("*/", index + 2);
                if (closing < 0) {
                    protectedSql.append(sql, index, sql.length());
                    break;
                }
                String token = "__sql_text_token_" + (++sequence) + "__";
                tokens.put(token, sql.substring(index + 2, closing));
                protectedSql.append("/*").append(token).append("*/");
                index = closing + 2;
                continue;
            }
            protectedSql.append(current);
            index++;
        }
        return new TokenizedSql(protectedSql.toString(), tokens);
    }

    private static boolean hasRemainingFirebirdSyntax(String sql) {
        String protectedSql = protectTextRegions(sql).sql();
        return REMAINING_FIREBIRD_SYNTAX.matcher(protectedSql).find();
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
                                String first, String skip, boolean withLock,
                                boolean ambiguousSetPagination) {
    }

    private record TokenizedSql(String sql, Map<String, String> tokens) {
    }

    private record PaginationSql(String sql, String first, String skip) {
    }

    private record NestedPagination(int opening, int contentStart, int first, int skip) {
    }
}
