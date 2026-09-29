package br.com.jjw.jrxmlconverter.groovy;

import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.DeclarationExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.GStringExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.CodeVisitorSupport;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Types;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lê a estrutura do Groovy sem compilar nem executar o script.
 *
 * <p>A AST é apenas uma fonte adicional de contexto para o conversor SQL. Qualquer
 * falha de análise devolve um resultado indisponível e mantém o comportamento
 * conservador do processador.</p>
 */
final class GroovyAstAnalyzer {
    private static final Pattern CLAUSE_PREFIX = Pattern.compile("(?is)^(?:AND|OR|WHERE)\\b.+");
    private static final Pattern PREDICATE = Pattern.compile(
            "(?is).+(?:\\s(?:IN\\s*\\(|IS\\s+(?:NOT\\s+)?NULL\\b|LIKE\\b|BETWEEN\\b)|<>|!=|<=|>=|=|<|>).+");
    private static final Pattern FIREBIRD_PAGINATION = Pattern.compile(
            "(?is)^\\s*(?:(?:FIRST\\b.+?(?:\\s+SKIP\\b.+)?)|(?:SKIP\\b.+?(?:\\s+FIRST\\b.+)?))\\s*$");
    private static final Pattern POSTGRES_PAGINATION = Pattern.compile(
            "(?is)^\\s*(?:(?:LIMIT|OFFSET|FETCH)\\b.+)\\s*$");

    Analysis analyze(String source) {
        try {
            SourceUnit sourceUnit = SourceUnit.create("conversao.groovy", source);
            sourceUnit.parse();
            sourceUnit.completePhase();
            sourceUnit.nextPhase();
            sourceUnit.convert();

            ModuleNode module = sourceUnit.getAST();
            List<Definition> definitions = new ArrayList<>();
            int lastLine = Math.max(1, source.lines().mapToInt(ignored -> 1).sum());

            DefinitionVisitor scriptVisitor = new DefinitionVisitor(definitions, 1, lastLine);
            module.getStatementBlock().visit(scriptVisitor);
            for (MethodNode method : module.getMethods()) {
                if (method.getCode() == null) {
                    continue;
                }
                int start = Math.max(1, method.getLineNumber());
                int end = method.getLastLineNumber() > 0 ? method.getLastLineNumber() : lastLine;
                method.getCode().visit(new DefinitionVisitor(definitions, start, end));
            }
            definitions.sort(Comparator.comparingInt(Definition::line));
            return new Analysis(true, List.copyOf(definitions));
        } catch (RuntimeException | LinkageError exception) {
            return Analysis.unavailable();
        }
    }

    enum Role {
        EMPTY,
        OPTIONAL_CONDITION,
        FIREBIRD_PAGINATION,
        POSTGRES_PAGINATION,
        UNKNOWN
    }

    record Analysis(boolean available, List<Definition> definitions) {
        static Analysis unavailable() {
            return new Analysis(false, List.of());
        }

        boolean isOptionalCondition(String variable, int useLine) {
            List<Role> roles = roles(variable, useLine);
            return !roles.isEmpty() && roles.stream()
                    .allMatch(role -> role == Role.EMPTY || role == Role.OPTIONAL_CONDITION);
        }

        boolean isOptionalFirebirdPagination(String variable, int useLine) {
            List<Role> roles = roles(variable, useLine);
            return roles.contains(Role.FIREBIRD_PAGINATION) && roles.stream()
                    .allMatch(role -> role == Role.EMPTY || role == Role.FIREBIRD_PAGINATION);
        }

        boolean isOptionalPostgresPagination(String variable, int useLine) {
            List<Role> roles = roles(variable, useLine);
            return roles.contains(Role.POSTGRES_PAGINATION) && roles.stream()
                    .allMatch(role -> role == Role.EMPTY || role == Role.POSTGRES_PAGINATION);
        }

