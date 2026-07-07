# regnexe-cli 设计文档

> 基于 regnexe-agent 框架构建的 Claude Code 风格 CLI 工具

---

## 一、项目定位

使用 `regnexe-agent` 框架，构建一个面向开发者的交互式命令行 AI 助手，具备：
- 交互式 REPL 对话（多轮上下文，多 session 并行，上下文隔离）
- 跨多目录的文件读写、代码编辑能力
- Shell 命令执行（交互式确认模式）
- 三层会话存储持久化（SQLite）
- 实时事件驱动输出 + 每次任务 token 消耗统计

---

## 二、整体架构

```
[用户终端]
    │
    ▼
[REPL 循环]  ← JLine3（行编辑、历史记录、Tab补全）
    │  携带 workspaceContext 信息注入 goal
    ▼
[RegnexeAgent.execute(input)]  ← 同步阻塞，每轮用户输入触发一次
    │
    ▼
[Search → Plan → Execute → Reflect]
    │           │
    │     ┌─────┴───────────────────────────────┐
    │     │  工具集（共享 WorkspaceContext 引用）  │
    │     └────────────────────────────────────┘
    │
    ▼
[CliEventListener]
  ├── 实时打印工具调用 / 结果（TOOL_CALLED / TOOL_RESULT）
  ├── 阶段提示（PLAN_STARTED / EXECUTION_STARTED）
  ├── LLM 最终答案（LLM_RESPONDED）
  └── 任务 token 汇总（TASK_TOKEN_SUMMARY，框架自动聚合）
```

---

## 三、配置文件

路径：`~/.rex/config.yml`，使用 SnakeYAML 解析（regnexe-agent 已包含此依赖）。

```yaml
model:
  vendor: anthropic            # anthropic | aliyun | openai | ...
  name: claude-sonnet-4-6
  api_key: ${REX_API_KEY}      # 支持 ${ENV_VAR} 插值，也可直接写值

agent:
  max_rounds: 10
  max_agent_iterations: 20
  session_buffer_size: 10      # ConversationSummaryBufferMemory 缓冲轮次数
  context_window_size: 8       # SlidingWindowContext 工具调用链窗口

tools:
  bash:
    require_confirmation: true  # false = 仅黑名单，不弹确认
    extra_blocked:              # 用户追加的硬拒命令（前缀匹配）
      - "git push --force"
      - "npm publish"
```

**加载逻辑**：
1. 读 `~/.rex/config.yml`
2. `${VAR}` 占位符替换为对应环境变量值
3. 环境变量 `REX_API_KEY` / `REX_MODEL` 可覆盖 config 值（优先级更高）

---

## 四、多目录工作区（WorkspaceContext）

### 设计目标

- 默认根目录 = CLI 启动时的 `System.getProperty("user.dir")`
- REPL 内 `/add-dir <path>` 动态追加目录
- 所有工具（read_file、write_file、search_files 等）共享同一个 `WorkspaceContext` 引用，追加目录后立即生效

### WorkspaceContext

```java
public class WorkspaceContext {
    private final List<Path> roots = new CopyOnWriteArrayList<>();

    public WorkspaceContext(String startupDir) {
        roots.add(Path.of(startupDir).toAbsolutePath().normalize());
    }

    public void addRoot(String dir) {
        Path p = Path.of(dir).toAbsolutePath().normalize();
        if (!roots.contains(p)) roots.add(p);
    }

    public Path primaryRoot() { return roots.get(0); }
    public List<Path> allRoots() { return Collections.unmodifiableList(roots); }

    // 读操作：按顺序尝试每个 root，返回第一个存在的文件
    public Path resolveForRead(String relativePath) throws IOException {
        Path input = Path.of(relativePath.trim());
        if (input.isAbsolute()) {
            for (Path root : roots) {
                if (input.normalize().startsWith(root)) return input.normalize();
            }
            throw new IOException("Absolute path outside all registered roots: " + relativePath);
        }
        for (Path root : roots) {
            Path candidate = root.resolve(input).normalize();
            if (candidate.startsWith(root) && Files.exists(candidate)) return candidate;
        }
        throw new IOException("File not found in any registered root: " + relativePath);
    }

    // 写操作：解析到主目录（或路径明确属于哪个 root）
    public Path resolveForWrite(String relativePath) {
        Path input = Path.of(relativePath.trim());
        if (input.isAbsolute()) {
            Path normalized = input.normalize();
            for (Path root : roots) {
                if (normalized.startsWith(root)) return normalized;
            }
            throw new IllegalArgumentException("Write path outside all registered roots: " + relativePath);
        }
        return primaryRoot().resolve(input).normalize();
    }

    public String describeRoots() {
        return roots.stream().map(Path::toString).collect(Collectors.joining(", "));
    }
}
```

