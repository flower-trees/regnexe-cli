package org.salt.regnexe.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.salt.jlangchain.core.agent.memory.SlidingWindowContext;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.cli.config.RexConfig;
import org.salt.regnexe.cli.db.RexDatabase;
import org.salt.regnexe.cli.db.SqliteConversationStorage;
import org.salt.regnexe.cli.db.SqliteTaskStore;
import org.salt.regnexe.cli.event.CliEventListener;
import org.salt.regnexe.cli.tools.BashTool;
import org.salt.regnexe.cli.tools.FileTools;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class CliMain implements CommandLineRunner {

    static final String VERSION = "0.1.0";

    @Autowired
    private RegnexeAgentBuilder agentBuilder;

    public static void main(String[] args) {
        // Wire api_key from config → Spring @Value before context starts.
        // j-langchain actuators read: models.<vendor>.chat-key or <VENDOR>_KEY env var.
        RexConfig preConfig = RexConfig.load();
        String apiKey = preConfig.effectiveApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            String prop = apiKeyPropFor(preConfig.getModel().getVendor());
            if (prop != null) {
                System.setProperty(prop, apiKey);
            }
        }

        SpringApplication app = new SpringApplication(CliMain.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    /** Maps vendor name → j-langchain Spring property key for API key injection. */
    static String apiKeyPropFor(String vendor) {
        return apiKeyMapFor(vendor, false);
    }

    /** Returns the conventional env var name to show to the user in warnings. */
    static String vendorKeyEnvName(String vendor) {
        return apiKeyMapFor(vendor, true);
    }

    private static String apiKeyMapFor(String vendor, boolean envName) {
        if (vendor == null) return null;
        return switch (vendor.toLowerCase()) {
            case "aliyun"            -> envName ? "ALIYUN_KEY"   : "models.aliyun.chat-key";
            case "deepseek"          -> envName ? "DEEPSEEK_KEY" : "models.deepseek.chat-key";
            case "doubao"            -> envName ? "DOUBAO_KEY"   : "models.doubao.chat-key";
            case "hunyuan"           -> envName ? "HUNYUAN_KEY"  : "models.hunyuan.chat-key";
            case "lingyi"            -> envName ? "LINGYI_KEY"   : "models.lingyi.chat-key";
            case "minimax"           -> envName ? "MINIMAX_KEY"  : "models.minimax.chat-key";
            case "moonshot"          -> envName ? "MOONSHOT_KEY" : "models.moonshot.chat-key";
            case "openai", "chatgpt" -> envName ? "CHATGPT_KEY"  : "models.chatgpt.chat-key";
            case "qianfan"           -> envName ? "QIANFAN_KEY"  : "models.qianfan.chat-key";
            case "stepfun"           -> envName ? "STEPFUN_KEY"  : "models.stepfun.chat-key";
            case "zhipu"             -> envName ? "ZHIPU_KEY"    : "models.zhipu.chat-key";
            default                  -> null;
        };
    }

    @Override
    public void run(String... args) throws Exception {
        RexConfig config = RexConfig.load();

        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true)
                .build();

        Path historyFile = Path.of(System.getProperty("user.home"), ".rex", "history");
        Files.createDirectories(historyFile.getParent());

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .variable(LineReader.HISTORY_FILE, historyFile)
                .build();

        PrintWriter out = terminal.writer();

        RexDatabase db;
        try {
            db = new RexDatabase();
        } catch (Exception e) {
            out.println("[warn] SQLite unavailable, falling back to in-memory: " + e.getMessage());
            db = null;
        }

        WorkspaceContext workspace = buildWorkspace(config);
        RegnexeAgent agent = buildAgent(config, terminal, db, workspace);
        // Use "default" as session name so history persists across runs
        String sessionId = "default";

        out.printf("rex v%s  (type /help for commands, /exit to quit)%n", VERSION);
        out.printf("Model: %s/%s%n", config.getModel().getVendor(), config.effectiveModel());
        String apiKey = config.effectiveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            String envVar = vendorKeyEnvName(config.getModel().getVendor());
            out.printf("[warn] No API key — set %s or api_key in ~/.rex/config.yml%n", envVar);
        }
        workspace.getRoots().forEach(r -> out.printf("Workspace: %s%n", r));
        out.println();
        out.flush();

        while (true) {
            String input;
            try {
                input = reader.readLine("rex> ");
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            if (input.startsWith("/")) {
                SlashResult result = handleSlashCommand(input, out, config, terminal, agent);
                if (result == SlashResult.EXIT) break;
                if (result == SlashResult.AGENT_REBUILT) {
                    agent = buildAgent(config, terminal, db, workspace);
                    sessionId = "default";
                }
                continue;
            }

            try {
                TaskRequest req = new TaskRequest();
                req.setGoal(input);
                req.setSessionId(sessionId);
                AgentResult result = agent.execute(req);
                // TASK_TOKEN_SUMMARY fires via listener just before execute() returns.
                // Print the clean final answer after the token summary line.
                String answer = result.getFinalText();
                if (answer != null && !answer.isBlank()) {
                    out.println(answer);
                    out.flush();
                }
            } catch (Exception e) {
                out.println("  [error] " + e.getMessage());
                out.flush();
            }
        }

        out.println("Goodbye!");
        out.flush();
        terminal.close();
        if (db != null) {
            try { db.close(); } catch (Exception ignored) {}
        }
    }

    // ── Agent factory ────────────────────────────────────────────────────────

    private RegnexeAgent buildAgent(RexConfig config, Terminal terminal, RexDatabase db, WorkspaceContext workspace) {
        RexConfig.AgentConfig ac = config.getAgent();
        var builder = agentBuilder
                .withDefaultModel(config.getModel().getVendor(), config.effectiveModel())
                .withEventListener(new CliEventListener(terminal))
                .withMaxRounds(ac.getMaxRounds())
                .withMaxAgentIterations(ac.getMaxAgentIterations())
                .withSessionBufferSize(ac.getSessionBufferSize())
                .withAgentContext(SlidingWindowContext.builder()
                        .windowSize(ac.getContextWindowSize())
                        .build())
                .withTool(
                        FileTools.readFile(workspace),
                        FileTools.listFiles(workspace),
                        FileTools.searchFiles(workspace),
                        FileTools.writeFile(workspace, terminal),
                        FileTools.editFile(workspace, terminal),
                        BashTool.bash(workspace, config.getTools().getBash(), terminal)
                );
        if (db != null) {
            builder = builder.withSessionStorage(new SqliteConversationStorage(db));
            builder = builder.withTaskStore(new SqliteTaskStore(db));
        }
        return builder.build();
    }

    private WorkspaceContext buildWorkspace(RexConfig config) {
        List<String> configured = config.getWorkspace().getDirs();
        List<Path> roots = new ArrayList<>();
        if (configured != null) {
            for (String d : configured) {
                Path p = Path.of(d).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) roots.add(p);
            }
        }
        if (roots.isEmpty()) {
            roots.add(Path.of("").toAbsolutePath());
        }
        return new WorkspaceContext(roots);
    }

    // ── Slash command dispatcher ─────────────────────────────────────────────

    private enum SlashResult { CONTINUE, EXIT, AGENT_REBUILT }

    private SlashResult handleSlashCommand(String raw, PrintWriter out,
                                           RexConfig config, Terminal terminal,
                                           RegnexeAgent agent) {
        String[] parts = raw.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/exit", "/quit" -> { return SlashResult.EXIT; }

            case "/help" -> {
                out.println("""
                        Commands:
                          /help              show this help
                          /exit              exit rex

                        Coming soon:
                          /add-dir <path>    add a workspace directory
                          /dirs              list workspace directories
                          /sessions          list sessions
                          /switch <name>     switch session
                          /clear             clear current session history
                          /pause             pause current task
                        """);
                out.flush();
            }

            default -> {
                out.println("Unknown command: " + cmd + "  (type /help for available commands)");
                out.flush();
            }
        }
        return SlashResult.CONTINUE;
    }
}
