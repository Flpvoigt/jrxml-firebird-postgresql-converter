package br.com.jjw.jrxmlconverter;

import br.com.jjw.jrxmlconverter.cli.CommandLineOptions;
import br.com.jjw.jrxmlconverter.domain.ConversionRun;
import br.com.jjw.jrxmlconverter.domain.ConversionSummary;
import br.com.jjw.jrxmlconverter.groovy.GroovyProcessor;
import br.com.jjw.jrxmlconverter.jrxml.JrxmlProcessor;
import br.com.jjw.jrxmlconverter.metadata.SchemaMetadata;
import br.com.jjw.jrxmlconverter.metadata.SchemaMetadataLoader;
import br.com.jjw.jrxmlconverter.report.ConversionReportWriter;
import br.com.jjw.jrxmlconverter.service.ConversionService;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import br.com.jjw.jrxmlconverter.xml.SecureXmlParser;
import br.com.jjw.jrxmlconverter.xml.SubreportInspector;

import java.nio.file.Path;

public final class JrxmlConverterApplication {
    private JrxmlConverterApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        try {
            CommandLineOptions options = CommandLineOptions.parse(args);
            if (options.help()) {
                printUsage();
                return 0;
            }

            SchemaMetadata metadata = new SchemaMetadataLoader().load(options.schemaMetadata());
            ConversionService service = createService(metadata);
            ConversionRun run = service.execute(options);
            Path input = options.input().toAbsolutePath().normalize();
            Path output = options.output() == null ? null : options.output().toAbsolutePath().normalize();
            printSummary(input, output, options, run.summary());

            if (!options.dryRun()) {
                new ConversionReportWriter().write(output, run);
                if (run.groupedByProject()) {
                    System.out.println("Estrutura convertida preservada em: " + output);
                    System.out.println("Diagnósticos separados por projeto em: "
                            + output.resolve("_conversion-reports/projects"));
                } else {
                    System.out.println("Estrutura convertida preservada em: " + output);
                    System.out.println("Relatório JRXML: "
                            + output.resolve("_conversion-reports/jrxml/conversion-report.csv"));
                    System.out.println("Relatório Groovy: "
                            + output.resolve("_conversion-reports/groovy/conversion-report.csv"));
                }
            }
            return run.summary().hasProblems() ? 3 : 0;
        } catch (IllegalArgumentException exception) {
            System.err.println("ERRO: " + exception.getMessage());
            System.err.println();
            printUsage();
            return 2;
        } catch (Exception exception) {
            System.err.println("FALHA: " + exception.getMessage());
            exception.printStackTrace(System.err);
            return 1;
        }
    }

    private static ConversionService createService(SchemaMetadata metadata) {
        var sqlConverter = new FirebirdToPostgresSqlConverter(metadata);
        return new ConversionService(
                new SecureXmlParser(), new SubreportInspector(), new JrxmlProcessor(sqlConverter),
                new GroovyProcessor(sqlConverter));
    }

    private static void printSummary(Path input, Path output, CommandLineOptions options,
                                     ConversionSummary summary) {
        System.out.println("Entrada: " + input);
        System.out.println("Modo: " + (options.dryRun() ? "somente análise" : "conversão"));
        if (output != null) {
            System.out.println("Saída: " + output);
        }
        if (options.dualDatabaseGroovy()) {
            System.out.println("Groovy: modo compatível com Firebird e PostgreSQL");
        }
        System.out.println("JRXML: " + summary.jrxmlFiles());
        System.out.println((options.dryRun() ? "Consultas JRXML validadas: " : "Consultas JRXML convertidas: ")
                + summary.convertedQueries());
        System.out.println("Consultas JRXML vazias: " + summary.emptyQueries());
        System.out.println("Consultas JRXML não SQL ignoradas: " + summary.ignoredQueries());
        System.out.println("Falhas em JRXML: " + summary.failedQueries());
        System.out.println("Subreports localizados: " + summary.locatedSubreports()
                + "/" + summary.subreportReferences());
        System.out.println("Subreports resolvidos sem ambiguidade: "
                + summary.resolvedSubreports());
        System.out.println("Subreports ambíguos: " + summary.ambiguousSubreports());
        System.out.println("Subreports dinâmicos: " + summary.dynamicSubreports());
        System.out.println("Subreports ausentes: " + summary.missingSubreports());
        System.out.println("Groovy: " + summary.groovyFiles());
        System.out.println((options.dryRun() ? "SQLs Groovy validados: " : "SQLs Groovy convertidos: ")
                + summary.convertedGroovySql());
        System.out.println("SQLs Groovy para revisão: " + summary.reviewGroovySql());
        System.out.println("Falhas em Groovy: " + summary.failedGroovySql());
    }

    private static void printUsage() {
        System.out.println("""
                Uso:
                  java -jar target/jrxml-converter-0.2.0-SNAPSHOT.jar \
                    --input <diretorio-origem> --output <diretorio-saida> [--overwrite]

                  java -jar target/jrxml-converter-0.2.0-SNAPSHOT.jar \
                    --input <diretorio-origem> --dry-run

                Opções:
                  --input       Arquivo .jrxml/.groovy ou pasta pesquisada recursivamente.
                  --output      Raiz das saídas separadas nas pastas jrxml e groovy.
                  --overwrite   Permite substituir arquivos existentes somente na saída.
                  --dry-run     Analisa e converte em memória, sem escrever arquivos.
                  --schema-metadata  JSON com as chaves das tabelas extraídas do banco.
                  --dual-database-groovy  Preserva o SQL Firebird e inclui a versão PostgreSQL nos Groovys.
                  --help        Exibe esta ajuda.
                """);
    }
}