### LLM 如何感知多目录

工具描述是静态字符串，不能动态变化。通过在 REPL 中将目录信息注入 `TaskRequest.goal` 让 LLM 感知：

```java
// REPL 内，agent.execute() 之前
String goal = input;
if (workspace.allRoots().size() > 1) {
    goal = "[Available workspace roots: " + workspace.describeRoots() + "]\n\n" + goal;
}
req.setGoal(goal);
```

单目录时不注入，保持 goal 干净；多目录时 LLM 会在构造路径时优先参考这个上下文。

---

## 五、工具清单

所有工具构造时注入同一个 `WorkspaceContext` 实例引用。

| Tool 名 | 描述 | 实现要点 |
|---|---|---|
| `read_file` | 读文件内容，支持 offset/limit 分页 | `resolveForRead()` 遍历所有 root |
| `write_file` | 写/覆盖文件 | `resolveForWrite()` 写到对应 root |
| `edit_file` | 精确字符串替换（old_string → new_string），old_string 需唯一 | 读→替换→写，替换前校验唯一性 |
| `list_files` | 列目录，支持 glob；无参数时列所有 root 的一级目录 | `Files.walk()` + `PathMatcher` |
| `search_files` | 全文搜索，遍历所有 root | `rg` fallback Java |
| `glob` | 按模式在所有 root 中找文件 | `PathMatcher` 遍历 roots |
| `bash` | 执行 shell 命令，交互式确认（见第六节） | `ProcessBuilder` + 确认对话 |

参考实现：`Example09CodeWorkspaceComponentTest.java` 中已有 `read_file`、`search_code`、`run_command` 的完整实现，可直接复用。

---

## 六、Bash 工具：交互式确认

**执行时序**：tool `func` 在 `agent.execute()` 内同步运行，此时 `readLine()` 已返回，terminal 处于 cooked mode，可安全读写。

```
reader.readLine("rex> ")    ← JLine3 raw mode
     ↓ 用户回车，返回
agent.execute(input)        ← 同步阻塞
     └─ bash tool func 内:
          terminal.writer().print(...)  ← 安全
          terminal.reader().readLine()  ← 安全
```

**三层控制**：

```
1. 硬拒黑名单（永不执行，config 可追加）
   内置：rm -rf /、rm -rf ~、:(){ :|:& };:、dd if=、mkfs、shutdown、reboot
        ↓
2. 交互式确认（每次显示命令，等待 y/N/always）
        ↓
3. session 级免确认（选 always 后，同一基础命令当前进程内免提示）
```

**实现骨架**：

```java
public class BashTool {
    private final Set<String> sessionAllowed = new HashSet<>();
    private final Terminal terminal;
    private final List<String> blockedPrefixes;  // 来自 config

    public Tool build() {
        return Tool.builder()
            .name("bash")
            .description("Execute a shell command in the current working directory. " +
                         "Requires user confirmation before running.")
            .params("command: String -- the shell command")
            .func(args -> {
                String cmd = extractCommand(args);
                if (isBlocked(cmd)) return "ERROR: command blocked: " + cmd;
                if (sessionAllowed.contains(extractBase(cmd))) return run(cmd);

                PrintWriter out = terminal.writer();
                out.printf("%n  ╭─ bash ─────────────────────────────────%n");
                out.printf("  │  %s%n", cmd);
                out.printf("  ╰────────────────────────────────────────%n");
                out.print("  Allow? [y/N/always] > ");
                out.flush();

                String answer = terminal.reader().readLine().trim().toLowerCase();
                return switch (answer) {
                    case "y", "yes"  -> run(cmd);
                    case "always"    -> { sessionAllowed.add(extractBase(cmd)); yield run(cmd); }
                    default          -> "Command rejected by user.";
                };
            })
            .build();
    }
}
```

