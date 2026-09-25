package br.com.jjw.jrxmlconverter.cli;

import java.nio.file.Path;

public record CommandLineOptions(Path input, Path output, boolean overwrite, boolean dryRun, boolean help) {
    public static CommandLineOptions parse(String[] args) {
        Path input = null;
        Path output = null;
        boolean overwrite = false;
        boolean dryRun = false;
        boolean help = false;

        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--input" -> input = Path.of(requireValue(args, ++index, "--input"));
                case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                case "--overwrite" -> overwrite = true;
                case "--dry-run" -> dryRun = true;
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("Opção desconhecida: " + args[index]);
            }
        }
        if (!help && input == null) {
            throw new IllegalArgumentException("A opção --input é obrigatória.");
        }
        return new CommandLineOptions(input, output, overwrite, dryRun, help);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException("Valor ausente para " + option);
        }
        return args[index];
    }
}
