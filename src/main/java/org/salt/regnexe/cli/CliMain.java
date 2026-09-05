package org.salt.regnexe.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.salt.jlangchain.ai.client.AiException;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.agent.memory.SlidingWindowContext;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.mcp.McpClient;
import org.salt.jlangchain.rag.tools.mcp.server.config.McpConfig;
import org.salt.jlangchain.rag.tools.mcp.server.config.ServerConfig;
import org.salt.jlangchain.rag.tools.mcp.server.param.ServerStatus;
import org.salt.jlangchain.rag.tools.mcp.tool.ToolDesc;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.llm.DefaultModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
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
import org.salt.regnexe.cli.tools.McpTools;
import org.salt.regnexe.cli.tools.WorkspaceContext;
import org.salt.regnexe.cli.ui.CliRenderer;
import org.salt.regnexe.cli.ui.TerminalCliRenderer;
import org.salt.regnexe.cli.ui.ThemeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CliMain.class);

    static final String VERSION = "0.1.0";

    /** Prefix for auto-generated session names — see {@link #generateSessionName()}. */
    private static final String SESSION_NAME_PREFIX = "session";

    @Autowired
    private RegnexeAgentBuilder agentBuilder;

    // Filesystem-only helpers for the .rex/marketplaces/*/{plugins,cache}/ convention. No
    // session/CLI concept in these classes themselves; CliMain only does argument parsing and
    // scope→path resolution.
    private final PluginCacheInstaller pluginCacheInstaller = new PluginCacheInstaller();
    private final EnabledStateLoader enabledStateLoader = new EnabledStateLoader();
    private final EnabledStateWriter enabledStateWriter = new EnabledStateWriter();
    private final ScopeResolver scopeResolver = new ScopeResolver();

    // The MCP client currently backing the running agent's MCP-sourced tools — both directly
    // configured (.rex/mcp.json) and Plugin-carried (<plugin-dir>/mcp.json) servers connect
    // through this one client. Holds real OS resources (child processes for stdio servers, open
    // connections for sse/http), so it must be destroy()ed before buildAgent() replaces it with a
    // fresh one, and on process exit.
    private McpClient activeMcpClient;

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
        // Wire planner_api_key / reflector_api_key too, in case those roles run on a genuinely
        // different vendor than the main model (see RexConfig.ModelConfig.plannerVendor javadoc)
        // — each needs its OWN vendor's key system-property set, not just the main model's.
        // effectivePlannerApiKey()/effectiveReflectorApiKey() already fall back to the main key
        // when the role's vendor matches the main one, so this is a no-op (re-sets the same
        // property to the same value) in the common same-vendor case.
        wireRoleApiKey("planner", preConfig.effectivePlannerVendor(), preConfig.effectivePlannerApiKey(),
                preConfig.getModel().getPlannerVendor());
        wireRoleApiKey("reflector", preConfig.effectiveReflectorVendor(), preConfig.effectiveReflectorApiKey(),
                preConfig.getModel().getReflectorVendor());

        // Wire model.chat_url from config → Spring @Value the same way, for any vendor. Every
        // vendor actuator already exposes its URL as "models.<vendor>.chat-url" with its real
        // endpoint as the @Value default (see j-langchain's *Actuator classes), so this works
        // generically — not just for vendor: custom (CustomActuator, whose chat-url has no
        // default at all and is the main reason this exists: pointing at an arbitrary
        // OpenAI-Chat-Completions-compatible endpoint like OpenRouter/Together/a self-hosted
        // server), but also to redirect any named vendor at a compatible proxy/mirror/regional
        // endpoint if a user ever needs that.
        String chatUrl = preConfig.getModel().getChatUrl();
        if (chatUrl != null && !chatUrl.isBlank()) {
            String urlProp = chatUrlPropFor(preConfig.getModel().getVendor());
            if (urlProp != null) {
                System.setProperty(urlProp, chatUrl);
            }
        }

        SpringApplication app = new SpringApplication(CliMain.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    /**
     * Sets the vendor's key system property for a Planner/Reflector role override, warning
     * instead of silently sending no key (or the wrong vendor's) when the user configured a
     * genuinely different vendor for this role but forgot its own {@code <role>_api_key}.
     *
     * @param roleLabel        "planner" or "reflector", for the warning message only
     * @param effectiveVendor  the role's resolved vendor (falls back to the main vendor when unset)
     * @param effectiveApiKey  the role's resolved key ({@code null} if a different vendor has no key of its own)
     * @param configuredVendorOverride the raw {@code <role>_vendor} config value, or null/blank if not set
     */
    private static void wireRoleApiKey(String roleLabel, String effectiveVendor, String effectiveApiKey,
                                        String configuredVendorOverride) {
        if (effectiveApiKey != null && !effectiveApiKey.isBlank()) {
            String prop = apiKeyPropFor(effectiveVendor);
            if (prop != null) {
                System.setProperty(prop, effectiveApiKey);
            }
            return;
        }
        if (configuredVendorOverride != null && !configuredVendorOverride.isBlank()) {
            System.err.printf(
                    "[warn] model.%s_vendor is set to '%s' but model.%s_api_key is empty — "
                    + "%s will likely fail to authenticate unless %s is already set another way "
                    + "(env var, -D system property).%n",
                    roleLabel, configuredVendorOverride, roleLabel, roleLabel, vendorKeyEnvName(effectiveVendor));
        }
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
            case "custom"            -> envName ? "CUSTOM_KEY"   : "models.custom.chat-key";
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

    /**
     * Maps vendor name → j-langchain Spring property key for chat-url override. Unlike
     * {@link #apiKeyMapFor}, this is a single formula rather than a per-vendor switch: every
     * vendor actuator already exposes its URL as {@code models.<vendor>.chat-url} (confirmed
     * against every {@code *Actuator} class in j-langchain — chat-url is always that exact
     * property name, just with each vendor's real endpoint as the default), so no per-vendor
     * table is needed. Only the "openai" CLI vendor name is special-cased: it maps to
     * {@code ChatGPTActuator}, whose Spring property prefix is the older "chatgpt", not "openai"
     * (see {@link #apiKeyMapFor}'s "openai", "chatgpt" case for the same alias).
     */
    private static String chatUrlPropFor(String vendor) {
        if (vendor == null) return null;
        String key = "openai".equalsIgnoreCase(vendor) ? "chatgpt" : vendor.toLowerCase();
        return "models." + key + ".chat-url";
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

        // Deliberately NOT forcing .dumb(true): dumb mode skips proper raw-mode terminal-driver
        // cooperation, so output written from the agent's background worker thread (status
        // events like "Ready"/"Executing...", dispatched concurrently with the main thread's
        // interactive readLine() below) can bleed into the next readLine() call as if it had
        // been typed — observed for real as spurious follow-up tasks whose "goal" was literally
        // the CLI's own last-printed status line or an internal [SYSTEM NOTICE] string.
        // .system(true) alone lets JLine auto-detect real terminal capabilities and only fall
        // back to dumb mode itself when the terminal genuinely isn't a TTY (piped/CI usage).
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
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
            final RexDatabase dbRef = db;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { dbRef.close(); } catch (Exception ignored) {}
            }, "rex-shutdown"));
        } catch (Exception e) {
            dbWarning = "SQLite unavailable, falling back to in-memory: " + e.getMessage();
            db = null;
        }

        // MCP connections (stdio child processes, sse/http sockets) must be torn down on exit —
        // otherwise a stdio server's child process outlives the CLI. Independent of the db hook
        // above (registered even when SQLite is unavailable).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (activeMcpClient != null) {
                try { activeMcpClient.destroy(); } catch (Exception ignored) {}
            }
        }, "rex-mcp-shutdown"));

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

        SessionContext ctx = resolveSession(sessionArg, config, db);
        RegnexeAgent agent = buildAgent(ctx, config, terminal, renderer, db, pauseAction);
        agentRef.set(agent);

        String apiKey = config.effectiveApiKey();
        String missingApiKeyEnv = (apiKey == null || apiKey.isBlank())
                ? vendorKeyEnvName(config.getModel().getVendor()) : null;
        renderer.startup(VERSION, ctx, config, missingApiKeyEnv);
        if (dbWarning != null) renderer.warning(dbWarning);

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

            if (input.startsWith("/")) {
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

            try {
                RegnexeAgent taskAgent = agent;
                TaskRequest req = new TaskRequest();
                req.setGoal(injectWorkspacePreamble(input, ctx));
                req.setSessionId(ctx.getSessionName());
                AgentResult result = runAgentTask(
                        () -> taskAgent.execute(req),
                        ctx,
                        out,
                        executing,
                        interruptCount);
                handleAgentResult(result, ctx, out, renderer, db);
                if (db != null) {
                    try { db.touchSession(ctx.getSessionName()); } catch (Exception ignored) {}
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                renderer.error(describeError(e));
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

    // ── Session resolution ────────────────────────────────────────────────────

    /**
     * Generates a name for a fresh implicit session — {@code session-<yyyyMMdd-HHmmss>-<4 hex>}.
     * There is no more single shared "default" session: every launch without an explicit {@code
     * --session <name>} gets its own brand-new one, named on the spot. This is what keeps a
     * session's stored working_dir trustworthy without ever needing to silently rewrite it later —
     * a fresh session's working_dir is simply wherever it was just created, always correct by
     * construction. The trailing hex suffix guards the (very unlikely) case of two launches in the
     * same second; {@code name} is UNIQUE in the sessions table.
     */
    private static String generateSessionName() {
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String suffix = UUID.randomUUID().toString().substring(0, 4);
        return SESSION_NAME_PREFIX + "-" + ts + "-" + suffix;
    }

    private SessionContext resolveSession(String nameArg, RexConfig config, RexDatabase db) {
        boolean explicit = nameArg != null && !nameArg.isBlank();
        String launchDir = System.getProperty("user.dir");
        String sessionName = explicit ? nameArg : generateSessionName();
        if (db == null) {
            WorkspaceContext ws = buildWorkspaceFor(launchDir, config);
            return new SessionContext(UUID.randomUUID().toString(), sessionName, ws);
        }
        try {
            // An explicit --session <name> may legitimately be reused across launches (that's the
            // whole point of naming one) — find-or-create, same as /switch. An implicit launch
            // always creates a fresh session (see generateSessionName()), so this lookup is
            // skipped entirely and row is always null.
            SessionRow row = explicit ? db.findSessionByName(sessionName).orElse(null) : null;
            if (row == null) {
                row = new SessionRow();
                row.setSessionId(UUID.randomUUID().toString());
                row.setName(sessionName);
                row.setWorkingDir(launchDir);
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
            WorkspaceContext ws = buildWorkspaceFor(launchDir, config);
            return new SessionContext(UUID.randomUUID().toString(), sessionName, ws);
        }
    }

    // ── Result handling ───────────────────────────────────────────────────────

    private void handleAgentResult(AgentResult result, SessionContext ctx, PrintWriter out,
                                   CliRenderer renderer, RexDatabase db) {
        // TASK_TOKEN_SUMMARY fires via listener just before execute() returns.
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
        summary.append("Task was paused.");

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

    /**
     * Builds the LLM used by {@link SlidingWindowContext} to compress steps that age out of the
     * window into a real summary, instead of the raw-text concatenation it falls back to when no
     * summarizer is set. Reuses the session's own configured model — simplest option, and no new
     * config surface (a second "summarizer model" setting) to add for what's an internal-only
     * compression step, not something the user watches responses from.
     * <p>
     * Real incident this fixes: on a long research-heavy run (89 tool calls, one deepseek
     * session, default window size 6) with no summarizer wired, {@code news-sources.md} — read
     * once early on — got re-read from scratch later in the same run. The content technically
     * stayed in context (raw-concatenated into {@code earlyStepsSummary}), but buried under ~80
     * un-compressed steps' worth of raw tool output (some of them large Playwright DOM
     * snapshots), it was easy for the model to lose track of. A real per-step summary keeps that
     * blob from growing into unstructured noise.
     * <p>
     * Returns null (falls back to {@link SlidingWindowContext}'s plain-concatenation behavior)
     * if the model can't be constructed — e.g. an unrecognized vendor/model string — rather than
     * fail CLI startup over a compression-quality nicety.
     */
    private static BaseChatModel buildSummarizerModel(RexConfig config) {
        try {
            String model = config.effectiveModel();
            return new DefaultModelProvider().provide(
                    ModelSpec.of(config.getModel().getVendor(), model, deepseekThinkingKwargs(model)));
        } catch (Exception e) {
            log.warn("Could not build a summarizer model ({}); SlidingWindowContext will fall back to plain-text concatenation for compressed steps.", e.getMessage());
            return null;
        }
    }

    /**
     * DeepSeek models default to "thinking" (chain-of-thought) mode on for every call — verified
     * directly against both {@code api.deepseek.com} (vendor deepseek) and DashScope's
     * compatible-mode endpoint (vendor aliyun, e.g. {@code deepseek-v4-flash-0731}/
     * {@code deepseek-v4-pro-0813} — Aliyun hosts DeepSeek-family models under their own dated
     * names): the response's {@code reasoning_content} field is populated unless the request
     * explicitly disables it. That reasoning text isn't shown to the user but is real, billed
     * output tokens on every Plan/Execute/Reflect call — for Execute in particular (a
     * multi-iteration ReAct loop), it compounds into real added latency per tool-call round-trip.
     * {@code thinking: {type: "disabled"}} is the parameter that actually turns it off (confirmed
     * empirically on both endpoints above — {@code enable_thinking: false}, the qwen3 convention,
     * does NOT work for deepseek). Keyed off the MODEL name rather than vendor: what determines
     * whether this parameter is recognized is which underlying model is actually answering, not
     * which vendor's endpoint the request happens to go through — a deepseek-family model hosted
     * via aliyun still needs (and still honors) the same override a direct deepseek call does.
     */
    /**
     * A raw {@code Exception.getMessage()} for a hard-FAILED task (see RegnexeAgent.runLoop() —
     * 403/404 and anything else unclassified reaches here, since those stay a real re-thrown
     * exception rather than a clean PAUSED) is usually either {@code null} (many exceptions,
     * NullPointerException included, don't set one) or, for an HTTP failure specifically,
     * {@code RuntimeException(Throwable)}'s default {@code cause.toString()} — something like
     * {@code "...AiException: {\"error\":{\"message\":\"...\"}}"}, technically informative but not
     * pleasant to read. Unwrap an AiException in the cause chain for a clean "HTTP <code>: <body>"
     * instead; otherwise fall back to the exception's own class name so the user at least sees
     * what kind of thing went wrong rather than a bare "error: null".
     */
    private static String describeError(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof AiException ai) {
                return "HTTP " + ai.getCode() + ": " + ai.getMessage();
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
    }

    private static java.util.Map<String, Object> deepseekThinkingKwargs(String model) {
        if (model == null) return null;
        String m = model.toLowerCase();
        // deepseek-family models (direct or aliyun-hosted) default chain-of-thought ON, wasting
        // billed tokens/latency on Plan/Execute/Reflect calls that never surface it. Confirmed via
        // direct curl to both api.deepseek.com and Aliyun's DashScope endpoint.
        if (m.startsWith("deepseek-")) {
            return java.util.Map.of("thinking", java.util.Map.of("type", "disabled"));
        }
        // harness-punchbag-* (local LiteLLM proxy, vendor: custom): confirmed via real 400s from
        // three separate call sites (TaskPlanner.process, Reflector.process, and now
        // CapabilityExecutor's Execute call too) that this proxy's backend rejects the
        // framework's default temperature=0.7 — "invalid temperature: only 1 is allowed for this
        // model" / "not supported for kimi-k3 model". OpenAIConver.convertRequest already
        // special-cases a "temperature" key inside modelKwargs to override the request's
        // temperature field (see its javadoc comment), so this is the same mechanism as the
        // thinking-disable override above, not a new one. Originally scoped to just "-pro" (flash
        // ran clean in earlier tests), but flash hit the identical error in later real use — the
        // proxy load-balances a "model group" across multiple backends, and apparently more than
        // one of them shares this constraint, so the whole harness-punchbag-* family gets it now
        // rather than allow-listing one more exact name each time a different tier hits the same
        // wall.
        if (m.startsWith("harness-punchbag")) {
            return java.util.Map.of("temperature", 1);
        }
        return null;
    }

    private RegnexeAgent buildAgent(SessionContext ctx, RexConfig config, Terminal terminal,
                                    CliRenderer renderer,
                                    RexDatabase db, Runnable pauseAction) {
        RexConfig.AgentConfig ac = config.getAgent();
        WorkspaceContext workspace = ctx.getWorkspace();
        var builder = agentBuilder
                .withDefaultModel(config.getModel().getVendor(), config.effectiveModel(),
                        deepseekThinkingKwargs(config.effectiveModel()))
                .withEventListener(new CliEventListener(renderer))
                .withMaxRounds(ac.getMaxRounds())
                .withMaxAgentIterations(ac.getMaxAgentIterations())
                .withMaxConsecutiveToolFailures(ac.getMaxConsecutiveToolFailures())
                .withSessionBufferSize(ac.getSessionBufferSize())
                .withSessionCompactPeriod(ac.getSessionCompactPeriod())
                .withAgentContext(SlidingWindowContext.builder()
                        .windowSize(ac.getContextWindowSize())
                        .summarizer(buildSummarizerModel(config))
                        .build())
                .withTool(
                        FileTools.readFile(workspace),
                        FileTools.listFiles(workspace),
                        FileTools.searchFiles(workspace),
                        FileTools.writeFile(workspace, renderer, pauseAction),
                        FileTools.editFile(workspace, renderer, pauseAction),
                        BashTool.bash(workspace, config.getTools().getBash(), terminal, renderer, pauseAction)
                );

        // Optional per-role model overrides — same vendor as the main model unless
        // planner_vendor/reflector_vendor names a different one (see
        // RexConfig.ModelConfig.plannerName/plannerVendor javadoc for why these two roles
        // specifically are a reasonable place to spend more on a stronger model, possibly on a
        // different vendor entirely). API keys for a different vendor are wired in main() —
        // see wireRoleApiKey().
        String plannerName = config.getModel().getPlannerName();
        if (plannerName != null && !plannerName.isBlank()) {
            builder = builder.withPlannerModel(config.effectivePlannerVendor(), plannerName,
                    deepseekThinkingKwargs(plannerName));
        }
        String reflectorName = config.getModel().getReflectorName();
        if (reflectorName != null && !reflectorName.isBlank()) {
            builder = builder.withReflectorModel(config.effectiveReflectorVendor(), reflectorName,
                    deepseekThinkingKwargs(reflectorName));
        }

        // MCP servers (direct + Plugin-carried, see connectMcpServers()).
        // Destroy any previously-connected client before replacing it: each holds real OS
        // resources (stdio child processes, sse/http connections) that must not leak across
        // rebuilds (e.g. /mcp enable triggering AGENT_REBUILT).
        if (activeMcpClient != null) {
            try { activeMcpClient.destroy(); } catch (Exception ignored) {}
            activeMcpClient = null;
        }
        List<Tool> mcpTools = connectMcpServers(workspace, renderer, pauseAction);
        if (!mcpTools.isEmpty()) {
            builder = builder.withTool(mcpTools.toArray(new Tool[0]));
        }

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

    // ── MCP ──────────────────────────────────────────────────────────────────
    // Two sources: directly-configured servers (.rex/mcp.json, independent /mcp enable|disable
    // switch) and Plugin-carried servers (<plugin-dir>/mcp.json, no independent switch — lifecycle
    // follows the owning plugin's own enable/disable state). Both connect through one shared
    // McpClient; a Plugin-carried server's internal key and capabilityId prefix is
    // "<pluginId>_<server>" (not just the bare server name declared in its mcp.json) so two
    // plugins that happen to use the same internal server name (e.g. both call it "github")
    // can't collide with each other or with a directly-configured server of the same name.

    /** One MCP server declared inside an installed plugin's own {@code mcp.json}. */
    private record PluginMcpServer(String internalKey, String pluginGlobalId, ServerConfig config) {}

    /**
     * Connects every enabled MCP server — direct and Plugin-carried — and adapts each discovered
     * tool into a confirmation-gated {@link Tool} — see {@link McpTools#forServer}. Stores the
     * connected client in {@link #activeMcpClient} so {@code /mcp list} can report live status and
     * so it can be torn down before the next rebuild. Returns an empty list (and connects nothing)
     * when no server is configured anywhere — the common case for a project that doesn't use MCP
     * at all shouldn't pay for a McpClient or a temp file.
     */
    private List<Tool> connectMcpServers(WorkspaceContext workspace, CliRenderer renderer, Runnable pauseAction) {
        Map<String, ServerConfig> servers = new LinkedHashMap<>(resolveEnabledMcpServerConfigs(workspace));
        servers.putAll(resolveEnabledPluginMcpServerConfigs(workspace));
        if (servers.isEmpty()) return List.of();

        McpConfig mcpConfig = new McpConfig();
        mcpConfig.mcpServers = servers;

        Path tempConfig;
        try {
            tempConfig = Files.createTempFile("rex-mcp-config-", ".json");
            tempConfig.toFile().deleteOnExit();
            new ObjectMapper().writeValue(tempConfig.toFile(), mcpConfig);
        } catch (IOException e) {
            System.err.println("[warn] Failed to write merged MCP config: " + e.getMessage());
            return List.of();
        }

        // McpClient(configPath)'s constructor does the env-var substitution (${VAR} in command/
        // args/env/url) via its own loadConfig() → processEnvironmentVariables() — that's why the
        // temp file above is written with the raw, unsubstituted values from mcp.json.
        McpClient client = new McpClient(tempConfig.toString());
        this.activeMcpClient = client;

        List<Tool> tools = new ArrayList<>();
        for (String serverName : servers.keySet()) {
            // A server that failed to connect just contributes zero tools here (McpClient logs
            // its own error and omits it from listAllTools()) — one bad server doesn't block the
            // others, matching McpClient.initializeFromConfig()'s existing per-server try/catch.
            tools.addAll(McpTools.forServer(client, serverName, serverName, renderer, pauseAction));
        }
        return tools;
    }

    /** {@link #mergeMcpServerConfigs}, filtered to servers not explicitly disabled via {@code <server>@mcp} in enabled.yml — absent means enabled, same convention as plugins. */
    private Map<String, ServerConfig> resolveEnabledMcpServerConfigs(WorkspaceContext workspace) {
        Map<String, ServerConfig> merged = mergeMcpServerConfigs(workspace);
        if (merged.isEmpty()) return merged;
        Map<String, Boolean> resolvedEnabled = resolveEnabledAcrossScopes(workspace);
        Map<String, ServerConfig> filtered = new LinkedHashMap<>();
        merged.forEach((name, cfg) -> {
            if (resolvedEnabled.getOrDefault(name + "@mcp", true)) filtered.put(name, cfg);
        });
        return filtered;
    }

    /**
     * Reads {@code ~/.rex/mcp.json} and {@code <project>/.rex/mcp.json} and merges their
     * {@code mcpServers} maps by server name — project overrides user, same direction as
     * {@code enabled.yml}'s scope priority. Unfiltered by enabled state (see
     * {@link #resolveEnabledMcpServerConfigs} for that) — used directly by {@code /mcp list} so
     * disabled servers still show up (as "disabled", not silently missing).
     */
    private Map<String, ServerConfig> mergeMcpServerConfigs(WorkspaceContext workspace) {
        Map<String, ServerConfig> merged = new LinkedHashMap<>();
        mergeMcpServersFrom(merged, Path.of(System.getProperty("user.home"), ".rex", "mcp.json"));
        mergeMcpServersFrom(merged, workspace.primaryRoot().resolve(".rex").resolve("mcp.json"));
        return merged;
    }

    private void mergeMcpServersFrom(Map<String, ServerConfig> target, Path mcpJson) {
        if (!Files.isRegularFile(mcpJson)) return;
        try {
            McpConfig cfg = new ObjectMapper().readValue(mcpJson.toFile(), McpConfig.class);
            if (cfg.mcpServers != null) target.putAll(cfg.mcpServers);
        } catch (IOException e) {
            System.err.println("[warn] Failed to parse " + mcpJson + ": " + e.getMessage());
        }
    }

    /**
     * Every MCP server declared inside any installed plugin's own root-level {@code mcp.json} —
     * unfiltered by the plugin's enabled state (see {@link #resolveEnabledPluginMcpServerConfigs}
     * for that), used directly by {@code /mcp list} so a disabled plugin's servers still show up.
     * {@code internalKey}/capabilityId prefix is {@code <pluginId>_<server>}, scanned in the same
     * order (and hence the same "first-scanned wins" duplicate-id precedent) as
     * {@link #listAllInstalledEntriesInScanOrder}.
     */
    private List<PluginMcpServer> listPluginMcpServers(WorkspaceContext workspace) {
        List<PluginMcpServer> result = new ArrayList<>();
        for (InstalledPluginEntry entry : listAllInstalledEntriesInScanOrder(workspace)) {
            Path mcpJson = entry.resolvedDir().resolve("mcp.json");
            if (!Files.isRegularFile(mcpJson)) continue;
            Map<String, ServerConfig> declared = new LinkedHashMap<>();
            mergeMcpServersFrom(declared, mcpJson);
            declared.forEach((serverName, cfg) ->
                    result.add(new PluginMcpServer(entry.pluginId() + "_" + serverName, entry.globalId(), cfg)));
        }
        return result;
    }

    /** {@link #listPluginMcpServers}, filtered to servers whose owning plugin is enabled — a Plugin-carried server has no independent switch, it's on exactly when its plugin is. */
    private Map<String, ServerConfig> resolveEnabledPluginMcpServerConfigs(WorkspaceContext workspace) {
        Map<String, Boolean> resolvedEnabled = resolveEnabledAcrossScopes(workspace);
        Map<String, ServerConfig> result = new LinkedHashMap<>();
        for (PluginMcpServer s : listPluginMcpServers(workspace)) {
            if (resolvedEnabled.getOrDefault(s.pluginGlobalId(), true)) {
                result.put(s.internalKey(), s.config());
            }
        }
        return result;
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
                          /history [name]    show a session's conversation history (default: current)
                          /add-dir <path>    add a workspace directory for this session
                          /dirs              list all workspace directories
                          /skills            list available skills
                          /<skill name> [args]  run a skill directly
                          /plugin install <local-path> [--marketplace <name>] [--scope user|project]
                          /plugin uninstall <plugin-id>@<marketplace> [--scope user|project]
                          /plugin enable|disable <plugin-id>@<marketplace> [--scope user|project]
                          /plugin list       list installed plugins (both scopes) with enabled state
                          /mcp list          list configured MCP servers (direct and Plugin-carried), status, tools
                          /mcp enable|disable <server-name> [--scope user|project]
                                             (declare servers in ~/.rex/mcp.json or <project>/.rex/mcp.json;
                                              a Plugin-carried server has no independent switch — use /plugin instead)
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

            case "/history" -> {
                if (db == null) {
                    out.println("  [warn] Database unavailable");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                String targetSession = parts.length > 1 && !parts[1].isBlank()
                        ? parts[1].trim() : ctx.getSessionName();
                java.util.List<org.salt.jlangchain.core.history.HistoryInfos> turns;
                try {
                    turns = db.loadConversation(targetSession);
                } catch (Exception e) {
                    out.println("  [error] " + e.getMessage());
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                if (turns.isEmpty()) {
                    out.printf("  No conversation history for session '%s' (never resumed, "
                            + "or the name is misspelled — check /sessions).%n", targetSession);
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                DateTimeFormatter histFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                out.printf("  === %s (%d turn%s) ===%n", targetSession, turns.size(),
                        turns.size() == 1 ? "" : "s");
                for (org.salt.jlangchain.core.history.HistoryInfos turn : turns) {
                    String when = LocalDateTime
                            .ofInstant(Instant.ofEpochMilli(turn.getCreatedAt()), ZoneId.systemDefault())
                            .format(histFmt);
                    out.printf("  [%s] %s%n", when, turn.getType());
                    if (turn.getMessages() != null) {
                        for (org.salt.jlangchain.core.message.BaseMessage msg : turn.getMessages()) {
                            String content = msg.getContent() != null ? msg.getContent() : "";
                            if (content.length() > 2000) {
                                content = content.substring(0, 2000) + "... [truncated, "
                                        + content.length() + " chars total]";
                            }
                            out.printf("    %-10s %s%n", msg.getRole() + ":", content.replace("\n", "\n               "));
                        }
                    }
                    out.println();
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

            case "/mcp" -> {
                return handleMcpCommand(parts.length > 1 ? parts[1].trim() : "", out, ctx);
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

    // ── /mcp list|enable|disable ────────────────────────────────────────────
    // A separate command family from /plugin, not an alias — same underlying enabled.yml
    // read/write, but "MCP server" and "Plugin" are different mental models for the user.
    // enable/disable return AGENT_REBUILT so the next loop iteration re-runs buildAgent(), which
    // reconnects/disconnects accordingly.

    private SlashResult handleMcpCommand(String args, PrintWriter out, SessionContext ctx) {
        String[] tokens = args.isBlank() ? new String[0] : args.split("\\s+");
        if (tokens.length == 0) {
            out.println("  Usage: /mcp list|enable|disable ...  (type /help for details)");
            out.flush();
            return SlashResult.CONTINUE;
        }
        String subcmd = tokens[0];
        Map<String, String> flags = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            if ("--scope".equals(tokens[i]) && i + 1 < tokens.length) {
                flags.put("scope", tokens[++i]);
            } else {
                positional.add(tokens[i]);
            }
        }
        boolean userScope = "user".equalsIgnoreCase(flags.getOrDefault("scope", "project"));
        Path rexRoot = userScope
                ? Path.of(System.getProperty("user.home"), ".rex")
                : ctx.getWorkspace().primaryRoot().resolve(".rex");
        Path enabledYml = rexRoot.resolve("enabled.yml");

        switch (subcmd) {
            case "list" -> {
                Map<String, ServerConfig> direct = mergeMcpServerConfigs(ctx.getWorkspace());
                List<PluginMcpServer> pluginServers = listPluginMcpServers(ctx.getWorkspace());
                if (direct.isEmpty() && pluginServers.isEmpty()) {
                    out.println("  No MCP servers configured. Add one to ~/.rex/mcp.json, <project>/.rex/mcp.json, or a plugin's own mcp.json.");
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                Map<String, Boolean> resolvedEnabled = resolveEnabledAcrossScopes(ctx.getWorkspace());
                Map<String, ServerStatus> statuses = activeMcpClient != null
                        ? activeMcpClient.getServerStatuses() : Map.of();
                Map<String, List<ToolDesc>> allTools = activeMcpClient != null
                        ? activeMcpClient.listAllTools() : Map.of();
                for (String name : direct.keySet()) {
                    boolean enabled = resolvedEnabled.getOrDefault(name + "@mcp", true);
                    printMcpServerStatus(out, name, name, enabled, statuses.get(name), allTools);
                }
                for (PluginMcpServer s : pluginServers) {
                    boolean enabled = resolvedEnabled.getOrDefault(s.pluginGlobalId(), true);
                    printMcpServerStatus(out, s.internalKey() + "  (via plugin " + s.pluginGlobalId() + ")",
                            s.internalKey(), enabled, statuses.get(s.internalKey()), allTools);
                }
                out.flush();
                return SlashResult.CONTINUE;
            }

            case "enable", "disable" -> {
                if (positional.isEmpty()) {
                    out.printf("  Usage: /mcp %s <server-name> [--scope user|project]%n", subcmd);
                    out.flush();
                    return SlashResult.CONTINUE;
                }
                String serverName = positional.get(0);
                // Plugin-carried servers have no independent switch — connectMcpServers() never
                // reads a <server>@mcp key for these, it only looks at the owning plugin's own
                // enabled state, so writing one here would silently do nothing.
                for (PluginMcpServer s : listPluginMcpServers(ctx.getWorkspace())) {
                    if (s.internalKey().equals(serverName)) {
                        out.printf("  '%s' is carried by plugin %s — it has no independent switch, "
                                        + "use: /plugin enable|disable %s%n",
                                serverName, s.pluginGlobalId(), s.pluginGlobalId());
                        out.flush();
                        return SlashResult.CONTINUE;
                    }
                }
                boolean value = "enable".equals(subcmd);
                enabledStateWriter.setEnabled(enabledYml, serverName + "@mcp", value);
                out.println("  " + (value ? "Enabled " : "Disabled ") + serverName + " (scope: " + rexRoot + ")");
                out.flush();
                return SlashResult.AGENT_REBUILT;
            }

            default -> {
                out.println("  Unknown /mcp subcommand: " + subcmd + "  (expected list|enable|disable)");
                out.flush();
                return SlashResult.CONTINUE;
            }
        }
    }

    /**
     * One {@code /mcp list} row: {@code label} is what's printed after the state tag (may include
     * a "via plugin ..." suffix for a Plugin-carried server); {@code statusKey} is the actual
     * {@link McpClient} internal key used to look up connection status/tools (always the bare
     * key, never the decorated label).
     */
    private void printMcpServerStatus(PrintWriter out, String label, String statusKey, boolean enabled,
                                      ServerStatus status, Map<String, List<ToolDesc>> allTools) {
        String state = !enabled ? "disabled"
                : (status != null && status.connected) ? "connected"
                : "connection failed";
        out.printf("  [%s] %s%n", state, label);
        if (enabled && status != null && status.connected) {
            for (ToolDesc t : allTools.getOrDefault(statusKey, List.of())) {
                out.printf("      %s_%s  %s%n", statusKey, t.getName(), truncate(t.getDescription(), 60));
            }
        } else if (enabled && status != null && status.lastError != null) {
            out.printf("      error: %s%n", status.lastError);
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
