package org.salt.regnexe.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.agent.memory.SlidingWindowContext;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
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

import java.io.IOException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootApplication
public class CliMain implements CommandLineRunner {

    static final String VERSION = "0.1.0";

    private static final String DEFAULT_SESSION = "default";

    @Autowired
    private RegnexeAgentBuilder agentBuilder;

    public static void main(String[] args) {
        if (hasArg(args, "--help") || hasArg(args, "-h")) {
            printUsage();
            return;
        }
        if (hasArg(args, "--version") || hasArg(args, "-v")) {
            System.out.println("rex v" + VERSION);
            return;
        }

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

    private static boolean hasArg(String[] args, String expected) {
        for (String arg : args) {
            if (expected.equals(arg)) return true;
        }
        return false;
    }

    private static void printUsage() {
        System.out.printf("""
                rex v%s

                Usage:
                  rex [--session <name>]
                  rex --resume <session-id>
                  rex --help
                  rex --version

                Environment:
                  REX_API_KEY   API key override for the configured model vendor
                  REX_MODEL     model name override
                  JAVA_OPTS     JVM options used by the installed wrapper
                """, VERSION);
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

        // Parse --session / --resume arguments
        String sessionArg = null;
        String resumeArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--session".equals(args[i]) && i + 1 < args.length) {
                sessionArg = args[i + 1];
            } else if ("--resume".equals(args[i]) && i + 1 < args.length) {
                resumeArg = args[i + 1];
                // --resume implies --session with the same name
                if (sessionArg == null) sessionArg = resumeArg;
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
            // On Ctrl+C / SIGTERM, mark in-flight RUNNING tasks as PAUSED so --resume works.
            final RexDatabase dbRef = db;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { dbRef.markAllRunningAsPaused(); } catch (Exception ignored) {}
                try { dbRef.close(); } catch (Exception ignored) {}
            }, "rex-shutdown"));
        } catch (Exception e) {
            out.println("[warn] SQLite unavailable, falling back to in-memory: " + e.getMessage());
            db = null;
        }

        // AtomicReference lets the pauseAction lambda always call the most recent agent instance,
        // even after /switch rebuilds the agent.
        AtomicReference<RegnexeAgent> agentRef = new AtomicReference<>();
        AtomicBoolean executing = new AtomicBoolean(false);
        AtomicBoolean exitRequested = new AtomicBoolean(false);
        AtomicInteger interruptCount = new AtomicInteger(0);
        Thread mainThread = Thread.currentThread();
        Runnable pauseAction = () -> {
            RegnexeAgent a = agentRef.get();
            if (a != null) a.pause();
        };
        terminal.handle(Terminal.Signal.INT, signal -> {
            if (!executing.get()) {
                exitRequested.set(true);
                mainThread.interrupt();
                return;
            }

            int count = interruptCount.incrementAndGet();
            if (count == 1) {
                out.println();
                out.println("  Interrupt received. Pausing current task...");
                out.flush();
                pauseAction.run();
            } else {
                out.println();
                out.println("  Second interrupt received. Exiting.");
                out.flush();
                System.exit(130);
            }
        });

        SessionContext ctx = resolveSession(sessionArg, config, db);
        RegnexeAgent agent = buildAgent(ctx, config, terminal, db, pauseAction);
        agentRef.set(agent);

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

        // --resume: kick off the paused task immediately before entering the REPL loop
        if (resumeArg != null) {
            out.printf("Resuming paused task for session: %s%n%n", ctx.getSessionName());
            out.flush();
            try {
                RegnexeAgent resumeAgent = agent;
                AgentResult r = runAgentTask(
                        () -> resumeAgent.resume(ctx.getSessionName(), null),
                        ctx,
                        out,
                        executing,
                        interruptCount);
                handleAgentResult(r, ctx, out, db);
            } catch (IllegalStateException e) {
                out.println("[warn] " + e.getMessage());
                out.flush();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitRequested.set(true);
            }
        }

        while (!exitRequested.get()) {
            String input;
            try {
                input = reader.readLine("rex [" + ctx.getSessionName() + "]> ");
            } catch (UserInterruptException e) {
                break;
            } catch (EndOfFileException e) {
                break;
            }

            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            boolean resumeRequested = false;
            String resumeSupplement = null;
            if (input.startsWith("/")) {
                if (input.equals("/resume") || input.startsWith("/resume ")
                        || input.equals("/continue") || input.startsWith("/continue ")) {
                    resumeRequested = true;
                    resumeSupplement = extractSlashArgument(input);
                } else {
                    SlashResult result = handleSlashCommand(input, out, config, terminal, agent, ctx, db);
                    if (result == SlashResult.EXIT) break;
                    if (result == SlashResult.AGENT_REBUILT) {
                        // ctx was mutated in-place by handleSlashCommand (/switch)
                        agent = buildAgent(ctx, config, terminal, db, pauseAction);
                        agentRef.set(agent);
                    } else if (result.kind == SlashResult.Kind.RUN_SKILL) {
                        RegnexeAgent taskAgent = agent;
                        String capId = result.capabilityId;
                        String skillArgs = result.args;
                        String displayGoal = result.rawInput;
                        try {
                            AgentResult skillResult = runAgentTask(
                                    () -> taskAgent.executeSkill(capId, skillArgs, ctx.getSessionName(), displayGoal),
                                    ctx, out, executing, interruptCount);
                            handleAgentResult(skillResult, ctx, out, db);
                            if (db != null) {
                                try { db.touchSession(ctx.getSessionName()); } catch (Exception ignored) {}
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (Exception e) {
                            out.println("  [error] " + e.getMessage());
                            out.flush();
                        }
                    }
                    continue;
                }
            }

            try {
                AgentResult result;
                RegnexeAgent taskAgent = agent;
                if (resumeRequested) {
                    String supplement = resumeSupplement;
                    result = runAgentTask(
                            () -> taskAgent.resume(ctx.getSessionName(), supplement),
                            ctx,
                            out,
                            executing,
                            interruptCount);
                } else {
                    TaskRequest req = new TaskRequest();
                    req.setGoal(injectWorkspacePreamble(input, ctx));
                    req.setSessionId(ctx.getSessionName());
                    result = runAgentTask(
                            () -> taskAgent.execute(req),
                            ctx,
                            out,
                            executing,
                            interruptCount);
                }
                handleAgentResult(result, ctx, out, db);
                if (db != null) {
                    try { db.touchSession(ctx.getSessionName()); } catch (Exception ignored) {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                out.println("  [error] " + e.getMessage());
                out.flush();
            }
        }

        out.println("Goodbye!");
        out.flush();
        terminal.close();
        // db.close() is handled by the shutdown hook registered above, which also marks
        // any RUNNING tasks as PAUSED. Don't double-close here.
    }

    private AgentResult runAgentTask(Callable<AgentResult> task,
                                     SessionContext ctx,
                                     PrintWriter out,
                                     AtomicBoolean executing,
                                     AtomicInteger interruptCount) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "rex-agent-worker");
            t.setDaemon(true);
            return t;
        });
        executing.set(true);
        interruptCount.set(0);
        Future<AgentResult> future = executor.submit(task);
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } finally {
            executing.set(false);
            interruptCount.set(0);
            executor.shutdown();
        }
    }

    private String extractSlashArgument(String input) {
        String[] parts = input.split("\\s+", 2);
        return parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : null;
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

    // ── Result handling ───────────────────────────────────────────────────────

    private void handleAgentResult(AgentResult result, SessionContext ctx, PrintWriter out, RexDatabase db) {
        // TASK_TOKEN_SUMMARY fires via listener just before execute()/resume() returns.
        // Print the clean final answer after the token summary line.
        String answer = result.getFinalText();
        if (answer != null && !answer.isBlank()) {
            out.println(answer);
        }
        if (result.getStatus() == TaskStatus.PAUSED) {
            storePausedTaskSummary(result, db);
            out.println();
            out.printf("  Task paused. Resume with: /resume or rex --resume %s%n", ctx.getSessionName());
        }
        out.flush();
    }

    private void storePausedTaskSummary(AgentResult result, RexDatabase db) {
        if (db == null || result.getState() == null || result.getState().getRequest() == null) return;

        TaskExecutionState state = result.getState();
        TaskRequest request = state.getRequest();
        String goal = request.getDisplayGoal();
        if (goal == null || goal.isBlank()) goal = request.getGoal();
        if (goal == null || goal.isBlank()) return;

        StringBuilder summary = new StringBuilder();
        summary.append("[Task paused]\n");
        summary.append("Task id: ").append(state.getTaskId()).append("\n");
        summary.append("Original request: ").append(goal).append("\n");
        summary.append("Current round: ").append(state.getCurrentRound())
                .append(" of ").append(state.getMaxRounds()).append("\n");
        String partial = latestExecutionText(state);
        if (partial != null && !partial.isBlank()) {
            summary.append("Partial result: ").append(truncate(partial, 800)).append("\n");
        }
        summary.append("Use /resume to continue this paused task.");

        HistoryInfos turn = HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .messages(List.of(
                        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), goal),
                        BaseMessage.fromMessage(MessageType.AI.getCode(), summary.toString())
                ))
                .build();
        try {
            long sessionKey = (long) state.getSessionId().hashCode();
            new SqliteConversationStorage(db).append(0L, 0L, sessionKey, turn);
        } catch (Exception ignored) {
            // Paused task persistence lives in task_store; session summary is best-effort context.
        }
    }

    private String latestExecutionText(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        if (rounds == null) return null;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            RoundRecord round = rounds.get(i);
            if (round.getExecutionResult() == null) continue;
            String text = round.getExecutionResult().getFinalText();
            if (text != null && !text.isBlank()) return text;
            text = round.getExecutionResult().getPartialContext();
            if (text != null && !text.isBlank()) return text;
        }
        return state.getLastToolResult();
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    // ── Goal injection ────────────────────────────────────────────────────────

    /**
     * When multiple workspace roots exist, prepend a preamble so the LLM knows where to look.
     * Single-root sessions get no preamble — no noise.
     */
    private String injectWorkspacePreamble(String goal, SessionContext ctx) {
        List<Path> roots = ctx.getWorkspace().getRoots();
        if (roots.size() <= 1) return goal;
        StringBuilder sb = new StringBuilder("[Workspace roots available for this task:\n");
        sb.append(ctx.getWorkspace().describeRoots());
        sb.append("Use these roots when resolving relative paths.]\n\n");
        sb.append(goal);
        return sb.toString();
    }

    // ── Agent factory ────────────────────────────────────────────────────────

    private RegnexeAgent buildAgent(SessionContext ctx, RexConfig config, Terminal terminal,
                                    RexDatabase db, Runnable pauseAction) {
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
                        FileTools.writeFile(workspace, terminal, pauseAction),
                        FileTools.editFile(workspace, terminal, pauseAction),
                        BashTool.bash(workspace, config.getTools().getBash(), terminal, pauseAction)
                )
                // Project marketplaces first, then user marketplaces, then configured extra_dirs,
                // so earlier entries win on an identically-named plugin (DefaultPluginManager
                // degrades pluginId collisions to a skip-with-warning in scan order — see
                // docs/design/skill-slash-invocation.md).
                .withDirectory(resolveSkillDirectories(workspace, config).toArray(new String[0]))
                // Claude-compatible mode's scoped filesystem tools (create_directory/write_file/
                // read_file/list_directory/file_exists) get this as their sandbox root, instead of
                // an anonymous throwaway temp dir — so a skill-authoring skill (skill-creator) can
                // see and edit skills that already exist here, and anything it creates lands where
                // /skills will actually find it. Scoped to the plugins dir specifically (not the
                // whole project) so a claude-compat skill never gets read/write access to source
                // code, .git, .env, etc. — only to skill/plugin content.
                .withClaudeCompatWorkspace(workspace.primaryRoot().resolve(".rex/marketplaces/default/plugins"));
        if (db != null) {
            builder = builder.withSessionStorage(new SqliteConversationStorage(db));
            builder = builder.withTaskStore(new SqliteTaskStore(db));
        }
        return builder.build();
    }

    /**
     * Skill plugin directories to scan, in priority order (earlier wins on pluginId collision):
     * <ol>
     *   <li>every {@code <name>/plugins} under the project's {@code .rex/marketplaces/}</li>
     *   <li>every {@code <name>/plugins} under the user's {@code ~/.rex/marketplaces/}</li>
     *   <li>{@code skills.extra_dirs} from {@code ~/.rex/config.yml}, in listed order</li>
     * </ol>
     * Marketplace names are just directory names — drop a new {@code <marketplace>/plugins/<plugin>/}
     * tree under either root and it's picked up automatically, no config needed.
     */
    private List<String> resolveSkillDirectories(WorkspaceContext workspace, RexConfig config) {
        List<String> dirs = new ArrayList<>();
        dirs.addAll(listMarketplacePluginDirs(workspace.primaryRoot().resolve(".rex/marketplaces")));
        dirs.addAll(listMarketplacePluginDirs(Path.of(System.getProperty("user.home"), ".rex/marketplaces")));
        List<String> extra = config.getSkills().getExtraDirs();
        if (extra != null) {
            for (String d : extra) {
                if (d != null && !d.isBlank()) dirs.add(expandHome(d.trim()));
            }
        }
        return dirs;
    }

    /** Each subdirectory of {@code marketplacesRoot} is a named marketplace; its plugins live under {@code <name>/plugins}. */
    private List<String> listMarketplacePluginDirs(Path marketplacesRoot) {
        if (!Files.isDirectory(marketplacesRoot)) return List.of();
        try (var subdirs = Files.list(marketplacesRoot)) {
            return subdirs.filter(Files::isDirectory)
                    .sorted()
                    .map(p -> p.resolve("plugins").toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
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

    /**
     * Class rather than a plain enum so RUN_SKILL can carry the resolved capabilityId/args/
     * displayGoal alongside it. CONTINUE/EXIT/AGENT_REBUILT stay singletons, so every existing
     * {@code return SlashResult.CONTINUE;}-style call site and {@code == SlashResult.EXIT} check
     * keeps working unchanged.
     */
    private static final class SlashResult {
        enum Kind { CONTINUE, EXIT, AGENT_REBUILT, RUN_SKILL }

        static final SlashResult CONTINUE = new SlashResult(Kind.CONTINUE, null, null, null);
        static final SlashResult EXIT = new SlashResult(Kind.EXIT, null, null, null);
        static final SlashResult AGENT_REBUILT = new SlashResult(Kind.AGENT_REBUILT, null, null, null);

        final Kind kind;
        final String capabilityId;
        final String args;
        final String rawInput;

        private SlashResult(Kind kind, String capabilityId, String args, String rawInput) {
            this.kind = kind;
            this.capabilityId = capabilityId;
            this.args = args;
            this.rawInput = rawInput;
        }

        static SlashResult runSkill(String capabilityId, String args, String rawInput) {
            return new SlashResult(Kind.RUN_SKILL, capabilityId, args, rawInput);
        }
    }

    /** One discovered SKILL capability, keyed by both its short name and full capabilityId. */
    private record SkillMatch(String capabilityId, String shortName, String description) {}

    /** Scans the agent's marketplace for SKILL capabilities. */
    private List<SkillMatch> listSkills(RegnexeAgent agent) {
        List<SkillMatch> out = new ArrayList<>();
        for (PluginDescriptor plugin : agent.getMarketplace().listEnabled()) {
            for (CapabilityDescriptor cap : plugin.getCapabilities()) {
                if (cap.getType() != CapabilityType.SKILL) continue;
                out.add(new SkillMatch(cap.getCapabilityId(), cap.getName(), cap.getDescription()));
            }
        }
        return out;
    }

    /**
     * Resolves a user-typed name (short name or full "pluginId.skillName" capabilityId) to
     * exactly one capabilityId. Returns null both when nothing matches (caller falls through to
     * "Unknown command") and when the short name is ambiguous (caller has already printed the
     * candidate list and should not treat it as an unknown command).
     */
    private String resolveSkillId(String typed, List<SkillMatch> all, PrintWriter out) {
        for (SkillMatch m : all) {
            if (m.capabilityId().equals(typed)) return m.capabilityId();
        }
        List<SkillMatch> byShortName = all.stream().filter(m -> m.shortName().equals(typed)).toList();
        if (byShortName.size() == 1) return byShortName.get(0).capabilityId();
        if (byShortName.size() > 1) {
            out.println("  [warn] Ambiguous skill name '" + typed + "', candidates:");
            byShortName.forEach(m -> out.println("    " + m.capabilityId()));
            out.println("  Re-run with the full id, e.g. /" + byShortName.get(0).capabilityId() + " ...");
            out.flush();
            return null;
        }
        return null;
    }

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
                          /add-dir <path>    add a workspace directory for this session
                          /dirs              list all workspace directories
                          /resume            resume the latest paused task in this session
                          /skills            list available skills
                          /<skill name> [args]  run a skill directly

                        Pause/Resume:
                          At any 'Execute/Apply? [y/N/pause]' prompt, type 'pause' to pause the task.
                          After Ctrl+C pauses a task, type /resume to resume it.
                          Resume a paused task by restarting with: rex --resume <session-name>
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

            case "/skills" -> {
                List<SkillMatch> skills = listSkills(agent);
                if (skills.isEmpty()) {
                    out.println("  No skills found. Put SKILL.md under any <marketplace>/plugins/<plugin>/skills/<skill>/ tree in:");
                    out.println("    " + ctx.getWorkspace().primaryRoot().resolve(".rex/marketplaces/"));
                    out.println("    " + Path.of(System.getProperty("user.home"), ".rex/marketplaces/"));
                    out.println("  or add extra plugin directories via skills.extra_dirs in ~/.rex/config.yml");
                } else {
                    out.printf("  %-28s  %-20s  %s%n", "SHORT NAME", "FULL ID", "DESCRIPTION");
                    out.println("  " + "─".repeat(100));
                    for (SkillMatch m : skills) {
                        out.printf("  %-28s  %-20s  %s%n", m.shortName(), m.capabilityId(),
                                truncate(m.description(), 60));
                    }
                    out.println();
                    out.println("  Invoke with: /<short name> [args]  (use full id if name is ambiguous)");
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

            case "/dirs" -> {
                out.println("  Workspace directories:");
                out.println(ctx.getWorkspace().describeRoots());
                out.flush();
            }

            case "/add-dir" -> {
                String pathArg = parts.length > 1 ? parts[1].trim() : "";
                if (pathArg.isEmpty()) {
                    out.println("  Usage: /add-dir <path>");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                try {
                    Path newRoot = Path.of(pathArg).toAbsolutePath().normalize();
                    ctx.getWorkspace().addRoot(newRoot);
                    out.println("  Added workspace directory: " + newRoot);
                    out.println("  Current roots:");
                    out.print(ctx.getWorkspace().describeRoots());
                } catch (IllegalArgumentException e) {
                    out.println("  [error] " + e.getMessage());
                }
                out.flush();
            }

            default -> {
                String skillName = cmd.substring(1);  // drop leading "/"
                String skillArgs = parts.length > 1 ? parts[1] : "";
                List<SkillMatch> skills = listSkills(agent);
                String capabilityId = resolveSkillId(skillName, skills, out);
                if (capabilityId != null) {
                    return SlashResult.runSkill(capabilityId, skillArgs, raw);
                }
                boolean wasAmbiguous = skills.stream().anyMatch(m -> m.shortName().equals(skillName));
                if (!wasAmbiguous) {
                    out.println("Unknown command: " + cmd + "  (type /help for commands, /skills for available skills)");
                    out.flush();
                }
            }
        }
        return SlashResult.CONTINUE;
    }
}
