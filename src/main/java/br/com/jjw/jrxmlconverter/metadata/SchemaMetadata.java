package br.com.jjw.jrxmlconverter.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SchemaMetadata {
    private static final SchemaMetadata EMPTY = new SchemaMetadata(Map.of());

    private final Map<String, TableMetadata> tables;

    public SchemaMetadata(Map<String, TableMetadata> tables) {
        Map<String, TableMetadata> normalized = new LinkedHashMap<>();
        if (tables != null) {
            tables.forEach((name, table) -> {
                if (name != null && table != null) {
                    normalized.put(normalizeIdentifier(name), table.normalized());
                }
            });
        }
        this.tables = Map.copyOf(normalized);
    }

    public static SchemaMetadata empty() {
        return EMPTY;
    }

    public List<String> primaryKey(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }
        String normalized = normalizeIdentifier(tableName);
        TableMetadata table = tables.get(normalized);
        if (table == null && normalized.contains(".")) {
            table = tables.get(normalized.substring(normalized.lastIndexOf('.') + 1));
        }
        return table == null ? List.of() : table.primaryKey();
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier.trim().replace("\"", "").toUpperCase(Locale.ROOT);
    }

    public record TableMetadata(List<String> primaryKey) {
        public TableMetadata {
            primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        }

        private TableMetadata normalized() {
            List<String> columns = new ArrayList<>();
            for (String column : primaryKey) {
                if (column != null && !column.isBlank()) {
                    columns.add(column.trim());
                }
            }
            return new TableMetadata(columns);
        }
    }
}