---

## 七、实时输出：CliEventListener

不修改框架，继承 `AbstractEventListener` 实现 `CliEventListener`。

**现有事件已足够**：
- `TOOL_CALLED` / `TOOL_RESULT`：工具调用时**实时**派发 ✓
- `LLM_RESPONDED` 等：完整 LLM 响应到达后派发
- `TASK_TOKEN_SUMMARY`：框架通过 `TokenAggregatingEventListener` 自动在 `AGENT_COMPLETED` 前派发，包含 total tokens、by_model 分布、elapsed_ms、llm_ms

```java
public class CliEventListener extends AbstractEventListener {

    private final Terminal terminal;

    public CliEventListener(Terminal terminal) {
        super(false, true);  // 关闭原始 token 事件，开启 LLM 事件
        this.terminal = terminal;
    }

    @Override
    public void onEvent(AgentEvent event) {
        PrintWriter out = terminal.writer();
        switch (event.getType()) {

            case TOOL_CALLED -> {
                out.printf("%n  ▶ %s%n", event.getText());
                out.flush();
            }
            case TOOL_RESULT -> {
                out.printf("  ◀ %s%n", truncate(event.getText(), 300));
                out.flush();
            }

            case PLAN_STARTED -> {
                out.print("  ⟳ Thinking...");
                out.flush();
            }
            case PLAN_COMPLETED -> {
                out.printf("\r  ✓ Plan ready  %n");
                out.flush();
            }
            case EXECUTION_STARTED -> {
                out.printf("  ⟳ Executing...%n");
                out.flush();
            }

            case LLM_RESPONDED,
                 SKILL_LLM_RESPONDED,
                 AGENT_LLM_RESPONDED -> {
                out.printf("%n%s%n", event.getText());
                out.flush();
            }

            // 框架自动聚合，每次 execute() 结束前派发一次
            case TASK_TOKEN_SUMMARY -> {
                out.printf("%n  ─────────────────────────────────────────%n");
                out.printf("  %s%n", formatTokenSummary(event));
                out.flush();
            }

            default -> {}
        }
    }

    private String formatTokenSummary(AgentEvent event) {
        // event.getText() 是 JSON：{total:{...}, by_model:{...}, elapsed_ms:N, llm_ms:N}
        // 解析后格式化为：Tokens: 1234 in / 567 out | 4.5s (LLM 3.9s)
        try {
            var node = new ObjectMapper().readTree(event.getText());
            var total = node.get("total");
            long prompt     = total.path("promptTokens").asLong();
            long completion = total.path("completionTokens").asLong();
            long toolCalls  = total.path("toolCalls").asLong();
            long elapsedMs  = node.path("elapsed_ms").asLong();
            long llmMs      = node.path("llm_ms").asLong();
            return String.format("Tokens: %d in / %d out | tool calls: %d | %.1fs (LLM %.1fs)",
                    prompt, completion, toolCalls, elapsedMs / 1000.0, llmMs / 1000.0);
        } catch (Exception e) {
            return event.getText();
        }
    }
}
```

---

## 八、REPL 入口设计

