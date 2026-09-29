package br.com.jjw.jrxmlconverter.groovy;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Move paginação Firebird produzida por variável Groovy para o fim do SELECT. */
final class GroovyDynamicPaginationRewriter {
    private static final Pattern EMPTY_DECLARATION = Pattern.compile(
            "(?m)^([ \\t]*)(?:def\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(['\"])\\s*\\3\\s*;?[ \\t]*$");
    private static final String VALUE = "(\\$\\{[^{}]+}|[^\\s;]+)";
    private static final Pattern FIRST_SKIP = Pattern.compile(
            "(?is)^\\s*FIRST\\s+" + VALUE + "\\s+SKIP\\s+" + VALUE + "\\s*$");
    private static final Pattern SKIP_FIRST = Pattern.compile(
            "(?is)^\\s*SKIP\\s+" + VALUE + "\\s+FIRST\\s+" + VALUE + "\\s*$");
    private static final Pattern FIRST = Pattern.compile("(?is)^\\s*FIRST\\s+" + VALUE + "\\s*$");
    private static final Pattern SKIP = Pattern.compile("(?is)^\\s*SKIP\\s+" + VALUE + "\\s*$");

    String rewrite(String source) {
        String rewritten = source;
        Set<String> handled = new HashSet<>();
        while (true) {
            Candidate candidate = findCandidate(rewritten, handled);
            if (candidate == null) {
                return rewritten;
            }
            handled.add(candidate.variable());
            rewritten = apply(rewritten, candidate);
        }
    }

    private static Candidate findCandidate(String source, Set<String> handled) {
        Matcher declaration = EMPTY_DECLARATION.matcher(source);
        while (declaration.find()) {
            String variable = declaration.group(2);
            if (handled.contains(variable)) {
                continue;
            }
            Candidate candidate = candidateFor(source, variable, declaration.end());
            if (candidate != null) {
                return candidate;
            }
            handled.add(variable);
        }
        return null;
    }

    private static Candidate candidateFor(String source, String variable, int afterDeclaration) {
        Pattern assignmentPattern = Pattern.compile(
                "(?m)^[ \\t]*" + Pattern.quote(variable)
                        + "\\s*=\\s*([\"'])([^\"'\\r\\n]*)\\1\\s*;?[ \\t]*$");
        Matcher assignment = assignmentPattern.matcher(source);
        assignment.region(afterDeclaration, source.length());
        if (!assignment.find()) {
            return null;
        }
        String between = source.substring(afterDeclaration, assignment.start());
        if (!between.matches("(?s).*\\bif\\s*\\(.*")) {
            return null;
        }

        String postgresPagination = toPostgresPagination(assignment.group(2));
        if (postgresPagination == null) {
            return null;
        }

        String interpolation = "${" + variable + "}";
        int use = source.indexOf(interpolation, assignment.end());
        if (use < 0 || source.indexOf(interpolation, use + interpolation.length()) >= 0) {
            return null;
        }
        LiteralBounds literal = enclosingTripleQuotedLiteral(source, use);
        if (literal == null) {
            return null;
        }
        String sql = source.substring(literal.contentStart(), literal.contentEnd());
        int relativeUse = use - literal.contentStart();
        if (!sql.substring(0, relativeUse).matches("(?is)^\\s*SELECT\\s*$")
                || !sql.substring(relativeUse + interpolation.length()).matches("(?is).*\\bFROM\\b.*")) {
            return null;
        }

        String indentation = lineIndentation(source, use);
        return new Candidate(variable, assignment.start(2), assignment.end(2), postgresPagination,
                use, use + interpolation.length(), literal.contentEnd(), indentation);
    }

    private static String apply(String source, Candidate candidate) {
        String withoutSelectModifier = source.substring(0, candidate.useStart())
                + source.substring(candidate.useEnd());
        int adjustedLiteralEnd = candidate.literalEnd() - (candidate.useEnd() - candidate.useStart());
        int insertAt = adjustedLiteralEnd;
        while (insertAt > 0 && Character.isWhitespace(withoutSelectModifier.charAt(insertAt - 1))) {
            insertAt--;
        }
        String paginationUse = "\n" + candidate.indentation() + "${" + candidate.variable() + "}";
        String moved = withoutSelectModifier.substring(0, insertAt) + paginationUse
                + withoutSelectModifier.substring(insertAt);

        int removed = candidate.useEnd() - candidate.useStart();
        int valueStart = candidate.valueStart();
        int valueEnd = candidate.valueEnd();
        if (candidate.useStart() < valueStart) {
            valueStart -= removed;
            valueEnd -= removed;
        }
        return moved.substring(0, valueStart) + candidate.postgresPagination()
                + moved.substring(valueEnd);
    }

    private static String toPostgresPagination(String firebird) {
        Matcher matcher = FIRST_SKIP.matcher(firebird);
        if (matcher.matches()) {
            return "offset " + matcher.group(2) + " rows fetch next " + matcher.group(1) + " rows only";
        }
        matcher = SKIP_FIRST.matcher(firebird);
        if (matcher.matches()) {
            return "offset " + matcher.group(1) + " rows fetch next " + matcher.group(2) + " rows only";
        }
        matcher = FIRST.matcher(firebird);
        if (matcher.matches()) {
            return "fetch next " + matcher.group(1) + " rows only";
        }
        matcher = SKIP.matcher(firebird);
        return matcher.matches() ? "offset " + matcher.group(1) + " rows" : null;
    }

    private static LiteralBounds enclosingTripleQuotedLiteral(String source, int position) {
        int doubleStart = source.lastIndexOf("\"\"\"", position);
        int singleStart = source.lastIndexOf("'''", position);
        int start = Math.max(doubleStart, singleStart);
        if (start < 0) {
            return null;
        }
        String delimiter = start == doubleStart ? "\"\"\"" : "'''";
        int end = source.indexOf(delimiter, start + 3);
        return end > position ? new LiteralBounds(start + 3, end) : null;
    }

    private static String lineIndentation(String source, int position) {
        int lineStart = source.lastIndexOf('\n', position) + 1;
        int cursor = lineStart;
        while (cursor < position && (source.charAt(cursor) == ' ' || source.charAt(cursor) == '\t')) {
            cursor++;
        }
        return source.substring(lineStart, cursor);
    }

    private record LiteralBounds(int contentStart, int contentEnd) {
    }

    private record Candidate(String variable, int valueStart, int valueEnd, String postgresPagination,
                             int useStart, int useEnd, int literalEnd, String indentation) {
    }
}
