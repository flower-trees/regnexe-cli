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
import org.salt.regnexe.cli.session.SessionContext;
import org.salt.regnexe.cli.session.SessionRow;
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
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@SpringBootApplication
public class CliMain implements CommandLineRunner {

    static final String VERSION = "0.1.0";

    private static final String DEFAULT_SESSION = "default";

    @Autowired
    private RegnexeAgentBuilder agentBuilder;

    public static void main(String[] args) {
        // Wire api_key from config → Spring @Value before context starts.
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

        // Parse --session argument
        String sessionArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--session".equals(args[i]) && i + 1 < args.length) {
                sessionArg = args[i + 1];
            }
        }

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

        SessionContext ctx = resolveSession(sessionArg, config, db);
        RegnexeAgent agent = buildAgent(ctx, config, terminal, db);

        out.printf("rex v%s  (type /help for commands, /exit to quit)%n", VERSION);
        out.printf("Model: %s/%s%n", config.getModel().getVendor(), config.effectiveModel());
        String apiKey = config.effectiveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            String envVar = vendorKeyEnvName(config.getModel().getVendor());
            out.printf("[warn] No API key — set %s or api_key in ~/.rex/config.yml%n", envVar);
        }
        ctx.getWorkspace().getRoots().forEach(r -> out.printf("Workspace: %s%n", r));
        out.printf("Session: %s%n", ctx.getSessionName());
        out.println();
        out.flush();

        while (true) {
            String input;
            try {
                input = reader.readLine("rex [" + ctx.getSessionName() + "]> ");
            } catch (UserInterruptException e) {
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            if (input.startsWith("/")) {
                SlashResult result = handleSlashCommand(input, out, config, terminal, agent, ctx, db);
                if (result == SlashResult.EXIT) break;
                if (result == SlashResult.AGENT_REBUILT) {
                    // ctx was mutated in-place by handleSlashCommand (/switch)
                    agent = buildAgent(ctx, config, terminal, db);
                }
                continue;
            }

            try {
                TaskRequest req = new TaskRequest();
                req.setGoal(input);
                req.setSessionId(ctx.getSessionName());
                AgentResult result = agent.execute(req);
                // TASK_TOKEN_SUMMARY fires via listener just before execute() returns.
                // Print the clean final answer after the token summary line.
                String answer = result.getFinalText();
                if (answer != null && !answer.isBlank()) {
                    out.println(answer);
                    out.flush();
                }
                if (db != null) {
                    try { db.touchSession(ctx.getSessionName()); } catch (Exception ignored) {}
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

    // ── Session resolution ────────────────────────────────────────────────────

    private SessionContext resolveSession(String nameArg, RexConfig config, RexDatabase db) {
        String sessionName = (nameArg != null && !nameArg.isBlank()) ? nameArg : DEFAULT_SESSION;
        if (db == null) {
            WorkspaceContext ws = buildWorkspaceFor(System.getProperty("user.dir"), config);
            return new SessionContext(UUID.randomUUID().toString(), sessionName, ws);
        }
        try {
            SessionRow row = db.findSessionByName(sessionName).orElse(null);
            if (row == null) {
                row = new SessionRow();
                row.setSessionId(UUID.randomUUID().toString());
                row.setName(sessionName);
                row.setWorkingDir(System.getProperty("user.dir"));
                row.setModel(config.effectiveModel());
                long now = System.currentTimeMillis();
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                db.upsertSession(row);
            }
            WorkspaceContext ws = buildWorkspaceFor(row.getWorkingDir(), config);
            return new SessionContext(row.getSessionId(), row.getName(), ws);
        } catch (SQLException e) {
            System.err.println("[warn] Session DB error: " + e.getMessage());
            WorkspaceContext ws = buildWorkspaceFor(System.getProperty("user.dir"), config);
            return new SessionContext(UUID.randomUUID().toString(), sessionName, ws);
        }
    }

    // ── Agent factory ────────────────────────────────────────────────────────

    private RegnexeAgent buildAgent(SessionContext ctx, RexConfig config, Terminal terminal, RexDatabase db) {
        RexConfig.AgentConfig ac = config.getAgent();
        WorkspaceContext workspace = ctx.getWorkspace();
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

    private WorkspaceContext buildWorkspaceFor(String primaryDir, RexConfig config) {
        List<Path> roots = new ArrayList<>();
        Path primary = Path.of(primaryDir).toAbsolutePath().normalize();
        if (Files.isDirectory(primary)) {
            roots.add(primary);
        }
        // Fallback: config-specified dirs, then cwd
        if (roots.isEmpty()) {
            List<String> configured = config.getWorkspace().getDirs();
            if (configured != null) {
                for (String d : configured) {
                    Path p = Path.of(d).toAbsolutePath().normalize();
                    if (Files.isDirectory(p)) roots.add(p);
                }
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
                                           RegnexeAgent agent,
                                           SessionContext ctx, RexDatabase db) {
        String[] parts = raw.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/exit", "/quit" -> { return SlashResult.EXIT; }

            case "/help" -> {
                out.println("""
                        Commands:
                          /help              show this help
                          /exit              exit rex
                          /sessions          list all sessions
                          /switch <name>     switch to a session (creates if new)
                          /clear             clear current session's conversation history

                        Coming soon:
                          /add-dir <path>    add a workspace directory
                          /dirs              list workspace directories
                          /pause             pause current task
                        """);
                out.flush();
            }

            case "/sessions" -> {
                if (db == null) {
                    out.println("  [warn] Database unavailable");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                try {
                    List<SessionRow> sessions = db.listSessions();
                    if (sessions.isEmpty()) {
                        out.println("  No sessions found.");
                    } else {
                        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                        out.printf("  %-20s  %-36s  %-19s  %s%n", "NAME", "ID", "LAST USED", "WORKING DIR");
                        out.println("  " + "─".repeat(100));
                        for (SessionRow row : sessions) {
                            String lastUsed = LocalDateTime
                                    .ofInstant(Instant.ofEpochMilli(row.getUpdatedAt()), ZoneId.systemDefault())
                                    .format(fmt);
                            String marker = row.getName().equals(ctx.getSessionName()) ? "* " : "  ";
                            out.printf("%s%-20s  %-36s  %-19s  %s%n",
                                    marker,
                                    row.getName(),
                                    row.getSessionId(),
                                    lastUsed,
                                    row.getWorkingDir());
                        }
                    }
                } catch (SQLException e) {
                    out.println("  [error] " + e.getMessage());
                }
                out.flush();
            }

            case "/switch" -> {
                String name = parts.length > 1 ? parts[1].trim() : "";
                if (name.isEmpty()) {
                    out.println("  Usage: /switch <name>");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                if (name.equals(ctx.getSessionName())) {
                    out.println("  Already in session: " + name);
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                if (db == null) {
                    out.println("  [warn] Database unavailable — cannot persist session");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                try {
                    SessionRow row = db.findSessionByName(name).orElse(null);
                    if (row == null) {
                        row = new SessionRow();
                        row.setSessionId(UUID.randomUUID().toString());
                        row.setName(name);
                        row.setWorkingDir(System.getProperty("user.dir"));
                        row.setModel(config.effectiveModel());
                        long now = System.currentTimeMillis();
                        row.setCreatedAt(now);
                        row.setUpdatedAt(now);
                        db.upsertSession(row);
                        out.printf("  Created new session: %s%n", name);
                    } else {
                        out.printf("  Resumed session: %s%n", name);
                    }
                    WorkspaceContext ws = buildWorkspaceFor(row.getWorkingDir(), config);
                    // Mutate ctx in-place — main loop rebuilds agent after this returns AGENT_REBUILT.
                    ctx.setSessionId(row.getSessionId());
                    ctx.setSessionName(row.getName());
                    ctx.setWorkspace(ws);
                    out.printf("  Workspace: %s%n", ws.primaryRoot());
                } catch (SQLException e) {
                    out.println("  [error] " + e.getMessage());
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                out.flush();
                return SlashResult.AGENT_REBUILT;
            }

            case "/clear" -> {
                if (db == null) {
                    out.println("  [warn] Database unavailable");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                try {
                    db.clearConversation(ctx.getSessionName());
                    out.printf("  Cleared conversation history for session: %s%n", ctx.getSessionName());
                } catch (SQLException e) {
                    out.println("  [error] " + e.getMessage());
                }
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
