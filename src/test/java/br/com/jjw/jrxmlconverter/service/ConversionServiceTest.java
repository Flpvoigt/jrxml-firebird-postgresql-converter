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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Files.writeString(input.resolve("rotina.groovy"), """
                def sql = "SELECT FIRST 1 ID FROM PRODUTOS WHERE CODIGO = ${codigo}"
                """);

        var sqlConverter = new FirebirdToPostgresSqlConverter();
        var service = new ConversionService(new SecureXmlParser(), new SubreportInspector(),
                new JrxmlProcessor(sqlConverter), new GroovyProcessor(sqlConverter));
        var options = new CommandLineOptions(input, output, false, false, false);

        var run = service.execute(options);
        new ConversionReportWriter().write(output, run);

        assertEquals(1, run.summary().jrxmlFiles());
        assertEquals(1, run.summary().groovyFiles());
        assertTrue(Files.exists(output.resolve("jrxml/relatorio.jrxml")));
        assertTrue(Files.exists(output.resolve("groovy/rotina.groovy")));
        assertTrue(Files.exists(output.resolve("jrxml/conversion-report.csv")));
        assertTrue(Files.exists(output.resolve("groovy/conversion-report.csv")));
        assertTrue(Files.notExists(output.resolve("relatorio.jrxml")));
    }
}