```java
@SpringBootApplication
public class CliMain implements CommandLineRunner {

    @Autowired RegnexeAgentBuilder builder;

    public void run(String... args) throws Exception {
        RexConfig config = RexConfig.load();          // ~/.rex/config.yml
        Terminal terminal = TerminalBuilder.terminal();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .history(new DefaultHistory())
                .build();

        // 初始 session
        SessionContext ctx = resolveSession(args, config);  // 解析 --session / --resume 参数
        RegnexeAgent agent  = buildAgent(ctx, config, terminal);
        WorkspaceContext workspace = ctx.getWorkspace();

        terminal.writer().printf("rex [%s] %s%n", ctx.getSessionName(), workspace.primaryRoot());

        while (true) {
            String input = reader.readLine("rex> ");
            if (input == null) break;
            input = input.trim();
            if (input.isEmpty()) continue;

            // 内置斜杠命令
            if (input.startsWith("/")) {
                SlashCommandResult result = handleSlash(input, ctx, terminal, config);
                if (result == SlashCommandResult.EXIT) break;
                if (result == SlashCommandResult.AGENT_REBUILT) {
                    agent = buildAgent(ctx, config, terminal);  // session 切换后重建
                    workspace = ctx.getWorkspace();
                }
                continue;
            }

            // 注入工作区信息（多目录时）
            String goal = workspace.allRoots().size() > 1
                ? "[Available workspace roots: " + workspace.describeRoots() + "]\n\n" + input
                : input;

            TaskRequest req = new TaskRequest();
            req.setGoal(goal);
            req.setSessionId(ctx.getSessionId());
            agent.execute(req);
        }
    }

    private RegnexeAgent buildAgent(SessionContext ctx, RexConfig config, Terminal terminal) {
        WorkspaceContext workspace = new WorkspaceContext(System.getProperty("user.dir"));
        ctx.setWorkspace(workspace);   // 切换 session 时重置工作区

        BashTool bashTool = new BashTool(terminal, config.getTools().getBash());

        return builder
                .withDefaultModel(config.getModel().getVendor(), config.getModel().getName())
                .withTool(
                    new ReadFileTool(workspace).build(),
                    new WriteFileTool(workspace).build(),
                    new EditFileTool(workspace).build(),
                    new ListFilesTool(workspace).build(),
                    new SearchFilesTool(workspace).build(),
                    new GlobTool(workspace).build(),
                    bashTool.build()
                )
                .withEventListener(new CliEventListener(terminal))
                .withSessionStorage(new SqliteConversationStorage(ctx.getSessionId()))
                .withTaskStore(new SqliteTaskStore())
                .withAgentContext(SlidingWindowContext.builder()
                        .windowSize(config.getAgent().getContextWindowSize()).build())
                .withMaxRounds(config.getAgent().getMaxRounds())
                .withMaxAgentIterations(config.getAgent().getMaxAgentIterations())
                .build();
    }
}
```

**Session 切换时重建 Agent 的理由**：
- 每个 session 有独立 `sessionId` → `SqliteConversationStorage` 分区不同
- BashTool 的 `sessionAllowed` 集合应随 session 切换重置，不应跨 session 继承权限
- WorkspaceContext 随 session 切换重置为主目录（不同 session 常对应不同项目）
- Agent 实例轻量（仅做依赖注入，无连接池），重建成本极低

**命令行参数**：

```bash
rex                          # 新建匿名 session，进入 REPL
rex --session my-project     # 按名字找 session，存在则 resume，不存在则新建
rex --resume <session-id>    # 精确 resume 某个 session（PAUSED 状态任务可续跑）
rex sessions list            # 列出所有 session（表格：name / id / last-used / working-dir）
rex sessions delete <id>     # 删除某个 session
rex "帮我分析这段代码"        # 单次执行模式，完成后退出（不进 REPL）
```

**REPL 内斜杠命令**：

```
/add-dir <path>      追加工作目录（立即对所有工具生效）
/dirs                列出当前所有工作目录
/sessions            列出所有 session
/switch <name>       切换到另一个 session（重建 Agent，重置工作区）
/clear               清除当前 session 的对话历史
/pause               暂停当前任务（agent.pause()，状态写入 SQLite，可用 resume 恢复）
/exit                退出
```

---

## 九、三层会话存储（SQLite + JDBI）

### 依赖

```xml
<dependency>
    <groupId>org.jdbi</groupId>
    <artifactId>jdbi3-sqlobject</artifactId>
    <version>3.45.4</version>
</dependency>
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.47.1.0</version>
</dependency>
```

### 数据库初始化

路径：`~/.rex/sessions.db`，WAL 模式支持多进程并发。

```sql
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

-- session 元数据
CREATE TABLE IF NOT EXISTS sessions (
    session_id  TEXT PRIMARY KEY,
    name        TEXT,
    working_dir TEXT,
    model       TEXT,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL
);

-- Layer 1: ConversationSummaryBufferMemory 读写
-- type: NORMAL（近期完整轮次）| SUMMARY（更早的 LLM 压缩摘要）
CREATE TABLE IF NOT EXISTS conversation_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    role        TEXT    NOT NULL,
    content     TEXT    NOT NULL,
    seq         INTEGER NOT NULL,
    created_at  INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(session_id)
);
CREATE INDEX IF NOT EXISTS idx_conv_session ON conversation_history(session_id);

-- Layer 2: TaskStore，TaskExecutionState 序列化为 JSON
CREATE TABLE IF NOT EXISTS tasks (
    task_id     TEXT    PRIMARY KEY,
    session_id  TEXT    NOT NULL,
    status      TEXT    NOT NULL,
    state_json  TEXT    NOT NULL,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(session_id)
);
CREATE INDEX IF NOT EXISTS idx_tasks_session_status ON tasks(session_id, status);
```

