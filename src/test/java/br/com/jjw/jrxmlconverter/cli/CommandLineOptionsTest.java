package br.com.jjw.jrxmlconverter.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineOptionsTest {
    @Test
    void readsSchemaMetadataPath() {
        CommandLineOptions options = CommandLineOptions.parse(new String[]{
                "--input", "entrada", "--output", "saida",
                "--schema-metadata", "schema.json"
        });

        assertEquals(Path.of("schema.json"), options.schemaMetadata());
    }

    @Test
    void parsesDryRun() {
        var options = CommandLineOptions.parse(new String[]{"--input", "reports", "--dry-run"});
        assertEquals("reports", options.input().toString());
        assertTrue(options.dryRun());
        assertNull(options.output());
    }

    @Test
    void rejectsMissingInput() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLineOptions.parse(new String[]{"--dry-run"}));
    }
}
