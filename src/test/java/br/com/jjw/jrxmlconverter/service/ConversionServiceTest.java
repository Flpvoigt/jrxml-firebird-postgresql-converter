package br.com.jjw.jrxmlconverter.service;

import br.com.jjw.jrxmlconverter.cli.CommandLineOptions;
import br.com.jjw.jrxmlconverter.groovy.GroovyProcessor;
import br.com.jjw.jrxmlconverter.jrxml.JrxmlProcessor;
import br.com.jjw.jrxmlconverter.report.ConversionReportWriter;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import br.com.jjw.jrxmlconverter.xml.SecureXmlParser;
import br.com.jjw.jrxmlconverter.xml.SubreportInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesJrxmlAndGroovyToSeparateOutputTrees() throws Exception {
        Path input = Files.createDirectory(temporaryDirectory.resolve("input"));
        Path output = temporaryDirectory.resolve("output");
        Files.writeString(input.resolve("relatorio.jrxml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <queryString><![CDATA[SELECT FIRST 1 ID FROM PRODUTOS]]></queryString>
                </jasperReport>
                """);
        Path nestedGroovyDirectory = Files.createDirectories(input.resolve("endpoints/tray"));
        Files.writeString(nestedGroovyDirectory.resolve("rotina.groovy"), """
                def sql = "SELECT FIRST 1 ID FROM PRODUTOS WHERE CODIGO = ${codigo}"
                """);
        Path staleDirectory = Files.createDirectories(output.resolve("groovy/tray"));
        Files.writeString(staleDirectory.resolve("arquivo-antigo.groovy"), "conteúdo antigo");

        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));
        var options = new CommandLineOptions(input, output, true, false, false);

        var run = service.execute(options);
        new ConversionReportWriter().write(output, run);

        assertEquals(1, run.summary().jrxmlFiles());
        assertEquals(1, run.summary().groovyFiles());
        assertTrue(Files.exists(output.resolve("jrxml/relatorio.jrxml")));
        assertTrue(Files.exists(output.resolve("groovy/rotina.groovy")));
        assertTrue(Files.notExists(output.resolve("groovy/endpoints")));
        assertTrue(Files.notExists(output.resolve("groovy/tray")));
        assertTrue(Files.exists(output.resolve("jrxml/conversion-report.csv")));
        assertTrue(Files.exists(output.resolve("groovy/conversion-report.csv")));
        assertTrue(Files.notExists(output.resolve("relatorio.jrxml")));
    }

    @Test
    void readsAndPreservesWindows1252GroovySource() throws Exception {
        Charset windows1252 = Charset.forName("windows-1252");
        Path input = Files.createDirectory(temporaryDirectory.resolve("ansi-input"));
        Path output = temporaryDirectory.resolve("ansi-output");
        String source = """
                // integração com acentuação
                def sql = "SELECT FIRST 1 DESCRICAO FROM PRODUTOS"
                """;
        Files.write(input.resolve("sql-utils.groovy"), source.getBytes(windows1252));

        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));
        var options = new CommandLineOptions(input, output, false, false, false);

        var run = service.execute(options);
        byte[] convertedBytes = Files.readAllBytes(output.resolve("groovy/sql-utils.groovy"));
        String converted = new String(convertedBytes, windows1252);

        assertEquals(1, run.summary().convertedGroovySql());
        assertTrue(converted.contains("acentuação"), converted);
        assertTrue(converted.toLowerCase().contains("fetch next 1 rows only"), converted);
    }

    @Test
    void refusesDuplicateGroovyNamesWhenFlatteningOutput() throws Exception {
        Path input = Files.createDirectory(temporaryDirectory.resolve("duplicate-input"));
        Files.createDirectories(input.resolve("a"));
        Files.createDirectories(input.resolve("b"));
        Files.writeString(input.resolve("a/rotina.groovy"), "def valor = 1");
        Files.writeString(input.resolve("b/rotina.groovy"), "def valor = 2");

        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));
        var options = new CommandLineOptions(input, temporaryDirectory.resolve("duplicate-output"),
                false, false, false);

        var exception = assertThrows(IllegalArgumentException.class, () -> service.execute(options));
        assertTrue(exception.getMessage().contains("mesmo nome"), exception.getMessage());
    }

    @Test
    void groupsMultipleComplementProjectsAndIgnoresBuildOutputs() throws Exception {
        Path input = Files.createDirectory(temporaryDirectory.resolve("complements"));
        Path project101 = Files.createDirectories(input.resolve(
                "besser-complements-101/src/main/resources/besser-core/endpoints/tray"));
        Path project102 = Files.createDirectories(input.resolve(
                "besser-complements-102/src/main/resources/besser-core/endpoints/tray"));
        Files.writeString(project101.resolve("sql-utils.groovy"),
                "def sql = \"SELECT FIRST 1 ID FROM PRODUTOS\"");
        Files.writeString(project102.resolve("sql-utils.groovy"),
                "def sql = \"SELECT FIRST 1 ID FROM CLIENTES\"");
        Path generated = Files.createDirectories(input.resolve(
                "besser-complements-101/target/classes/besser-core/endpoints/tray"));
        Files.writeString(generated.resolve("sql-utils.groovy"), "def copia = true");
        Path output = temporaryDirectory.resolve("grouped-output");

        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));
        var options = new CommandLineOptions(input, output, true, false, false);

        var run = service.execute(options);
        new ConversionReportWriter().write(output, run);

        assertTrue(run.groupedByProject());
        assertEquals(2, run.summary().groovyFiles());
        assertTrue(Files.exists(output.resolve(
                "besser-complements-101/groovy/endpoints/sql-utils.groovy")));
        assertTrue(Files.exists(output.resolve(
                "besser-complements-102/groovy/endpoints/sql-utils.groovy")));
        assertTrue(Files.exists(output.resolve(
                "besser-complements-101/groovy/conversion-report.csv")));
        assertTrue(Files.notExists(output.resolve("besser-complements-101/groovy/target")));
    }

    @Test
    void acceptsOneJrxmlOrOneGroovyAsDirectInput() throws Exception {
        Path jrxml = temporaryDirectory.resolve("unico.jrxml");
        Files.writeString(jrxml, """
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <queryString><![CDATA[SELECT FIRST 1 ID FROM PRODUTOS]]></queryString>
                </jasperReport>
                """);
        Path groovy = temporaryDirectory.resolve("unico.groovy");
        Files.writeString(groovy, "def sql = \"SELECT FIRST 1 ID FROM PRODUTOS\"");
        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));

        var jrxmlRun = service.execute(new CommandLineOptions(jrxml,
                temporaryDirectory.resolve("single-jrxml-output"), false, false, false));
        var groovyRun = service.execute(new CommandLineOptions(groovy,
                temporaryDirectory.resolve("single-groovy-output"), false, false, false));

        assertEquals(1, jrxmlRun.summary().jrxmlFiles());
        assertEquals(1, jrxmlRun.summary().convertedQueries());
        assertTrue(Files.exists(temporaryDirectory.resolve("single-jrxml-output/jrxml/unico.jrxml")));
        assertEquals(1, groovyRun.summary().groovyFiles());
        assertEquals(1, groovyRun.summary().convertedGroovySql());
        assertTrue(Files.exists(temporaryDirectory.resolve("single-groovy-output/groovy/unico.groovy")));
    }

    @Test
    void keepsProcessingAfterAnInvalidJrxml() throws Exception {
        Path input = Files.createDirectory(temporaryDirectory.resolve("resilient-input"));
        String invalid = "<jasperReport><queryString>SELECT * FROM";
        Files.writeString(input.resolve("invalido.jrxml"), invalid);
        Files.writeString(input.resolve("valido.jrxml"), """
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <queryString><![CDATA[SELECT FIRST 1 ID FROM PRODUTOS]]></queryString>
                </jasperReport>
                """);
        Path output = temporaryDirectory.resolve("resilient-output");
        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));

        var run = service.execute(new CommandLineOptions(input, output, false, false, false));

        assertEquals(2, run.summary().jrxmlFiles());
        assertEquals(1, run.summary().convertedQueries());
        assertEquals(1, run.summary().failedQueries());
        assertEquals(invalid, Files.readString(output.resolve("jrxml/invalido.jrxml")));
        assertTrue(Files.readString(output.resolve("jrxml/valido.jrxml"))
                .toLowerCase().contains("fetch next 1 rows only"));
    }

    @Test
    void rejectsUnsupportedSingleFile() throws Exception {
        Path input = temporaryDirectory.resolve("arquivo.txt");
        Files.writeString(input, "conteúdo");
        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));

        var exception = assertThrows(IllegalArgumentException.class, () -> service.execute(
                new CommandLineOptions(input, temporaryDirectory.resolve("unsupported-output"),
                        false, false, false)));

        assertTrue(exception.getMessage().contains(".jrxml ou .groovy"), exception.getMessage());
    }
}