### JDBI DAO 接口（SqlObject 风格）

```java
public interface SessionDao {
    @SqlUpdate("""
        INSERT OR REPLACE INTO sessions (session_id, name, working_dir, model, created_at, updated_at)
        VALUES (:sessionId, :name, :workingDir, :model, :createdAt, :updatedAt)
        """)
    void upsert(@BindBean SessionRow row);

    @SqlQuery("SELECT * FROM sessions ORDER BY updated_at DESC")
    @RegisterBeanMapper(SessionRow.class)
    List<SessionRow> listAll();

    @SqlQuery("SELECT * FROM sessions WHERE name = :name LIMIT 1")
    @RegisterBeanMapper(SessionRow.class)
    Optional<SessionRow> findByName(@Bind("name") String name);
}

public interface ConversationDao {
    @SqlUpdate("""
        INSERT INTO conversation_history (session_id, type, role, content, seq, created_at)
        VALUES (:sessionId, :type, :role, :content, :seq, :createdAt)
        """)
    void insert(@BindBean ConversationRow row);

    @SqlQuery("SELECT * FROM conversation_history WHERE session_id = :sid ORDER BY id")
    @RegisterBeanMapper(ConversationRow.class)
    List<ConversationRow> findBySession(@Bind("sid") String sessionId);

    @SqlUpdate("DELETE FROM conversation_history WHERE session_id = :sid")
    void deleteBySession(@Bind("sid") String sessionId);

    @SqlUpdate("DELETE FROM conversation_history WHERE session_id = :sid AND type = 'NORMAL'")
    void deleteNormalBySession(@Bind("sid") String sessionId);  // 保留 summary，清除近期轮次
}

public interface TaskDao {
    @SqlUpdate("""
        INSERT OR REPLACE INTO tasks (task_id, session_id, status, state_json, created_at, updated_at)
        VALUES (:taskId, :sessionId, :status, :stateJson, :createdAt, :updatedAt)
        """)
    void upsert(@BindBean TaskRow row);

    @SqlQuery("SELECT * FROM tasks WHERE task_id = :id")
    @RegisterBeanMapper(TaskRow.class)
    Optional<TaskRow> findById(@Bind("id") String taskId);

    @SqlQuery("SELECT * FROM tasks WHERE session_id = :sid AND status = 'PAUSED' ORDER BY updated_at DESC")
    @RegisterBeanMapper(TaskRow.class)
    List<TaskRow> findResumable(@Bind("sid") String sessionId);
}
```

### 三层定义

| 层 | 作用 | 实现类 | 存储 |
|---|---|---|---|
| Layer 1 Session Memory | 跨 execute() 的对话历史与摘要 | `SqliteConversationStorage` 实现 `ConversationStorage` | `conversation_history` 表 |
| Layer 2 Task Ledger | 单次 execute() 内逐轮 plan/exec/reflect 记录，支持 pause/resume | `SqliteTaskStore` 实现 `TaskStore` | `tasks` 表（`state_json`） |
| Layer 3 Agent Context | 单轮工具调用链的上下文窗口 | `SlidingWindowContext`（框架提供） | 纯内存，无需持久化 |

### 文件布局

```
~/.rex/
├── config.yml          ← 模型 / agent / 工具配置
└── sessions.db         ← WAL 模式，所有 session 共享，按 session_id 分区隔离
```

多进程并行：WAL 模式下各进程写各自的 `session_id` 分区，SQLite 行锁天然隔离。

---

## 十、打包方案

### 方案 A（推荐初期）：Fat JAR + Shell 包装器

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <mainClass>org.salt.regnexe.cli.CliMain</mainClass>
    </configuration>
</plugin>
```

```bash
mvn package -DskipTests
# 产物：target/regnexe-cli.jar

