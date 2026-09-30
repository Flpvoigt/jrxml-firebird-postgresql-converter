package br.com.jjw.jrxmlconverter.report;

import br.com.jjw.jrxmlconverter.domain.ConversionRun;
import br.com.jjw.jrxmlconverter.domain.ConversionSummary;
import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import br.com.jjw.jrxmlconverter.domain.GroovySqlResult;
import br.com.jjw.jrxmlconverter.domain.QueryResult;
import br.com.jjw.jrxmlconverter.domain.SubreportReferenceResult;
import br.com.jjw.jrxmlconverter.domain.SubreportResolutionStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConversionReportWriter {
    private static final String REPORT_SEPARATOR =
            "================================================================================";
    private static final Pattern ADJACENT_QUALIFIED_COLUMNS = Pattern.compile(
            "(?i)\\b([A-Za-z_][A-Za-z0-9_$]*\\.[A-Za-z_][A-Za-z0-9_$]*)\\s+"
                    + "([A-Za-z_][A-Za-z0-9_$]*\\.[A-Za-z_][A-Za-z0-9_$]*)\\b");
    private static final Pattern PARSER_LOCATION = Pattern.compile(
            "(?i)ParserException:\\s*(.*?)\\s*\\[(\\d+):(\\d+)]");
    private static final Pattern GROOVY_INTERNAL_TOKEN = Pattern.compile("__groovy_token_\\d+__");

    public void write(Path output, ConversionRun run) throws IOException {
        Path reportOutput = output.resolve("_conversion-reports");
        Files.createDirectories(reportOutput);
        if (run.groupedByProject()) {
            writeGroupedReports(reportOutput.resolve("projects"), run);
            writeSubreportReview(reportOutput.resolve("subreport-review-required.txt"),
                    run.subreportReferences());
            writeSummary(reportOutput.resolve("conversion-summary.txt"), run.summary());
            return;
        }
        Path jrxmlOutput = reportOutput.resolve("jrxml");
        Path groovyOutput = reportOutput.resolve("groovy");
        Files.createDirectories(jrxmlOutput);
        Files.createDirectories(groovyOutput);
        writeJrxmlCsv(jrxmlOutput.resolve("conversion-report.csv"), run.queryResults());
        writeJrxmlReview(jrxmlOutput.resolve("review-required.txt"), run.queryResults());
        writeGroovyCsv(groovyOutput.resolve("conversion-report.csv"), run.groovyResults());
        writeGroovyReview(groovyOutput.resolve("review-required.txt"), run.groovyResults());
        writeSubreportReview(reportOutput.resolve("subreport-review-required.txt"),
                run.subreportReferences());
        writeSummary(reportOutput.resolve("conversion-summary.txt"), run.summary());
    }

    private static void writeGroupedReports(Path output, ConversionRun run) throws IOException {
        Map<String, List<QueryResult>> jrxmlByProject = new LinkedHashMap<>();
        Map<String, List<GroovySqlResult>> groovyByProject = new LinkedHashMap<>();
        Set<String> projects = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (QueryResult result : run.queryResults()) {
            String project = projectName(result.file());
            projects.add(project);
            jrxmlByProject.computeIfAbsent(project, ignored -> new ArrayList<>()).add(result);
        }
        for (GroovySqlResult result : run.groovyResults()) {
            String project = projectName(result.file());
            projects.add(project);
            groovyByProject.computeIfAbsent(project, ignored -> new ArrayList<>()).add(result);
        }
        for (String project : projects) {
            Path jrxmlOutput = output.resolve(project).resolve("jrxml");
            Path groovyOutput = output.resolve(project).resolve("groovy");
            Files.createDirectories(jrxmlOutput);
            Files.createDirectories(groovyOutput);
            writeJrxmlCsv(jrxmlOutput.resolve("conversion-report.csv"),
                    jrxmlByProject.getOrDefault(project, List.of()));
            writeJrxmlReview(jrxmlOutput.resolve("review-required.txt"),
                    jrxmlByProject.getOrDefault(project, List.of()));
            writeGroovyCsv(groovyOutput.resolve("conversion-report.csv"),
                    groovyByProject.getOrDefault(project, List.of()));
            writeGroovyReview(groovyOutput.resolve("review-required.txt"),
                    groovyByProject.getOrDefault(project, List.of()));
        }
    }

    private static String projectName(Path file) {
        return file.getNameCount() == 0 ? "_raiz" : file.getName(0).toString();
    }

    private static void writeJrxmlCsv(Path destination, List<QueryResult> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("file;query_index;status;jasper_tokens;message");
        for (QueryResult result : results) {
            if (!requiresAttention(result.status())) {
                continue;
            }
            lines.add(csv(result.file().toString()) + ";" + result.queryIndex() + ";"
                    + result.status() + ";" + result.jasperTokens() + ";" + csv(result.message()));
        }
        Files.write(destination, lines, StandardCharsets.UTF_8);
    }

    private static void writeGroovyCsv(Path destination, List<GroovySqlResult> results) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("file;sql_index;line;status;dynamic_expressions;message");
        for (GroovySqlResult result : results) {
            if (!requiresAttention(result.status())) {
                continue;
            }
            lines.add(csv(result.file().toString()) + ";" + result.sqlIndex() + ";" + result.line()
                    + ";" + result.status() + ";" + result.dynamicExpressions() + ";"
                    + csv(result.message()));
        }
        Files.write(destination, lines, StandardCharsets.UTF_8);
    }

    private static void writeJrxmlReview(Path destination, List<QueryResult> results) throws IOException {
        List<QueryResult> pending = results.stream()
                .filter(result -> requiresAttention(result.status()))
                .toList();

        StringBuilder text = reviewHeader(
                "PENDÊNCIAS DE CONVERSÃO — JRXML",
                pending.size());
        if (pending.isEmpty()) {
            text.append("RESULTADO\n")
                    .append("  Nenhuma pendência encontrada.\n");
        } else {
            for (int index = 0; index < pending.size(); index++) {
                QueryResult result = pending.get(index);
                ReviewGuidance guidance = jrxmlReviewGuidance(result);
                text.append(REPORT_SEPARATOR).append('\n')
                        .append("PENDÊNCIA ").append(index + 1).append('/').append(pending.size()).append("\n\n")
                        .append("Arquivo: ").append(result.file()).append('\n')
                        .append("Local: QueryString ").append(result.queryIndex()).append('\n')
                        .append("Status: ").append(result.status()).append('\n')
                        .append("Problema: ").append(guidance.type()).append(". ").append(guidance.found()).append('\n')
                        .append("Ação: ").append(guidance.action()).append('\n')
                        .append("Referência: ").append(technicalDetail(result.message())).append('\n')
                        .append("SQL original: ").append(sqlPreview(result.originalSql())).append("\n\n");
            }
        }
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static void writeGroovyReview(Path destination, List<GroovySqlResult> results) throws IOException {
        List<GroovySqlResult> pending = results.stream()
                .filter(result -> requiresAttention(result.status()))
                .toList();

        StringBuilder text = reviewHeader(
                "PENDÊNCIAS DE CONVERSÃO — GROOVY",
                pending.size());
        if (pending.isEmpty()) {
            text.append("RESULTADO\n")
                    .append("  Nenhuma pendência encontrada.\n");
        } else {
            for (int index = 0; index < pending.size(); index++) {
                GroovySqlResult result = pending.get(index);
                ReviewGuidance guidance = reviewGuidance(result);
                text.append(REPORT_SEPARATOR).append('\n')
                        .append("PENDÊNCIA ").append(index + 1).append('/').append(pending.size()).append("\n\n")
                        .append("Arquivo: ").append(result.file()).append('\n')
                        .append("Local: SQL ").append(result.sqlIndex()).append(", linha ").append(result.line()).append('\n')
                        .append("Status: ").append(result.status()).append('\n')
                        .append("Problema: ").append(guidance.type()).append(". ").append(guidance.found()).append('\n')
                        .append("Ação: ").append(guidance.action()).append('\n')
                        .append("SQL original: ").append(sqlPreview(result.originalSql())).append("\n\n");
            }
        }
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static void writeSubreportReview(Path destination,
                                             List<SubreportReferenceResult> results) throws IOException {
        List<SubreportReferenceResult> pending = results.stream()
                .filter(result -> result.status().requiresAttention())
                .toList();
        StringBuilder text = reviewHeader("PENDÊNCIAS DE SUBREPORTS", pending.size());
        if (pending.isEmpty()) {
            text.append("RESULTADO\n")
                    .append("  Todas as referências foram resolvidas sem ambiguidade.\n");
        } else {
            for (int index = 0; index < pending.size(); index++) {
                SubreportReferenceResult result = pending.get(index);
                text.append(REPORT_SEPARATOR).append('\n')
                        .append("PENDÊNCIA ").append(index + 1).append('/')
                        .append(pending.size()).append("\n\n")
                        .append("Arquivo: ").append(result.file()).append('\n')
                        .append("Local: SubreportExpression ").append(result.referenceIndex()).append('\n')
                        .append("Expressão: ").append(result.expression()).append('\n')
                        .append("Status: ").append(result.status()).append('\n');
                appendSubreportGuidance(text, result);
                if (!result.candidates().isEmpty()) {
                    text.append("Candidatos encontrados:\n");
                    result.candidates().forEach(candidate ->
                            text.append("  - ").append(candidate).append('\n'));
                }
                text.append('\n');
            }
        }
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static void appendSubreportGuidance(StringBuilder text,
                                                SubreportReferenceResult result) {
        if (result.status() == SubreportResolutionStatus.AMBIGUOUS) {
            text.append("Problema: MAIS DE UM CANDIDATO. Existem vários JRXML com o nome referenciado.\n")
                    .append("Ação: Confirmar qual caminho é utilizado pelo Jasper em execução. Nenhum arquivo foi escolhido automaticamente.\n");
        } else if (result.status() == SubreportResolutionStatus.DYNAMIC) {
            text.append("Problema: CAMINHO DINÂMICO. A expressão depende de parâmetro, variável ou concatenação.\n")
                    .append("Ação: Conferir o valor produzido em execução e se o JRXML correspondente acompanha o relatório.\n");
        } else {
            text.append("Problema: ARQUIVO NÃO ENCONTRADO. Nenhum JRXML com o nome referenciado foi recebido.\n")
                    .append("Ação: Incluir o complement ou arquivo que contém o subreport antes da publicação.\n");
        }
    }

    private static boolean requiresAttention(ConversionStatus status) {
        return status == ConversionStatus.REVIEW || status == ConversionStatus.FAILED;
    }

    private static ReviewGuidance reviewGuidance(GroovySqlResult result) {
        String safe = result.message() == null ? "" : result.message();
        if (safe.contains("sem MATCHING")) {
            return new ReviewGuidance(
                    "UPSERT SEM CHAVE DE CONFLITO",
                    "Foi encontrado UPDATE OR INSERT do Firebird sem a cláusula MATCHING.",
                    "O PostgreSQL exige uma chave para gerar ON CONFLICT, mas ela não está informada no SQL.",
                    "A consulta original não é aceita pelo PostgreSQL e escolher a chave errada pode atualizar outro registro.",
                    "Confirmar a chave primária ou única da tabela e informar MATCHING no SQL original ou fornecer esse metadado ao conversor.");
        }
        if (safe.contains("FIRST/SKIP") && (safe.contains("UNION") || safe.contains("INTERSECT")
                || safe.contains("EXCEPT"))) {
            return new ReviewGuidance(
                    "PAGINAÇÃO EM OPERAÇÃO DE CONJUNTO",
                    "Foi encontrado FIRST ou SKIP junto de UNION, INTERSECT ou EXCEPT.",
                    "O limite pode pertencer a apenas um SELECT ou ao resultado completo; mover a paginação sem confirmar o escopo pode mudar o resultado.",
                    "Uma conversão incorreta pode devolver quantidade ou registros diferentes do Firebird.",
                    "Confirmar a qual SELECT cada FIRST/SKIP pertence e, quando necessário, colocar cada ramo entre parênteses antes de aplicar LIMIT/OFFSET.");
        }
        if (safe.contains("várias etapas")) {
            return new ReviewGuidance(
                    "SQL MONTADO EM VÁRIAS ETAPAS",
                    "A variável SQL recebe novos trechos posteriormente, normalmente com += ou dentro de condições.",
                    "O trecho encontrado não representa a consulta completa que será enviada ao banco.",
                    "Partes com sintaxe Firebird podem permanecer sem conversão ou colunas e valores podem ficar desalinhados.",
                    "Reconstruir todas as possibilidades da consulta e validar cada resultado completo antes de converter.");
        }
        if (safe.contains("concatenação")) {
            return new ReviewGuidance(
                    "SQL MONTADO POR CONCATENAÇÃO",
                    "A consulta está dividida em strings e valores unidos pelo operador +.",
                    "O analisador não conseguiu provar que a junção forma sempre a mesma consulta SQL completa.",
                    "O SQL pode já ser compatível, mas também pode manter funções ou comandos exclusivos do Firebird.",
                    "Revisar a expressão completa. Se as partes forem constantes ou valores escalares, ela poderá ser automatizada pela análise AST.");
        }
        if (safe.startsWith("Fragmento de SQL")) {
            return new ReviewGuidance(
                    "FRAGMENTO SQL DINÂMICO",
                    "Foi encontrada somente uma parte da consulta, como um filtro AND/OR contendo outro SELECT.",
                    "Um fragmento isolado não pode ser validado como uma consulta completa; ele precisa ser relacionado ao SQL em que é inserido.",
                    "Este aviso pode ser conservador e não significa necessariamente que o fragmento esteja incompatível.",
                    "Verificar a consulta que interpola essa variável e confirmar se functions, filtros e aliases existem no PostgreSQL.");
        }
        if (safe.contains("SQL dinâmico")) {
            return new ReviewGuidance(
                    "ESTRUTURA DINÂMICA NÃO RESOLVIDA",
                    "A consulta contém uma expressão Groovy cujo conteúdo não pôde ser determinado pela análise estática.",
                    "O valor depende de código ou dados de execução que não puderam ser acompanhados com segurança.",
                    "O conteúdo produzido em execução pode alterar a estrutura do SQL ou manter sintaxe Firebird.",
                    "Localizar onde a expressão é definida e revisar todas as possibilidades de valor junto da consulta completa.");
        }
        if (safe.contains("expressão ${") || safe.contains("A expressão $")) {
            return new ReviewGuidance(
                    "EXPRESSÃO GROOVY NÃO RESOLVIDA",
                    safe,
                    "Nem todos os valores possíveis da expressão são compatíveis com o ponto em que ela aparece no SQL.",
                    "Liberar a expressão sem comprovar seus valores pode manter sintaxe Firebird ou alterar a estrutura da consulta.",
                    "Revisar a expressão indicada e sua definição. O restante da consulta foi preservado.");
        }
        if (result.status() == ConversionStatus.FAILED) {
            return new ReviewGuidance(
                    "FALHA AO ANALISAR O SQL",
                    "O parser não conseguiu interpretar a consulta encontrada.",
                    "A estrutura não foi reconhecida com segurança, portanto nenhum trecho foi alterado.",
                    "O arquivo de saída mantém o SQL original, que pode não funcionar no PostgreSQL.",
                    "Revisar o detalhe técnico, corrigir ou isolar a construção não reconhecida e executar novamente.");
        }
        return new ReviewGuidance(
                "REVISÃO DE COMPATIBILIDADE",
                "A consulta contém uma construção que não foi validada automaticamente.",
                "Não houve evidência suficiente para alterar o SQL sem risco.",
                "O arquivo preserva o conteúdo original, que pode ser incompatível com PostgreSQL.",
                "Revisar o SQL original e testar a consulta completa no PostgreSQL.");
    }

    private static ReviewGuidance jrxmlReviewGuidance(QueryResult result) {
        String safe = result.message() == null ? "" : result.message();
        String adjacentColumns = adjacentQualifiedColumns(result.originalSql());
        if (adjacentColumns != null && (safe.contains("ParserException") || safe.contains("Unexpected"))) {
            return new ReviewGuidance(
                    "PROVÁVEL VÍRGULA AUSENTE NO SELECT",
                    "Foram encontrados dois campos qualificados consecutivos sem separador: " + adjacentColumns + ".",
                    "Em uma lista de SELECT, campos diferentes precisam ser separados por vírgula.",
                    "A consulta é sintaticamente inválida e deve falhar tanto na análise quanto na execução no PostgreSQL.",
                    "Conferir os dois campos indicados e inserir a vírgula ausente antes de executar o conversor novamente.");
        }
        if (safe.contains("$X{") || safe.contains("expressão Jasper")) {
            return new ReviewGuidance(
                    "EXPRESSÃO JASPER NÃO CONVERTIDA",
                    "A queryString contém uma expressão dinâmica do Jasper com conteúdo SQL.",
                    "O conteúdo interno não pôde ser separado e convertido sem alterar o comportamento dos parâmetros do relatório.",
                    "A expressão pode produzir sintaxe Firebird quando o relatório for executado no PostgreSQL.",
                    "Revisar a expressão Jasper indicada, mantendo seus parâmetros, e converter somente o SQL produzido por ela.");
        }
        if (safe.contains("ParserException") || safe.contains("Unexpected")
                || safe.contains("expected")) {
            return new ReviewGuidance(
                    "SQL NÃO RECONHECIDO PELO PARSER",
                    "O parser encontrou uma construção inesperada na queryString e informou uma posição aproximada por linha e coluna.",
                    "A consulta pode ter erro de sintaxe original, como vírgula, operador ou parêntese ausente, ou usar uma construção ainda não suportada.",
                    "A queryString foi preservada e pode falhar no PostgreSQL se for utilizada sem correção.",
                    "Abrir a queryString indicada na linha e coluna informadas, corrigir a sintaxe ou identificar a construção especial e executar novamente.");
        }
        if (safe.contains("Firebird") || safe.contains("incompatível")) {
            return new ReviewGuidance(
                    "SINTAXE FIREBIRD RESTANTE",
                    "A validação encontrou uma construção específica do Firebird depois da tentativa de conversão.",
                    "Não foi possível determinar uma transformação PostgreSQL equivalente sem alterar o significado da consulta.",
                    "O SQL original pode ser rejeitado ou produzir resultado diferente no PostgreSQL.",
                    "Revisar a construção informada no detalhe técnico e definir sua equivalência PostgreSQL.");
        }
        return new ReviewGuidance(
                "FALHA NA QUERYSTRING",
                "A queryString não pôde ser validada ou convertida automaticamente.",
                "O conversor não obteve segurança suficiente para modificar o conteúdo do JRXML.",
                "O arquivo de saída mantém a consulta original, que pode não funcionar no PostgreSQL.",
                "Revisar o detalhe técnico e o trecho original, corrigir a consulta e executar o conversor novamente.");
    }

    private static String adjacentQualifiedColumns(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        int from = Pattern.compile("(?i)\\bFROM\\b").matcher(sql).results()
                .findFirst().map(match -> match.start()).orElse(sql.length());
        Matcher matcher = ADJACENT_QUALIFIED_COLUMNS.matcher(sql.substring(0, from));
        return matcher.find() ? "`" + matcher.group(1) + "` e `" + matcher.group(2) + "`" : null;
    }

    private record ReviewGuidance(String type, String found, String reason, String risk, String action) {
    }

    private static String sqlPreview(String sql) {
        if (sql == null || sql.isBlank()) {
            return "(indisponível)";
        }
        String normalized = sql.replaceAll("\\s+", " ").trim();
        int limit = 300;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private static StringBuilder reviewHeader(String title, int pendingCount) {
        return new StringBuilder()
                .append(title).append('\n')
                .append(REPORT_SEPARATOR).append('\n')
                .append("Total: ").append(pendingCount).append("\n\n");
    }

    private static String technicalDetail(String value) {
        String normalized = singleLine(value);
        if (normalized.isBlank()) {
            return "(não informado)";
        }

        Matcher parserLocation = PARSER_LOCATION.matcher(normalized);
        if (parserLocation.find()) {
            String cause = parserLocation.group(1).trim().replaceFirst(":$", "");
            if (cause.isBlank()) {
                cause = "falha de interpretação do SQL";
            }
            return "ParserException: " + cause + ". Posição aproximada informada pelo parser: linha "
                    + parserLocation.group(2) + ", coluna " + parserLocation.group(3) + ".";
        }

        return GROOVY_INTERNAL_TOKEN.matcher(normalized.replace("[*]", ""))
                .replaceAll("expressão Groovy dinâmica")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static void writeSummary(Path destination, ConversionSummary summary) throws IOException {
        String text = """
                Conversor JRXML/Groovy - resumo
                Gerado em: %s
                Arquivos JRXML: %d
                QueryStrings JRXML registradas: %d
                QueryStrings JRXML convertidas: %d
                QueryStrings JRXML vazias: %d
                QueryStrings JRXML não SQL ignoradas: %d
                Falhas em JRXML: %d
                Referências de subreport: %d
                Subreports localizados no conjunto: %d
                Subreports resolvidos sem ambiguidade: %d
                Subreports ambíguos: %d
                Subreports dinâmicos: %d
                Subreports ausentes: %d
                Arquivos Groovy: %d
                SQLs Groovy identificados: %d
                SQLs Groovy convertidos: %d
                SQLs Groovy para revisão: %d
                Falhas em Groovy: %d
                """.formatted(Instant.now(), summary.jrxmlFiles(), summary.queryEntries(),
                summary.convertedQueries(), summary.emptyQueries(), summary.ignoredQueries(), summary.failedQueries(),
                summary.subreportReferences(), summary.locatedSubreports(), summary.resolvedSubreports(),
                summary.ambiguousSubreports(), summary.dynamicSubreports(), summary.missingSubreports(),
                summary.groovyFiles(),
                summary.groovySqlEntries(), summary.convertedGroovySql(), summary.reviewGroovySql(),
                summary.failedGroovySql());
        Files.writeString(destination, text, StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
