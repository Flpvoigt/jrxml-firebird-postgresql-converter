package br.com.jjw.jrxmlconverter.cli;

import java.nio.file.Path;

public record CommandLineOptions(Path input, Path output, boolean overwrite, boolean dryRun,
                                 boolean help, Path schemaMetadata, boolean dualDatabaseGroovy) {
    public CommandLineOptions(Path input, Path output, boolean overwrite, boolean dryRun, boolean help) {
        this(input, output, overwrite, dryRun, help, null, false);
    }

    public CommandLineOptions(Path input, Path output, boolean overwrite, boolean dryRun,
                              boolean help, Path schemaMetadata) {
        this(input, output, overwrite, dryRun, help, schemaMetadata, false);
    }

    public static CommandLineOptions parse(String[] args) {
        Path input = null;
        Path output = null;
        boolean overwrite = false;
        boolean dryRun = false;
        boolean help = false;
        Path schemaMetadata = null;
        boolean dualDatabaseGroovy = false;

        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--input" -> input = Path.of(requireValue(args, ++index, "--input"));
                case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                case "--overwrite" -> overwrite = true;
                case "--dry-run" -> dryRun = true;
                case "--schema-metadata" -> schemaMetadata = Path.of(
                        requireValue(args, ++index, "--schema-metadata"));
                case "--dual-database-groovy" -> dualDatabaseGroovy = true;
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("Opção desconhecida: " + args[index]);
            }
        }
        if (!help && input == null) {
            throw new IllegalArgumentException("A opção --input é obrigatória.");
        }
        return new CommandLineOptions(input, output, overwrite, dryRun, help, schemaMetadata,
                dualDatabaseGroovy);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Valor ausente para " + option);
        }
        return args[index];
    }
}