sudo mkdir -p /opt/regnexe
sudo cp target/regnexe-cli.jar /opt/regnexe/
sudo tee /usr/local/bin/rex <<'EOF'
#!/bin/bash
exec java $JAVA_OPTS -jar /opt/regnexe/regnexe-cli.jar "$@"
EOF
sudo chmod +x /usr/local/bin/rex
```

缺点：需要 JVM，冷启动约 1-2 秒。后期可用 GraalVM native-image 消除。

### 方案 B（后期）：GraalVM Native Image

产物为原生二进制，启动 <100ms。Spring Boot 反射较重，需补充 `reflect-config.json`，留待打磨期实施。

---

## 十一、目录结构规划

```
regnexe-cli/
├── pom.xml
├── design.md
└── src/main/
    ├── java/org/salt/regnexe/cli/
    │   ├── CliMain.java                        ← Spring Boot 入口 + REPL 循环
    │   ├── config/
    │   │   └── RexConfig.java                  ← ~/.rex/config.yml 加载与解析
    │   ├── session/
    │   │   └── SessionContext.java             ← 当前 session 状态（id/name/workspace）
    │   ├── workspace/
    │   │   └── WorkspaceContext.java           ← 多目录管理，线程安全
    │   ├── event/
    │   │   └── CliEventListener.java           ← 实时输出 + token 汇总
    │   ├── tools/
    │   │   ├── ReadFileTool.java
    │   │   ├── WriteFileTool.java
    │   │   ├── EditFileTool.java
    │   │   ├── ListFilesTool.java
    │   │   ├── SearchFilesTool.java
    │   │   ├── GlobTool.java
    │   │   └── BashTool.java                   ← 交互式确认 + 硬拒黑名单
    │   └── storage/
    │       ├── DbInit.java                     ← 建表 + PRAGMA WAL
    │       ├── SqliteConversationStorage.java  ← Layer 1: ConversationStorage 实现
    │       ├── SqliteTaskStore.java            ← Layer 2: TaskStore 实现
    │       └── dao/
    │           ├── SessionDao.java
    │           ├── ConversationDao.java
    │           └── TaskDao.java
    └── resources/
        └── application.yml
