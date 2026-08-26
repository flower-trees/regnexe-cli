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
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.marketplace.loader.PluginCacheInstaller;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.marketplace.scope.EnabledStateLoader;
import org.salt.regnexe.agent.core.marketplace.scope.EnabledStateWriter;
import org.salt.regnexe.agent.core.marketplace.scope.Scope;
import org.salt.regnexe.agent.core.marketplace.scope.ScopeResolver;
import org.salt.regnexe.agent.core.marketplace.scope.ScopedEnabledState;
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
import org.salt.regnexe.cli.ui.CliRenderer;
import org.salt.regnexe.cli.ui.TerminalCliRenderer;
import org.salt.regnexe.cli.ui.ThemeConfig;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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

    // Filesystem-only helpers for the .rex/marketplaces/*/{plugins,cache}/ convention. No
    // session/CLI concept in these classes themselves; CliMain only does argument parsing and
    // scope→path resolution.
    private final PluginCacheInstaller pluginCacheInstaller = new PluginCacheInstaller();
    private final EnabledStateLoader enabledStateLoader = new EnabledStateLoader();
    private final EnabledStateWriter enabledStateWriter = new EnabledStateWriter();
    private final ScopeResolver scopeResolver = new ScopeResolver();

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
                  rex --continue | -c        continue the most recently used session
                  rex --resume <session-id>  resume a paused task in that session
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

        // Parse --session / --resume / --continue arguments
        String sessionArg = null;
        String resumeArg = null;
        boolean continueArg = false;
        for (int i = 0; i < args.length; i++) {
            if ("--session".equals(args[i]) && i + 1 < args.length) {
                sessionArg = args[i + 1];
            } else if ("--resume".equals(args[i]) && i + 1 < args.length) {
                resumeArg = args[i + 1];
                // --resume implies --session with the same name
                if (sessionArg == null) sessionArg = resumeArg;
            } else if ("--continue".equals(args[i]) || "-c".equals(args[i])) {
                continueArg = true;
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
        CliRenderer renderer = new TerminalCliRenderer(terminal, ThemeConfig.from(config.getUi(), terminal));

        RexDatabase db;
        String dbWarning = null;
        try {
            db = new RexDatabase();
            // On Ctrl+C / SIGTERM, mark in-flight RUNNING tasks as PAUSED so --resume works.
            final RexDatabase dbRef = db;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { dbRef.markAllRunningAsPaused(); } catch (Exception ignored) {}
                try { dbRef.close(); } catch (Exception ignored) {}
            }, "rex-shutdown"));
        } catch (Exception e) {
            dbWarning = "SQLite unavailable, falling back to in-memory: " + e.getMessage();
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
                renderer.interruptPausing();
                pauseAction.run();
            } else {
                renderer.secondInterrupt();
                System.exit(130);
            }
        });

        SessionContext ctx = continueArg
                ? resolveMostRecentSession(config, db)
                : resolveSession(sessionArg, config, db);
        RegnexeAgent agent = buildAgent(ctx, config, terminal, renderer, db, pauseAction);
        agentRef.set(agent);

        String apiKey = config.effectiveApiKey();
        String missingApiKeyEnv = (apiKey == null || apiKey.isBlank())
                ? vendorKeyEnvName(config.getModel().getVendor()) : null;
        renderer.startup(VERSION, ctx, config, missingApiKeyEnv);
        if (dbWarning != null) renderer.warning(dbWarning);

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
                handleAgentResult(r, ctx, out, renderer, db);
            } catch (IllegalStateException e) {
                renderer.warning(e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exitRequested.set(true);
            }
        }

        while (!exitRequested.get()) {
            String input;
            try {
                input = reader.readLine(renderer.prompt(ctx));
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
                        agent = buildAgent(ctx, config, terminal, renderer, db, pauseAction);
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
                            handleAgentResult(skillResult, ctx, out, renderer, db);
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
                handleAgentResult(result, ctx, out, renderer, db);
                if (db != null) {
                    try { db.touchSession(ctx.getSessionName()); } catch (Exception ignored) {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                renderer.error(e.getMessage());
            }
        }

        renderer.goodbye();
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

    /**
     * {@code --continue}/{@code -c}: picks up the most recently used session without the caller
     * needing to remember its name. Deliberately does NOT touch Task-level resume ({@code
     * --resume <name>} stays the only way to trigger that) — this only re-attaches Session memory
     * (Layer 1), same as if the caller had typed {@code --session <that name>} themselves. No
     * sessions yet (fresh install) falls back to the same default-session bootstrap as a bare,
     * no-flags launch.
     */
    private SessionContext resolveMostRecentSession(RexConfig config, RexDatabase db) {
        if (db == null) {
            WorkspaceContext ws = buildWorkspaceFor(System.getProperty("user.dir"), config);
            return new SessionContext(UUID.randomUUID().toString(), DEFAULT_SESSION, ws);
        }
        try {
            List<SessionRow> sessions = db.listSessions(); // ORDER BY updated_at DESC
            if (sessions.isEmpty()) {
                return resolveSession(null, config, db);
            }
            SessionRow row = sessions.get(0);
            WorkspaceContext ws = buildWorkspaceFor(row.getWorkingDir(), config);
            return new SessionContext(row.getSessionId(), row.getName(), ws);
        } catch (SQLException e) {
            System.err.println("[warn] Session DB error: " + e.getMessage());
            WorkspaceContext ws = buildWorkspaceFor(System.getProperty("user.dir"), config);
            return new SessionContext(UUID.randomUUID().toString(), DEFAULT_SESSION, ws);
        }
    }

    // ── Result handling ───────────────────────────────────────────────────────

    private void handleAgentResult(AgentResult result, SessionContext ctx, PrintWriter out,
                                   CliRenderer renderer, RexDatabase db) {
        // TASK_TOKEN_SUMMARY fires via listener just before execute()/resume() returns.
        // Print the clean final answer after the token summary line.
        String answer = result.getFinalText();
        if (answer != null && !answer.isBlank()) {
            out.println(answer);
        }
        if (result.getStatus() == TaskStatus.PAUSED) {
            storePausedTaskSummary(result, db);
            renderer.paused(ctx.getSessionName());
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
                                    CliRenderer renderer,
                                    RexDatabase db, Runnable pauseAction) {
        RexConfig.AgentConfig ac = config.getAgent();
        WorkspaceContext workspace = ctx.getWorkspace();
        var builder = agentBuilder
                .withDefaultModel(config.getModel().getVendor(), config.effectiveModel())
                .withEventListener(new CliEventListener(renderer))
                .withMaxRounds(ac.getMaxRounds())
                .withMaxAgentIterations(ac.getMaxAgentIterations())
                .withSessionBufferSize(ac.getSessionBufferSize())
                .withSessionCompactPeriod(ac.getSessionCompactPeriod())
                .withAgentContext(SlidingWindowContext.builder()
                        .windowSize(ac.getContextWindowSize())
                        .build())
                .withTool(
                        FileTools.readFile(workspace),
                        FileTools.listFiles(workspace),
                        FileTools.searchFiles(workspace),
                        FileTools.writeFile(workspace, renderer, pauseAction),
                        FileTools.editFile(workspace, renderer, pauseAction),
                        BashTool.bash(workspace, config.getTools().getBash(), terminal, renderer, pauseAction)
                );
        if (db != null) {
            builder = builder.withSessionStorage(new SqliteConversationStorage(db));
            builder = builder.withTaskStore(new SqliteTaskStore(db));
        }

        // On-disk convention: skills/ (manifest-less, directly-editable) is a separate tree from
        // marketplaces/*/{plugins,cache}/, each present at both user (~/.rex) and project
        // (<workspace>/.rex) scope. marketplaces/*/plugins/ is only an "installable" listing — it
        // is NOT scanned here. Only marketplaces/*/cache/ (populated by `/plugin install`) is
        // loaded, aligning with how Claude Code/Codex's own marketplace directories are never
        // auto-loaded without an explicit install step. skillDirs also folds in skills.extra_dirs
        // from config.yml (see resolveSkillDirectories).
        List<String> skillDirs = resolveSkillDirectories(workspace, config);
        if (!skillDirs.isEmpty()) {
            builder = builder.withSkillsDirectory(skillDirs.toArray(new String[0]));
        }
        List<String> pluginDirs = resolveMarketplacePluginDirectories(workspace);
        if (!pluginDirs.isEmpty()) {
            builder = builder.withPluginDirectory(pluginDirs.toArray(new String[0]));
        }
        // enabled.yml persists the soft on/off switch written by /plugin enable|disable.
        // User layer first, Project layer last so it wins on conflict — an interim default
        // (project-overrides-user, matching the convention already used for duplicate-plugin-id
        // directory scan order below), NOT a final answer to the still-open cross-scope-priority
        // question.
        Map<Scope, Path> enabledYmlByScope = new LinkedHashMap<>();
        enabledYmlByScope.put(Scope.USER, Path.of(System.getProperty("user.home"), ".rex", "enabled.yml"));
        enabledYmlByScope.put(Scope.PROJECT, workspace.primaryRoot().resolve(".rex").resolve("enabled.yml"));
        builder = builder.withEnabledState(enabledYmlByScope, List.of(Scope.USER, Scope.PROJECT));

        // A claudeCompatMode Skill with no declared allowed-tools (i.e. most real Claude Code
        // skills) falls back to sandboxed filesystem tools scoped to this workspace root instead
        // of a fresh throwaway temp dir — so a skill-authoring skill (skill-creator and friends)
        // can see and edit the project's own .rex/ tree across runs. Deliberately scoped to .rex/
        // only, not the whole project root: claude-compat fallback tools should never reach
        // source code, .git, or .env.
        builder = builder.withClaudeCompatWorkspace(workspace.primaryRoot().resolve(".rex"));

        // Long-term project memory (REX.md) — independent of the three memory layers; always
        // injected regardless of session/history.
        String projectMemory = resolveProjectMemory(workspace);
        if (!projectMemory.isBlank()) {
            builder = builder.withProjectMemory(projectMemory);
        }

        return builder.build();
    }

    /**
     * Reads {@code ~/.rex/REX.md} and {@code <project>/.rex/REX.md} and concatenates them —
     * user layer first, project layer last (closer to the model's attention, and project can
     * add context beyond what the user-level file says). Missing files are silently skipped;
     * this is meant to be optional, unlike {@code enabled.yml}/skills which have real absence
     * semantics.
     */
    private String resolveProjectMemory(WorkspaceContext workspace) {
        StringBuilder sb = new StringBuilder();
        appendRexMd(sb, Path.of(System.getProperty("user.home"), ".rex", "REX.md"));
        appendRexMd(sb, workspace.primaryRoot().resolve(".rex").resolve("REX.md"));
        return sb.toString().strip();
    }

    private void appendRexMd(StringBuilder sb, Path rexMd) {
        if (!Files.isRegularFile(rexMd)) return;
        try {
            String content = Files.readString(rexMd).strip();
            if (content.isEmpty()) return;
            if (sb.length() > 0) sb.append("\n\n---\n\n");
            sb.append(content);
        } catch (IOException ignored) {
            // best-effort — an unreadable REX.md just means "no memory from this scope"
        }
    }

    /**
     * {@code <project>/.rex/skills}, {@code ~/.rex/skills}, plus any {@code skills.extra_dirs}
     * declared in {@code ~/.rex/config.yml} — this order is priority order (earlier wins on a
     * pluginId collision, since {@code DefaultPluginManager} degrades duplicates to a
     * skip-with-warning in scan order), matching {@code resolveMarketplacePluginDirectories}
     * (project before user) and {@code enabled.yml}'s {@code ScopeResolver} merge (Project
     * overrides User). Project-before-user used to be reversed here, which was an inconsistency
     * against those other two.
     * {@code extra_dirs} lets a user point at an arbitrary flat-SKILL.md directory outside the
     * {@code .rex} convention (e.g. a shared team skills checkout) without it needing to live
     * under either scope root; it's listed last (lowest priority) since it's a supplementary
     * source, not a scope layer.
     */
    private List<String> resolveSkillDirectories(WorkspaceContext workspace, RexConfig config) {
        List<String> dirs = new ArrayList<>();
        addIfDirectory(dirs, workspace.primaryRoot().resolve(".rex").resolve("skills"));
        addIfDirectory(dirs, Path.of(System.getProperty("user.home"), ".rex", "skills"));
        List<String> extra = config.getSkills().getExtraDirs();
        if (extra != null) {
            for (String d : extra) {
                if (d != null && !d.isBlank()) dirs.add(expandHome(d.trim()));
            }
        }
        return dirs;
    }

    private String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /**
     * One installed plugin's resolved location, in the exact order {@link #buildAgent} loads
     * plugins in — the first {@link InstalledPluginEntry} for a given {@code pluginId} is the one
     * that actually wins the registry (see {@link #resolveMarketplacePluginDirectories}); every
     * later one with the same {@code pluginId} is silently shadowed by
     * {@code DefaultPluginManager}'s "first-scanned wins" duplicate-id skip. {@code SimpleMarketplace}
     * keys its registry by bare {@code pluginId} only (no marketplace namespace — a deliberate
     * simplification, see the design doc's "Scope-creep self-correction" note), so the same
     * pluginId installed under two different marketplace names collides exactly like this.
     */
    private record InstalledPluginEntry(String pluginId, String marketplaceName, String scopeLabel, Path resolvedDir) {
        String globalId() {
            return pluginId + "@" + marketplaceName;
        }
    }

    /**
     * Every plugin currently resolved via {@code cache/<plugin-id>/CURRENT} under
     * {@code ~/.rex/marketplaces/*} and {@code <project>/.rex/marketplaces/*} — one entry per
     * installed plugin's resolved version directory (not one per marketplace — see
     * {@link PluginCacheInstaller#resolveCurrent}). {@code plugins/} is deliberately NOT scanned
     * here — it's just what {@code /plugin install} reads sources from, not something that's ever
     * auto-loaded. Project scope listed before user scope so
     * {@code DefaultPluginManager}'s "first-scanned wins" duplicate-id skip favors the
     * project-level plugin (matches the existing
     * DefaultPluginManagerManifestCompatTest#duplicatePluginIdAcrossDirectoriesShouldSkipInsteadOfThrow
     * convention in regnexe-agent: caller lists the higher-priority source first).
     */
    private List<String> resolveMarketplacePluginDirectories(WorkspaceContext workspace) {
        return listAllInstalledEntriesInScanOrder(workspace).stream()
                .map(e -> e.resolvedDir().toString())
                .toList();
    }

    /** Same scan (and same priority order) as {@link #resolveMarketplacePluginDirectories}, but keeping pluginId/marketplace/scope for display and conflict-detection (see {@code /plugin list} and {@code /plugin install}). */
    private List<InstalledPluginEntry> listAllInstalledEntriesInScanOrder(WorkspaceContext workspace) {
        List<InstalledPluginEntry> entries = new ArrayList<>();
        collectInstalledEntries(entries, workspace.primaryRoot().resolve(".rex").resolve("marketplaces"), "project");
        collectInstalledEntries(entries, Path.of(System.getProperty("user.home"), ".rex", "marketplaces"), "user");
        return entries;
    }

    private void collectInstalledEntries(List<InstalledPluginEntry> entries, Path marketplacesRoot, String scopeLabel) {
        if (!Files.isDirectory(marketplacesRoot)) return;
        try (Stream<Path> subdirs = Files.list(marketplacesRoot)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(marketDir -> {
                String marketplaceName = marketDir.getFileName().toString();
                for (String pluginId : pluginCacheInstaller.listInstalledIds(marketDir)) {
                    pluginCacheInstaller.resolveCurrent(marketDir, pluginId)
                            .ifPresent(dir -> entries.add(
                                    new InstalledPluginEntry(pluginId, marketplaceName, scopeLabel, dir)));
                }
            });
        } catch (IOException ignored) {
            // best-effort discovery — a missing/unreadable marketplaces/ dir just means "none found"
        }
    }

    /**
     * Every flat skill under {@link #resolveSkillDirectories}'s roots (project skills, user
     * skills, extra_dirs) plus {@link #listAllInstalledEntriesInScanOrder} (marketplaces cache) —
     * used only for {@code warnDuplicatePluginIds}'s conflict check, never for what actually gets
     * loaded (that split stays exactly as it is: skills and marketplace cache are two independent
     * builder calls in {@code buildAgent()}). Skills listed first because {@code buildAgent()}
     * calls {@code withSkillsDirectory} before {@code withPluginDirectory} — matching that order
     * here matters, not just including both: get it backwards and the warning tells the user the
     * wrong one won. A bare skill and a marketplace plugin sharing a pluginId collide in the same
     * way two marketplace plugins do.
     */
    private List<InstalledPluginEntry> listAllPluginIdSourcesForConflictCheck(WorkspaceContext workspace, RexConfig config) {
        List<InstalledPluginEntry> entries = new ArrayList<>();
        collectSkillEntries(entries, workspace.primaryRoot().resolve(".rex").resolve("skills"), "project");
        collectSkillEntries(entries, Path.of(System.getProperty("user.home"), ".rex", "skills"), "user");
        List<String> extra = config.getSkills().getExtraDirs();
        if (extra != null) {
            for (String d : extra) {
                if (d != null && !d.isBlank()) collectSkillEntries(entries, Path.of(expandHome(d.trim())), "extra_dirs");
            }
        }
        entries.addAll(listAllInstalledEntriesInScanOrder(workspace));
        return entries;
    }

    /** Mirrors {@code FlatSkillLoader}'s own scan: each immediate subdirectory with a {@code SKILL.md} is one pluginId, named after the directory. */
    private void collectSkillEntries(List<InstalledPluginEntry> entries, Path skillsRoot, String scopeLabel) {
        if (!Files.isDirectory(skillsRoot)) return;
        try (Stream<Path> subdirs = Files.list(skillsRoot)) {
            subdirs.filter(Files::isDirectory).sorted()
                    .filter(skillDir -> Files.exists(skillDir.resolve("SKILL.md")))
                    .forEach(skillDir -> entries.add(
                            new InstalledPluginEntry(skillDir.getFileName().toString(), "skills", scopeLabel, skillDir)));
        } catch (IOException ignored) {
            // best-effort discovery — a missing/unreadable skills/ dir just means "none found"
        }
    }

    /**
     * Prints one warning line per pluginId installed under more than one location — the registry
     * only keys by bare pluginId, so every entry after the first for a given id is silently
     * dropped by {@code DefaultPluginManager} at load time (a WARN-level log line that, in this
     * packaged jar, is neither shown on the console nor written to any file — see
     * harness-testbed case 002's re-run notes). This is the CLI's only way to surface that.
     */
    private void warnDuplicatePluginIds(PrintWriter out, List<InstalledPluginEntry> entries) {
        Map<String, InstalledPluginEntry> winners = new LinkedHashMap<>();
        for (InstalledPluginEntry entry : entries) {
            InstalledPluginEntry winner = winners.putIfAbsent(entry.pluginId(), entry);
            if (winner != null) {
                out.printf("  [warn] plugin id '%s' is installed in more than one place — "
                                + "only %s (%s) is actually active; %s (%s) is silently shadowed "
                                + "(first-scanned wins; use /plugin uninstall on the one you don't want)%n",
                        entry.pluginId(),
                        winner.globalId(), winner.scopeLabel(),
                        entry.globalId(), entry.scopeLabel());
            }
        }
    }

    private void addIfDirectory(List<String> dirs, Path path) {
        if (Files.isDirectory(path)) dirs.add(path.toString());
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
                          /plugin install <local-path> [--marketplace <name>] [--scope user|project]
                          /plugin uninstall <plugin-id>@<marketplace> [--scope user|project]
                          /plugin enable|disable <plugin-id>@<marketplace> [--scope user|project]
                          /plugin list       list installed plugins (both scopes) with enabled state

                        Pause/Resume:
                          At any 'Execute/Apply? [y/N/pause]' prompt, type 'pause' to pause the task.
                          After Ctrl+C pauses a task, type /resume to resume it.
                          Resume a paused task by restarting with: rex --resume <session-name>
                          Continue the most recent session (no name needed): rex --continue
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

            case "/plugin" -> {
                return handlePluginCommand(parts.length > 1 ? parts[1].trim() : "", out, ctx, config);
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

    // ── /plugin install|uninstall|enable|disable|list ────────────────────────
    // install/uninstall/enable/disable all change what's on disk under .rex/, so they return
    // AGENT_REBUILT — the next loop iteration re-runs buildAgent(), which re-resolves cache/ and
    // re-applies enabled.yml from scratch.

    private SlashResult handlePluginCommand(String args, PrintWriter out, SessionContext ctx, RexConfig config) {
        String[] tokens = args.isBlank() ? new String[0] : args.split("\\s+");
        if (tokens.length == 0) {
            out.println("  Usage: /plugin install|uninstall|enable|disable|list ...  (type /help for details)");
            out.flush();
            return SlashResult.CONTINUE;
        }
        String subcmd = tokens[0];
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            if (("--marketplace".equals(tokens[i]) || "--scope".equals(tokens[i])) && i + 1 < tokens.length) {
                flags.put(tokens[i].substring(2), tokens[++i]);
            } else {
                positional.add(tokens[i]);
            }
        }
        String marketplaceName = flags.getOrDefault("marketplace", "default");
        boolean userScope = "user".equalsIgnoreCase(flags.getOrDefault("scope", "project"));
        Path rexRoot = userScope
                ? Path.of(System.getProperty("user.home"), ".rex")
                : ctx.getWorkspace().primaryRoot().resolve(".rex");
        Path enabledYml = rexRoot.resolve("enabled.yml");

        switch (subcmd) {
            case "install" -> {
                if (positional.isEmpty()) {
                    out.println("  Usage: /plugin install <local-path> [--marketplace <name>] [--scope user|project]");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                Path source = Path.of(positional.get(0)).toAbsolutePath().normalize();
                Path marketplaceRoot = rexRoot.resolve("marketplaces").resolve(marketplaceName);
                try {
                    PluginCacheInstaller.InstallResult result = pluginCacheInstaller.install(source, marketplaceRoot);
                    String globalId = result.pluginId() + "@" + marketplaceName;
                    // Explicit, even though an undeclared plugin already defaults to enabled —
                    // makes enabled.yml a readable record of "this came in via install".
                    enabledStateWriter.setEnabled(enabledYml, globalId, true);
                    out.printf("  Installed %s (hash %s)%s -> %s%n", globalId, result.hash(),
                            result.alreadyPresent() ? " [already cached]" : "", result.installedPath());
                    // pluginId collisions across marketplaces/scopes/skills/ are silent at load
                    // time (see warnDuplicatePluginIds's javadoc) — this is the only point the CLI
                    // can tell the user "what you just installed may not actually be the one that runs".
                    warnDuplicatePluginIds(out, listAllPluginIdSourcesForConflictCheck(ctx.getWorkspace(), config));
                } catch (IllegalArgumentException e) {
                    out.println("  [error] " + e.getMessage());
                    out.flush();
                    return SlashResult.CONTINUE;
                } catch (RuntimeException e) {
                    out.println("  [error] install failed: " + e.getMessage());
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                out.flush();
                return SlashResult.AGENT_REBUILT;
            }

            case "uninstall" -> {
                if (positional.isEmpty()) {
                    out.println("  Usage: /plugin uninstall <plugin-id>@<marketplace> [--scope user|project]");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                String globalId = positional.get(0);
                String pluginId = pluginIdOf(globalId);
                String marketplace = marketplaceOf(globalId, marketplaceName);
                Path marketplaceRoot = rexRoot.resolve("marketplaces").resolve(marketplace);
                boolean removed = pluginCacheInstaller.uninstall(marketplaceRoot, pluginId);
                enabledStateWriter.remove(enabledYml, pluginId + "@" + marketplace);
                out.println(removed ? "  Uninstalled " + pluginId + "@" + marketplace
                        : "  Nothing installed for " + pluginId + "@" + marketplace);
                out.flush();
                return removed ? SlashResult.AGENT_REBUILT : SlashResult.CONTINUE;
            }

            case "enable", "disable" -> {
                if (positional.isEmpty()) {
                    out.printf("  Usage: /plugin %s <plugin-id>@<marketplace> [--scope user|project]%n", subcmd);
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                String globalId = positional.get(0);
                boolean value = "enable".equals(subcmd);
                enabledStateWriter.setEnabled(enabledYml, globalId, value);
                out.println("  " + (value ? "Enabled " : "Disabled ") + globalId + " (scope: " + rexRoot + ")");
                out.flush();
                return SlashResult.AGENT_REBUILT;
            }

            case "list" -> {
                out.println("  Installed plugins:");
                List<InstalledPluginEntry> entries = listAllInstalledEntriesInScanOrder(ctx.getWorkspace());
                if (entries.isEmpty()) {
                    out.println("    (none)");
                } else {
                    // Same USER-then-PROJECT merge order as buildAgent()'s withEnabledState — the
                    // "enabled" column here now reflects what would ACTUALLY run, not just this
                    // one scope's own enabled.yml (which used to be able to disagree with the
                    // resolved truth whenever both scopes declared the same key).
                    Map<String, Boolean> resolvedEnabled = resolveEnabledAcrossScopes(ctx.getWorkspace());
                    for (InstalledPluginEntry entry : entries) {
                        boolean enabled = resolvedEnabled.getOrDefault(entry.globalId(), true);
                        out.printf("    [%s] %s%s%n", entry.scopeLabel(), entry.globalId(),
                                enabled ? "" : "  (disabled)");
                    }
                    // Conflict check runs against marketplaces/*/cache/ AND skills/ together — a
                    // bare skill and a marketplace plugin sharing a pluginId collide too, even
                    // though skills/ isn't itself listed above as an "installed plugin".
                    warnDuplicatePluginIds(out, listAllPluginIdSourcesForConflictCheck(ctx.getWorkspace(), config));
                }
                out.flush();
                return SlashResult.CONTINUE;
            }

            default -> {
                out.println("  Unknown /plugin subcommand: " + subcmd
                        + "  (expected install|uninstall|enable|disable|list)");
                out.flush();
                return SlashResult.CONTINUE;
            }
        }
    }

    private String pluginIdOf(String globalId) {
        int at = globalId.lastIndexOf('@');
        return at >= 0 ? globalId.substring(0, at) : globalId;
    }

    private String marketplaceOf(String globalId, String defaultMarketplace) {
        int at = globalId.lastIndexOf('@');
        return at >= 0 ? globalId.substring(at + 1) : defaultMarketplace;
    }

    /**
     * Same USER-then-PROJECT merge {@code buildAgent()}'s {@code withEnabledState} call uses —
     * kept in one place so {@code /plugin list} can never show a different "enabled" answer than
     * what actually gets applied at agent build time.
     */
    private Map<String, Boolean> resolveEnabledAcrossScopes(WorkspaceContext workspace) {
        Path userEnabledYml = Path.of(System.getProperty("user.home"), ".rex", "enabled.yml");
        Path projectEnabledYml = workspace.primaryRoot().resolve(".rex").resolve("enabled.yml");
        List<ScopedEnabledState> layers = List.of(
                new ScopedEnabledState(Scope.USER, enabledStateLoader.load(userEnabledYml)),
                new ScopedEnabledState(Scope.PROJECT, enabledStateLoader.load(projectEnabledYml)));
        return scopeResolver.resolve(layers);
    }
}
