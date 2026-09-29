package br.com.jjw.jrxmlconverter.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SchemaMetadataLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public SchemaMetadata load(Path path) throws IOException {
        if (path == null) {
            return SchemaMetadata.empty();
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("Arquivo de metadados inexistente: " + normalized);
        }
        JsonNode root = mapper.readTree(normalized.toFile());
        if (root.path("formatVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException(
                    "Metadados inválidos: 'formatVersion' deve ser 1.");
        }
        JsonNode tablesNode = root.path("tables");
        if (!tablesNode.isObject()) {
            throw new IllegalArgumentException("Metadados inválidos: o objeto 'tables' é obrigatório.");
        }

        Map<String, SchemaMetadata.TableMetadata> tables = new LinkedHashMap<>();
        tablesNode.fields().forEachRemaining(entry -> {
            JsonNode primaryKeyNode = entry.getValue().path("primaryKey");
            List<String> primaryKey = new ArrayList<>();
            if (primaryKeyNode.isArray()) {
                primaryKeyNode.forEach(column -> {
                    if (column.isTextual() && !column.textValue().isBlank()) {
                        primaryKey.add(column.textValue());
                    }
                });
            }
            tables.put(entry.getKey(), new SchemaMetadata.TableMetadata(primaryKey));
        });
        return new SchemaMetadata(tables);
    }
}
