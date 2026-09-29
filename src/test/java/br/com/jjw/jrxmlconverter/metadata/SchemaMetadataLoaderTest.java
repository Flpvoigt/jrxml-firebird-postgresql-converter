package br.com.jjw.jrxmlconverter.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaMetadataLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsPrimaryKeyContract() throws Exception {
        Path metadata = temporaryDirectory.resolve("schema.json");
        Files.writeString(metadata, """
                {
                  "formatVersion": 1,
                  "tables": {
                    "PRODUTOS": {"primaryKey": ["COD_EMPRESA", "COD_PRODUTO"]}
                  }
                }
                """);

        SchemaMetadata loaded = new SchemaMetadataLoader().load(metadata);

        assertEquals(List.of("COD_EMPRESA", "COD_PRODUTO"), loaded.primaryKey("produtos"));
    }

    @Test
    void rejectsUnknownFormatVersion() throws Exception {
        Path metadata = temporaryDirectory.resolve("schema.json");
        Files.writeString(metadata, """
                {"formatVersion": 2, "tables": {}}
                """);

        assertThrows(IllegalArgumentException.class,
                () -> new SchemaMetadataLoader().load(metadata));
    }
}
