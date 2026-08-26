package org.salt.regnexe.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.Data;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loaded from ~/.rex/config.yml.
 * Missing file or missing fields fall back to built-in defaults.
 * ${ENV_VAR} placeholders in the YAML are replaced with environment variable values.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RexConfig {
    private String sessionId = "default";
    private ModelConfig model = new ModelConfig();
    private AgentConfig agent = new AgentConfig();
    private ToolsConfig tools = new ToolsConfig();
    private WorkspaceConfig workspace = new WorkspaceConfig();
    private UiConfig ui = new UiConfig();
    private SkillsConfig skills = new SkillsConfig();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelConfig {
        private String vendor = "deepseek";
        private String name = "deepseek-v4-pro";
        private String apiKey;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentConfig {
        private int maxRounds = 10;
        private int maxAgentIterations = 20;
        private int sessionBufferSize = 10;
        /**
         * Trigger threshold for the default (periodic/batch) session-memory compaction strategy:
         * once this many raw turns accumulate, they're compressed into the summary in one LLM
         * call and the buffer clears.
         */
        private int sessionCompactPeriod = 20;
        private int contextWindowSize = 8;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkspaceConfig {
        /** Root directories the agent is allowed to read. Defaults to the current working directory. */
        private List<String> dirs = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UiConfig {
        private String theme = "codex";
        private String color = "auto";
        private boolean icons = true;
        private boolean compact = true;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SkillsConfig {
        /**
         * Extra plugin-root directories to scan for skills, beyond the auto-discovered
         * {@code .rex/marketplaces/*&#47;plugins} (project) and {@code ~/.rex/marketplaces/*&#47;plugins}
         * (user). Each entry must directly contain plugin subfolders — same shape
         * DefaultPluginManager.addDirectory() expects (e.g. you can point this straight at
         * an existing Claude Code marketplace's {@code plugins/} folder without copying it).
         * {@code ~} is expanded to the user's home directory.
         */
        private List<String> extraDirs = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolsConfig {
        private BashConfig bash = new BashConfig();

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class BashConfig {
            private boolean requireConfirmation = true;
            private List<String> extraBlocked = new ArrayList<>();
        }
    }

    // ── Static factory ──────────────────────────────────────────────────────

    private static final Path CONFIG_PATH =
            Path.of(System.getProperty("user.home"), ".rex", "config.yml");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static RexConfig load() {
        if (!Files.exists(CONFIG_PATH)) {
            return new RexConfig();
        }
        try {
            String raw = Files.readString(CONFIG_PATH);
            String resolved = resolveEnvVars(raw);
            Yaml yaml = new Yaml();
            Map<?, ?> map = yaml.load(resolved);
            if (map == null) return new RexConfig();
            RexConfig cfg = MAPPER.convertValue(map, RexConfig.class);
            return cfg != null ? cfg : new RexConfig();
        } catch (IOException e) {
            System.err.println("[warn] Failed to load " + CONFIG_PATH + ": " + e.getMessage());
            return new RexConfig();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    private static String resolveEnvVars(String text) {
        Matcher m = ENV_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String val = System.getenv(var);
            if (val == null) val = System.getProperty(var, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Convenience: effective API key (env var REX_API_KEY overrides config file). */
    public String effectiveApiKey() {
        String fromEnv = System.getenv("REX_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return model.getApiKey();
    }

    /** Convenience: effective model name (env var REX_MODEL overrides config file). */
    public String effectiveModel() {
        String fromEnv = System.getenv("REX_MODEL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return model.getName();
    }
}
