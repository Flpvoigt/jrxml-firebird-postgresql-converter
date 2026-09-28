package br.com.jjw.jrxmlconverter.jrxml;

import br.com.jjw.jrxmlconverter.domain.ConversionStatus;
import br.com.jjw.jrxmlconverter.sql.FirebirdToPostgresSqlConverter;
import br.com.jjw.jrxmlconverter.xml.SecureXmlParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JrxmlProcessorTest {
    private final SecureXmlParser parser = new SecureXmlParser();
    private final JrxmlProcessor processor =
            new JrxmlProcessor(new FirebirdToPostgresSqlConverter());

    @Test
    void ignoresNonSqlQueryLanguagesWithoutChangingXml() {
        String xml = """
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <queryString language="xPath"><![CDATA[/nfeProc/NFe/infNFe/det]]></queryString>
                </jasperReport>
                """;

        var conversion = processor.convert(Path.of("danfe.jrxml"), xml,
                parser.parse(xml, Path.of("danfe.jrxml")));

        assertEquals(ConversionStatus.IGNORED, conversion.queryResults().getFirst().status());
        assertEquals(xml, conversion.xml());
    }

    @Test
    void acceptsEmptyQueryWithoutCdata() {
        String xml = """
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <queryString>&#xD;</queryString>
                </jasperReport>
                """;

        var conversion = processor.convert(Path.of("empty.jrxml"), xml,
                parser.parse(xml, Path.of("empty.jrxml")));

        assertEquals(ConversionStatus.EMPTY, conversion.queryResults().getFirst().status());
        assertEquals(xml, conversion.xml());
    }

    @Test
    void convertsSqlWithoutCdataAndKeepsValidXmlEscaping() {
        String xml = """
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <queryString>select first 1 codigo from produtos where codigo &gt; 0</queryString>
                </jasperReport>
                """;

        var conversion = processor.convert(Path.of("plain.jrxml"), xml,
                parser.parse(xml, Path.of("plain.jrxml")));

        assertEquals(ConversionStatus.CONVERTED, conversion.queryResults().getFirst().status(),
                conversion.queryResults().getFirst().message());
        parser.parse(conversion.xml(), Path.of("plain-converted.jrxml"));
    }
}
