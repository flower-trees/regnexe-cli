package org.salt.regnexe.cli.ui;

public enum CliTheme {
    MINIMAL,
    CODEX;

    public static CliTheme from(String value) {
        if (value == null || value.isBlank()) return CODEX;
        return switch (value.trim().toLowerCase()) {
            case "minimal" -> MINIMAL;
            default -> CODEX;
        };
    }
}
