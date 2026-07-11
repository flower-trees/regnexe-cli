package org.salt.regnexe.cli.ui;

import org.jline.terminal.Terminal;
import org.salt.regnexe.cli.config.RexConfig;

public record ThemeConfig(
        CliTheme theme,
        ColorMode color,
        boolean icons,
        boolean compact,
        boolean colorEnabled
) {
    public static ThemeConfig from(RexConfig.UiConfig config, Terminal terminal) {
        CliTheme theme = CliTheme.from(config != null ? config.getTheme() : null);
        ColorMode color = ColorMode.from(config != null ? config.getColor() : null);
        boolean icons = config == null || config.isIcons();
        boolean compact = config == null || config.isCompact();
        boolean tty = terminal != null && !"dumb".equalsIgnoreCase(terminal.getType());
        boolean colorEnabled = color == ColorMode.ALWAYS || (color == ColorMode.AUTO && tty);
        return new ThemeConfig(theme, color, icons, compact, colorEnabled);
    }
}
