import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.salt.regnexe.cli.CliMain;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.marketplace.scope.Scope;
import org.salt.regnexe.agent.core.task.state.capability.SearchQuery;

/** Offline probe of the actual packaged loader. Never calls main(), build(), or a tool. */
public class SkillInventoryProbe {
    private static final String TARGET = "robot-description-style";

    static class Registry extends SimpleMarketplace {
        final List<Map<String, Object>> attempts = new ArrayList<>();
        @Override public void install(PluginDescriptor plugin) {
            var attempt = new LinkedHashMap<String, Object>();
            attempt.put("plugin_id", plugin.getPluginId());
            try { super.install(plugin); attempt.put("registered", true); }
            catch (RuntimeException error) {
                attempt.put("registered", false); attempt.put("error", error.getMessage()); throw error;
            } finally { attempts.add(attempt); }
        }
    }
    static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    static String origin(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
    static List<String> candidates(Registry registry) {
        var query = new SearchQuery(); query.setGoal("规范产品描述"); query.setTopK(50);
        return registry.search(query).getCandidates().stream().map(c -> c.getCapabilityId()).toList();
    }
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toRealPath();
        Path result = Path.of(args[1]);
        var config = RexConfig.load();
        if (config.getOpik().isEnabled()) throw new IllegalStateException("Probe requires Opik disabled");
        // Reuse CLI directory resolution without starting Spring, MCP, a database or an Agent.
        var cli = new CliMain();
        try {
            Method resolve = CliMain.class.getDeclaredMethod("resolveSkillDirectories", WorkspaceContext.class, RexConfig.class);
            resolve.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> dirs = (List<String>) resolve.invoke(cli, new WorkspaceContext(List.of(workspace)), config);
            var registry = new Registry();
            // These builder operations only register descriptors and apply enabled.yml.
            // Do NOT call builder.build(): that is outside the P01 loading probe.
            var builder = new RegnexeAgentBuilder(null, null).withPluginMarket(registry)
                    .withSkillsDirectory(dirs.toArray(String[]::new));
            var scopeFiles = Map.of(Scope.USER, Path.of(System.getProperty("user.home"), ".rex/enabled.yml"),
                    Scope.PROJECT, workspace.resolve(".rex/enabled.yml"));
            builder.withEnabledState(scopeFiles, List.of(Scope.USER, Scope.PROJECT));
            var inventory = new ArrayList<Map<String, Object>>();
            var names = new TreeMap<String, List<String>>();
            for (PluginDescriptor plugin : registry.listEnabled()) {
                for (CapabilityDescriptor cap : plugin.getCapabilities()) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("plugin_id", plugin.getPluginId()); row.put("capability_id", cap.getCapabilityId());
                    row.put("name", cap.getName()); row.put("description", cap.getDescription());
                    row.put("enabled", true); row.put("type", cap.getType().toString());
                    String body = cap.getSkillConfig().getSystemPrompt();
                    row.put("body_chars", body.length()); row.put("body_sha256", hash(body));
                    row.put("reference_count", cap.getSkillConfig().getReferences().size());
                    row.put("script_count", cap.getSkillConfig().getScripts().size());
                    inventory.add(row);
                    names.computeIfAbsent(cap.getName(), ignored -> new ArrayList<>()).add(cap.getCapabilityId());
                }
            }
            List<String> initialCandidates = candidates(registry);
            var target = registry.resolveDescriptor(TARGET);
            if (target == null) throw new IllegalStateException("Target skill did not load");
            Path projectState = scopeFiles.get(Scope.PROJECT);
            String original = Files.readString(projectState);
            boolean disabled, restored;
            try {
                Files.writeString(projectState, TARGET + ": false\n");
                builder.withEnabledState(scopeFiles, List.of(Scope.USER, Scope.PROJECT));
                disabled = registry.resolveDescriptor(TARGET) == null && !candidates(registry).contains(TARGET);
                Files.writeString(projectState, TARGET + ": true\n");
                builder.withEnabledState(scopeFiles, List.of(Scope.USER, Scope.PROJECT));
                restored = registry.resolveDescriptor(TARGET) != null && candidates(registry).contains(TARGET);
            } finally { Files.writeString(projectState, original); }
            if (!disabled || !restored) throw new IllegalStateException("Enabled-state control failed");
            var output = new LinkedHashMap<String, Object>();
            output.put("scope", "isolated skill registration only; no base tools or MCP clients");
            output.put("user_home", System.getProperty("user.home")); output.put("workspace", workspace.toString());
            output.put("skill_directories", dirs); output.put("inventory", inventory);
            output.put("registration_attempts", registry.attempts);
            output.put("initial_candidate_ids", initialCandidates);
            output.put("duplicate_short_names", names.entrySet().stream().filter(e -> e.getValue().size() > 1)
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
            output.put("enabled_control", Map.of("disabled_target_absent", disabled, "restored_target_present", restored,
                    "original_project_state_restored", Files.readString(projectState).equals(original)));
            output.put("class_origins", Map.of("cli", origin(CliMain.class), "agent", origin(RegnexeAgentBuilder.class),
                    "skill_loader", origin(org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader.class)));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(result.toFile(), output);
        } finally {
            var tasks = CliMain.class.getDeclaredField("liveTasks"); tasks.setAccessible(true);
            ((AutoCloseable) tasks.get(cli)).close();
        }
    }
}
