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
        /**
         * Chat-completions endpoint URL. Only meaningful for {@code vendor: custom} (any
         * OpenAI-Chat-Completions-compatible endpoint not covered by a named vendor —
         * OpenRouter, Together, Groq, a self-hosted server, a corporate gateway); every named
         * vendor already has its own built-in URL and ignores this field. Unlike every other
         * vendor, "custom" has no sensible default endpoint to fall back to, so this is
         * effectively required when vendor is custom — see CliMain's startup wiring
         * (models.custom.chat-url) and j-langchain's CustomActuator.
         */
        private String chatUrl;
        /**
         * Optional per-role model override: run TaskPlanner's planning call on a different
         * model than Execute (which stays on {@code name}) — e.g. a pricier/stronger tier for
         * the low-volume Plan/Reflect judgment calls, a cheaper one for the tool-calling loop
         * whose cost scales with iteration count. Unset means Planner uses the main model too.
         */
        private String plannerName;
        /**
         * Optional vendor override for {@link #plannerName} — set this when Planner should run
         * on a genuinely different vendor, not just a different tier of the same one (e.g. a
         * stronger model only available elsewhere). Defaults to the main {@link #vendor} when
         * {@link #plannerName} is set but this isn't.
         */
        private String plannerVendor;
        /**
         * Optional API key for {@link #plannerVendor}. Only meaningful when plannerVendor names
         * a genuinely different vendor than the main one — defaults to the main {@link #apiKey}
         * otherwise (same vendor implies the same key works). Resolved the same
         * {@code ${ENV_VAR}} way as {@link #apiKey}.
         */
        private String plannerApiKey;
        /**
         * Optional per-role model override for Reflector's FINISH/CONTINUE/ESCALATE judgment —
         * see {@link #plannerName}. A wrong FINISH verdict is a one-way door (the task ends, no
         * later round can catch it), unlike a Planner or Execute mistake, so judgment quality
         * here has outsized leverage relative to this call's own small cost.
         */
        private String reflectorName;
        /** Optional vendor override for {@link #reflectorName} — see {@link #plannerVendor}. */
        private String reflectorVendor;
        /** Optional API key for {@link #reflectorVendor} — see {@link #plannerApiKey}. */
        private String reflectorApiKey;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentConfig {
        private int maxRounds = 10;
        private int maxAgentIterations = 20;
        /**
         * Caps consecutive tool-call failures before a round aborts early with a diagnostic,
         * instead of grinding through the rest of maxAgentIterations retrying the same broken
         * dependency (e.g. a real external API repeatedly rejecting credentials). 0 disables the
         * check (j-langchain's own default) — set here because regnexe never wired this through
         * before, so it silently ran disabled regardless of what a project configured.
         */
        private int maxConsecutiveToolFailures = 5;
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

    /** Effective vendor for the Planner role — plannerVendor, falling back to the main vendor. */
    public String effectivePlannerVendor() {
        String v = model.getPlannerVendor();
        return v != null && !v.isBlank() ? v : model.getVendor();
    }

    /**
     * Effective API key for the Planner role. Only falls back to the main {@link #effectiveApiKey()}
     * when plannerVendor is unset or matches the main vendor — a genuinely different
     * plannerVendor with no plannerApiKey of its own is very likely a real misconfiguration
     * (that vendor's real key almost never happens to also be a valid key for the main vendor),
     * so surfacing null lets the caller warn instead of silently sending the wrong vendor's key.
     */
    public String effectivePlannerApiKey() {
        String k = model.getPlannerApiKey();
        if (k != null && !k.isBlank()) return k;
        String plannerVendor = model.getPlannerVendor();
        boolean sameVendor = plannerVendor == null || plannerVendor.isBlank()
                || plannerVendor.equalsIgnoreCase(model.getVendor());
        return sameVendor ? effectiveApiKey() : null;
    }

    /** Effective vendor for the Reflector role — see {@link #effectivePlannerVendor()}. */
    public String effectiveReflectorVendor() {
        String v = model.getReflectorVendor();
        return v != null && !v.isBlank() ? v : model.getVendor();
    }

    /** Effective API key for the Reflector role — see {@link #effectivePlannerApiKey()}. */
    public String effectiveReflectorApiKey() {
        String k = model.getReflectorApiKey();
        if (k != null && !k.isBlank()) return k;
        String reflectorVendor = model.getReflectorVendor();
        boolean sameVendor = reflectorVendor == null || reflectorVendor.isBlank()
                || reflectorVendor.equalsIgnoreCase(model.getVendor());
        return sameVendor ? effectiveApiKey() : null;
    }
}
