package br.com.jjw.jrxmlconverter.xml;

import br.com.jjw.jrxmlconverter.domain.SubreportResolutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubreportInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void classifiesReferencesAcrossTheWholeInputWithoutGuessingAmbiguousFiles() throws Exception {
        Path client = Files.createDirectories(temporaryDirectory.resolve("cliente/reports"));
        Path shared = Files.createDirectories(temporaryDirectory.resolve("core/reports"));
        Path duplicate = Files.createDirectories(temporaryDirectory.resolve("outro/reports"));
        Path master = client.resolve("master.jrxml");
        Path local = Files.writeString(client.resolve("local.jrxml"), "<jasperReport/>");
        Path unique = Files.writeString(shared.resolve("shared.jrxml"), "<jasperReport/>");
        Path ambiguousOne = Files.writeString(shared.resolve("duplicado.jrxml"), "<jasperReport/>");
        Path ambiguousTwo = Files.writeString(duplicate.resolve("duplicado.jrxml"), "<jasperReport/>");
        String xml = """
                <jasperReport xmlns="http://jasperreports.sourceforge.net/jasperreports">
                    <subreportExpression><![CDATA["local.jasper"]]></subreportExpression>
                    <subreportExpression><![CDATA["shared.jrxml"]]></subreportExpression>
                    <subreportExpression><![CDATA["duplicado.jasper"]]></subreportExpression>
                    <subreportExpression><![CDATA["ausente.jasper"]]></subreportExpression>
                    <subreportExpression><![CDATA[$P{SUBREPORT_DIR} + "dinamico.jasper"]]></subreportExpression>
                    <subreportExpression><![CDATA["prefixo/" + $P{NOME_SUBREPORT} + ".jasper"]]></subreportExpression>
                </jasperReport>
                """;
        Files.writeString(master, xml);
        List<Path> files = List.of(master, local, unique, ambiguousOne, ambiguousTwo);
        var inspector = new SubreportInspector();

        var inspection = inspector.inspect(new SecureXmlParser().parse(xml, master), master,
                temporaryDirectory, inspector.catalog(files));

        assertEquals(6, inspection.stats().total());
        assertEquals(3, inspection.stats().located());
        assertEquals(2, inspection.stats().resolved());
        assertEquals(1, inspection.stats().ambiguous());
        assertEquals(1, inspection.stats().missing());
        assertEquals(2, inspection.stats().dynamic());
        assertEquals(List.of(
                        SubreportResolutionStatus.LOCAL,
                        SubreportResolutionStatus.SHARED,
                        SubreportResolutionStatus.AMBIGUOUS,
                        SubreportResolutionStatus.MISSING,
                        SubreportResolutionStatus.DYNAMIC,
                        SubreportResolutionStatus.DYNAMIC),
                inspection.references().stream().map(reference -> reference.status()).toList());
    }
}