        private List<Role> roles(String variable, int useLine) {
            return definitions.stream()
                    .filter(definition -> definition.name().equals(variable))
                    .filter(definition -> definition.line() < useLine)
                    .filter(definition -> useLine >= definition.scopeStart()
                            && useLine <= definition.scopeEnd())
                    .map(Definition::role)
                    .distinct()
                    .toList();
        }
    }

    private record Definition(String name, int line, int scopeStart, int scopeEnd, Role role) {
    }

    private static final class DefinitionVisitor extends CodeVisitorSupport {
        private final List<Definition> definitions;
        private final int scopeStart;
        private final int scopeEnd;

        private DefinitionVisitor(List<Definition> definitions, int scopeStart, int scopeEnd) {
            this.definitions = definitions;
            this.scopeStart = scopeStart;
            this.scopeEnd = scopeEnd;
        }

        @Override
        public void visitDeclarationExpression(DeclarationExpression expression) {
            register(expression.getVariableExpression(), expression.getRightExpression(), expression.getLineNumber());
            super.visitDeclarationExpression(expression);
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            if (!(expression instanceof DeclarationExpression)
                    && expression.getOperation().getType() == Types.ASSIGN
                    && expression.getLeftExpression() instanceof VariableExpression variable) {
                register(variable, expression.getRightExpression(), expression.getLineNumber());
            }
            super.visitBinaryExpression(expression);
        }

        private void register(VariableExpression variable, Expression value, int line) {
            definitions.add(new Definition(variable.getName(), Math.max(1, line), scopeStart, scopeEnd,
                    classify(value)));
        }

        private static Role classify(Expression expression) {
            if (expression instanceof TernaryExpression ternary) {
                Role trueRole = classify(ternary.getTrueExpression());
                Role falseRole = classify(ternary.getFalseExpression());
                if (trueRole == falseRole) {
                    return trueRole;
                }
                if ((trueRole == Role.EMPTY && falseRole == Role.OPTIONAL_CONDITION)
                        || (falseRole == Role.EMPTY && trueRole == Role.OPTIONAL_CONDITION)) {
                    return Role.OPTIONAL_CONDITION;
                }
                if ((trueRole == Role.EMPTY && falseRole == Role.FIREBIRD_PAGINATION)
                        || (falseRole == Role.EMPTY && trueRole == Role.FIREBIRD_PAGINATION)) {
                    return Role.FIREBIRD_PAGINATION;
                }
                return Role.UNKNOWN;
            }
            if (expression instanceof BinaryExpression binary
                    && binary.getOperation().getType() == Types.PLUS) {
                String leftText = staticText(binary.getLeftExpression());
                String fixedPrefix = leftText == null ? "" : leftText.stripLeading();
                if (fixedPrefix.matches("(?is)^(?:AND|OR|WHERE)\\b.*")) {
                    return Role.OPTIONAL_CONDITION;
                }
            }
            String text = staticText(expression);
            if (text == null) {
                return Role.UNKNOWN;
            }
            String normalized = text.strip();
            if (normalized.isEmpty()) {
                return Role.EMPTY;
            }
            if (FIREBIRD_PAGINATION.matcher(normalized).matches()) {
                return Role.FIREBIRD_PAGINATION;
            }
            if (POSTGRES_PAGINATION.matcher(normalized).matches()) {
                return Role.POSTGRES_PAGINATION;
            }
            if (CLAUSE_PREFIX.matcher(normalized).matches()
                    || PREDICATE.matcher(normalized).matches()) {
                return Role.OPTIONAL_CONDITION;
            }
            return Role.UNKNOWN;
        }

        private static String staticText(Expression expression) {
            if (expression instanceof ConstantExpression constant && constant.getValue() instanceof String value) {
                return value;
            }
            if (expression instanceof GStringExpression gString) {
                StringBuilder text = new StringBuilder();
                List<ConstantExpression> strings = gString.getStrings();
                for (int index = 0; index < strings.size(); index++) {
                    text.append(strings.get(index).getText());
                    if (index < strings.size() - 1) {
                        text.append(" __valor_dinamico__ ");
                    }
                }
                return text.toString();
            }
            return null;
        }
    }
}