```

---

## 十二、主要挑战与应对

| 挑战 | 应对策略 |
|---|---|
| JVM 启动时间（约 1-2s） | 初期接受，后期 GraalVM native-image |
| Bash 工具安全性 | 硬拒黑名单 + 交互式确认 + session 级免确认，三层控制 |
| 多目录路径解析 | `WorkspaceContext.resolveForRead()` 遍历所有 root；LLM 通过 goal 注入感知 |
| session 切换状态隔离 | 切换时重建 Agent（BashTool allowlist / WorkspaceContext / ConversationMemory 全部重置） |
| SQLite 多进程并发 | WAL 模式 + session_id 分区天然隔离 |
| 上下文窗口膨胀 | Layer 3 用 `SlidingWindowContext(8)` 限制单轮工具调用链长度 |
| token 消耗可见性 | 框架 `TokenAggregatingEventListener` 自动聚合，`CliEventListener` 处理 `TASK_TOKEN_SUMMARY` 格式化输出 |

---

## 十三、实现步骤

每步产出都可独立运行验证，不依赖后续步骤。

---

### Step 1：项目骨架 + 配置加载

**实现内容**
- `pom.xml`：继承 spring-boot-starter-parent，依赖 regnexe-agent、JLine3、jdbi3-sqlobject、sqlite-jdbc、snakeyaml
- `CliMain.java`：空的 `CommandLineRunner`，只打印启动信息后退出
- `RexConfig.java`：加载 `~/.rex/config.yml`，支持 `${ENV_VAR}` 插值，字段缺失给合理默认值

**验证方式**
```bash
mvn compile                          # 编译通过
mvn spring-boot:run                  # 启动打印 "rex v0.1.0 ready"，正常退出
# 手动创建 ~/.rex/config.yml，验证字段读取和环境变量替换
```

---

### Step 2：最小 REPL（无 Agent）

**实现内容**
- JLine3 终端初始化（`TerminalBuilder` + `LineReaderBuilder`）
- 基本 REPL 循环：读取输入 → 打印 `echo: <input>` → 循环
- 内置斜杠命令：`/exit`（退出）、`/help`（列出命令）

**验证方式**
```bash
mvn spring-boot:run
# 出现提示符 "rex> "
# 输入任意内容 → 打印 "echo: ..."
# 输入 /exit → 正常退出
# Ctrl+C → 正常退出
```

---

### Step 3：接入 Agent（内存模式，无工具）

**实现内容**
- `CliEventListener.java`：继承 `AbstractEventListener`，处理 `PLAN_STARTED` / `PLAN_COMPLETED` / `EXECUTION_STARTED` / `LLM_RESPONDED` / `AGENT_COMPLETED` 阶段事件
- `TASK_TOKEN_SUMMARY` 处理：解析 JSON，格式化输出 `Tokens: N in / M out | X.Xs`
- `CliMain` 接入 `RegnexeAgentBuilder`：从 `RexConfig` 读取模型，暂用 `InMemoryConversationStorage` + `InMemoryTaskStore`
- REPL 循环：用户输入 → `agent.execute()` → 结果已由 Listener 实时打印

**验证方式**
```bash
export REX_API_KEY=sk-...
mvn spring-boot:run
rex> 你好，介绍一下你自己
# 看到：⟳ Thinking... → ✓ Plan ready → ⟳ Executing... → LLM 回答
# 看到：Tokens: 234 in / 89 out | 2.1s (LLM 1.8s)
rex> 上面你说了什么？   # 验证多轮上下文（内存内）
```

> 前置条件：需要有效的 API Key。

---

### Step 4：SQLite 持久化（Layer 1 + Layer 2）

**实现内容**
- `DbInit.java`：建表 SQL + `PRAGMA WAL`，首次启动自动执行
- JDBI `Jdbi` 单例初始化，注册 `SessionDao` / `ConversationDao` / `TaskDao`
- `SqliteConversationStorage.java`：实现 `ConversationStorage` 接口，读写 `conversation_history` 表
- `SqliteTaskStore.java`：实现 `TaskStore` 接口，`state_json` 用 Jackson 序列化 `TaskExecutionState`
- `CliMain` 替换内存存储为 SQLite 实现，session_id 默认用 UUID 生成并保存到 `sessions` 表

**验证方式**
```bash
mvn spring-boot:run
rex> 今天天气怎么样？   # 对话一轮
# Ctrl+C 退出
mvn spring-boot:run     # 重新启动（同一 session_id 暂时硬编码或写入文件）
rex> 我刚才问了什么？   # 验证历史被读取，LLM 能回忆
# 用 sqlite3 直接查库验证数据
sqlite3 ~/.rex/sessions.db "SELECT role, content FROM conversation_history;"
```

---

### Step 5：文件读取工具

**实现内容**
- `WorkspaceContext.java`：单目录模式，`resolveForRead()` / `primaryRoot()` / `describeRoots()`
- `ReadFileTool.java`：分页读取，offset/limit，路径安全校验
- `ListFilesTool.java`：列目录，支持 glob，无参数列主目录
- `SearchFilesTool.java`：优先 `rg`，fallback Java `Files.walk`
- `GlobTool.java`：`PathMatcher` 在主目录内搜索

**验证方式**
```bash
rex> 列出 src/main/java 下的所有 Java 文件
# 看到 ▶ list_files / ▶ search_files 工具调用
# 看到 ◀ 文件列表结果
rex> 读取 pom.xml 的前 20 行
# 看到 ▶ read_file {"path":"pom.xml","limit":20}
# 看到文件内容
```

---

### Step 6：文件写入工具

**实现内容**
- `WriteFileTool.java`：写/覆盖文件，自动创建父目录，路径安全校验
- `EditFileTool.java`：精确字符串替换，替换前校验 `old_string` 唯一性，不唯一则报错要求用户提供更多上下文

**验证方式**
```bash
rex> 在当前目录创建 hello.txt，内容为 "Hello, World!"
# 看到 ▶ write_file 调用
ls -la hello.txt && cat hello.txt   # 验证文件存在且内容正确

