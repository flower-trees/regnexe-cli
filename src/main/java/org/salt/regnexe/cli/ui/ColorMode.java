package org.salt.regnexe.cli.ui;

public enum ColorMode {
    AUTO,
    ALWAYS,
    NEVER;

    public static ColorMode from(String value) {
        if (value == null || value.isBlank()) return AUTO;
        return switch (value.trim().toLowerCase()) {
            case "always" -> ALWAYS;
            case "never" -> NEVER;
            default -> AUTO;
        };
    }
}