rex> 把 hello.txt 的 "Hello" 改成 "Hi"
# 看到 ▶ edit_file 调用
cat hello.txt   # 验证内容已更新
```

---

### Step 7：Bash 工具（交互式确认）

**实现内容**
- `BashTool.java`：硬拒黑名单、交互式确认对话框、session 级 always-allow 集合
- 从 `RexConfig` 读取 `extra_blocked` 扩展黑名单
- `ProcessBuilder` 执行，捕获 stdout+stderr，超时 30s，工作目录为主目录

**验证方式**
```bash
rex> 运行 ls -la
# 看到确认框显示命令内容
# 输入 y → 看到 ls 输出
rex> 运行 ls -la   # 再次执行同一命令
# 再次弹出确认（非 always 模式）

rex> 运行 ls -la
# 输入 always → 执行
rex> 运行 ls src   # 同一基础命令 ls
# 不再弹出确认

rex> 删除 / 目录   # 触发硬拒
# 看到 "ERROR: command blocked (dangerous): rm -rf /"
```

---

### Step 8：Session 管理

**实现内容**
- `--session <name>` 启动参数：按名字查找 session，存在则读取历史，不存在则新建并写 `sessions` 表
- `--resume <session-id>`：精确按 ID 找 PAUSED 任务，调用 `agent.resume()`
- REPL 斜杠命令：
  - `/sessions`：查 `sessions` 表，表格展示 name / id / last-used / working-dir
  - `/switch <name>`：切换 session，重建 Agent（重置 WorkspaceContext + BashTool allowlist）
  - `/clear`：调 `ConversationDao.deleteBySession()`，清除当前 session 历史

**验证方式**
```bash
rex --session proj-a
rex> 我在做一个 CLI 项目
# Ctrl+C 退出

rex --session proj-a   # 重新进入同名 session
rex> 我刚才在做什么？   # 验证历史被读取

rex --session proj-b   # 新 session，历史隔离
rex> 我刚才在做什么？   # 验证 LLM 不知道 proj-a 的内容

rex --session proj-a
/sessions   # 列出 proj-a 和 proj-b
/switch proj-b   # 切换，验证提示符或欢迎语变化
```

---

### Step 9：多目录支持

**实现内容**
- `WorkspaceContext` 升级为多 root：`addRoot()` / `resolveForRead()` 遍历所有 root / `describeRoots()`
- REPL 斜杠命令：`/add-dir <path>`、`/dirs`
- REPL 注入逻辑：多目录时将 root 列表前置到 goal
- 所有文件工具（read/write/list/search/glob）切换为多 root 路径解析

**验证方式**
```bash
rex
/add-dir /tmp/test-project   # 追加目录
/dirs   # 列出两个目录

rex> 列出 test-project 下的所有文件
# 验证 agent 能找到 /tmp/test-project 下的文件

rex> 读取 test-project/README.md
# 验证跨目录读取
```

---

### Step 10：Pause / Resume

**实现内容**
- REPL 斜杠命令 `/pause`：调用 `agent.pause()`，任务状态写为 PAUSED 到 SQLite
- `--resume <session-id>`：从 SQLite 读取最新 PAUSED 任务，调用 `agent.resume(sessionId, supplement)`
- 长任务场景：给 agent 设置足够多的 round，执行过程中 `/pause` 中断

**验证方式**
```bash
# 启动一个多步骤任务（需要多个 round 才能完成）
rex --session test-pause
rex> 分析整个 src 目录的代码结构，列出所有类和接口，然后生成文档

# 在执行中途输入 /pause（需要在确认弹框或工具调用间隙）
# 看到 "Task paused. Resume with: rex --resume <session-id>"

# 重新启动并续跑
rex --resume <session-id>
# 任务从中断处继续
```

---

### Step 11：打包与安装脚本

**实现内容**
- `pom.xml` 配置 `spring-boot-maven-plugin` repackage goal
- `install.sh` 脚本：下载 JAR → 放置到 `/opt/regnexe/` → 写 `/usr/local/bin/rex` wrapper
- `rex` wrapper 脚本：透传 `$JAVA_OPTS` 和所有参数

**验证方式**
```bash
mvn package -DskipTests
# 验证 target/regnexe-cli.jar 是可执行 fat JAR
java -jar target/regnexe-cli.jar --help

bash install.sh
which rex   # /usr/local/bin/rex
rex --session smoke-test
rex> hello
# 验证全局命令可用
```
