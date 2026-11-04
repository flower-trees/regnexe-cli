# `/skill名` 直接调用 Skill 设计方案

> 目标：让 `regnexe-cli` 支持类似 Claude Code 的 `/skill名 参数` —— 用户在 REPL 里显式敲命令，
> 直接执行一个文件化定义的 Skill，跳过 Search→Plan→Execute→Reflect 全流程规划。
> 日期：2026-07-15

---

## 一、结论先行：这次改动量很小

`regnexe-agent` + `j-langchain` 已经有完整的 Skill/Marketplace 基础设施（SKILL.md 解析、目录扫描注册、
Claude Code 兼容性已用真实插件目录验证过，见 `j-langchain` 的
`Article30ClaudeCodeSkillCompat`）。这次要做的事严格来说只有三件：

1. `regnexe-agent`：`DefaultPluginManager` 的 manifest 识别加一种格式（`.claude-plugin/plugin.json`）
2. `regnexe-agent`：`RegnexeAgent` 加一个直调入口 `executeSkill()`，绕开 Search/Plan/Reflect
3. `regnexe-cli`：接线（注册插件目录）+ 分发（`/<skill名>` 路由）+ 一个新命令（`/skills`）

不新增任何 SKILL.md 解析逻辑、不新增目录扫描逻辑 —— 这些全部复用现有实现。

---

## 二、涉及仓库与改动清单

| 仓库 | 文件 | 改动类型 | 说明 |
|---|---|---|---|
| regnexe-agent | `market/DefaultPluginManager.java` | 修改 `loadPlugin()` | 支持 `.claude-plugin/plugin.json` manifest |
| regnexe-agent | `RegnexeAgent.java` | 新增 `executeSkill()` | 直调入口，复用 `storeSessionRound`/`eventListener` |
| regnexe-cli | `CliMain.java` | 修改 `buildAgent()` | 接线 `.withDirectory(...)` 注册 marketplace 目录 |
| regnexe-cli | `CliMain.java` | 修改 `handleSlashCommand()` | `default` 分支加 skill 短名路由 |
| regnexe-cli | `CliMain.java` | 新增 `/skills` 分支 | 列出所有已发现 Skill |
| regnexe-cli | （新）`skill/SkillNameResolver.java` 或内联方法 | 新增 | 短名 → capabilityId 解析 + 歧义处理 |

j-langchain **不需要改动** —— `FileSystemSkillConfigLoader` 已经是 SKILL.md 层的权威实现，
兼容性问题只出在"plugin 级别的 manifest 文件名"这一层，属于 `regnexe-agent` 的
`DefaultPluginManager` 职责范围。

---

## 三、详细设计

### 3.1 regnexe-agent：`DefaultPluginManager` 支持双 manifest 格式

**现状**：`loadPlugin(pluginDir, marketplace)` 只认 `{pluginDir}/plugin.yaml`，找不到就跳过
（`log.debug`，静默）。

**改动**：抽出一个 `loadManifest(pluginDir)`，按优先级尝试两种 manifest：

```java
// DefaultPluginManager.java

private void loadPlugin(Path pluginDir, Marketplace marketplace) {
    Map<String, Object> manifest = loadManifest(pluginDir);
    if (manifest == null) {
        log.debug("Skipping directory (no plugin.yaml or .claude-plugin/plugin.json): {}", pluginDir);
        return;
    }

    String pluginId  = getString(manifest, "pluginId", pluginDir.getFileName().toString());
    String name      = getString(manifest, "name", pluginId);
    String version   = getString(manifest, "version", "1.0");
    String desc      = getString(manifest, "description", "");
    // Claude Code plugin.json 用 "keywords"，regnexe 原生用 "tags" —— 两个都认
    List<String> tags = firstNonEmpty(getStringList(manifest, "tags"), getStringList(manifest, "keywords"));

    List<CapabilityDescriptor> caps = new ArrayList<>();
    caps.addAll(loadTools(pluginId, pluginDir));
    caps.addAll(loadSkills(pluginId, pluginDir));
    caps.addAll(loadSubAgents(pluginId, pluginDir));

    if (caps.isEmpty()) {
        log.warn("Plugin '{}' has no loadable capabilities — skipped", pluginId);
        return;
    }

    marketplace.install(PluginDescriptor.builder()
            .pluginId(pluginId).version(version)
            .name(name).description(desc).tags(tags)
            .capabilities(caps)
            .build());
    log.info("Installed plugin '{}' with {} capabilities", pluginId, caps.size());
}

/**
 * 优先级：{@code plugin.yaml}（regnexe 原生）> {@code .claude-plugin/plugin.json}
 * （Claude Code 插件清单）。两者都没有则返回 null，调用方跳过该目录。
 *
 * <p>plugin.json 本质是合法 YAML（YAML 是 JSON 的超集），复用同一个 SnakeYAML 解析器，
 * 不引入新依赖。
 */
private Map<String, Object> loadManifest(Path pluginDir) {
    Path nativeManifest = pluginDir.resolve("plugin.yaml");
    if (Files.exists(nativeManifest)) {
        return parseYaml(nativeManifest);
    }
    Path claudeManifest = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
    if (Files.exists(claudeManifest)) {
        return parseYaml(claudeManifest);
    }
    return null;
}

private List<String> firstNonEmpty(List<String> a, List<String> b) {
    return (a == null || a.isEmpty()) ? b : a;
}
```

**验证方式**：已经实测确认本机路径和字段结构，`~/.claude/plugins/marketplaces/claude-plugins-official/plugins/claude-md-management/`
目录下确实是：

```
claude-md-management/
  .claude-plugin/
    plugin.json      ← {"name": "claude-md-management", "description": "...", "version": "1.0.0", "author": {...}}
  skills/
    claude-md-improver/
      SKILL.md
```

（`Article30ClaudeCodeSkillCompat` 测试引用的同一个目录，可以直接拿来做集成测试，不用手造 fixture。）

注意：Claude Code 顶层的 `~/.claude/plugins/config.json`、`~/.claude/plugins/marketplaces/<name>/.claude-plugin/marketplace.json`
是另外两种文件——前者是全局配置，后者是"从哪个远程仓库拉插件"的索引清单（带 `source.url`），
**不是**单个 plugin 自己的 manifest，本方案不需要读它们。

**边界情况**：
- 两种 manifest 都没有 → 按现状跳过（不报错，`log.debug`），保持向后兼容
- `plugin.json` 里没有 `description`/`version` 等字段 → 走现有 `getString` 的默认值兜底，行为和原生
  `plugin.yaml` 缺字段时一致；真实样本里也确实没有 `keywords`/`tags`，`firstNonEmpty` 兜底成空列表，
  不影响安装
- 后续如果要支持 marketplace 级别的 `.claude-plugin/marketplace.json`（多插件清单索引），是另一个独立
  的改动，本方案不覆盖，先不做

### 3.1.1 pluginId 冲突：项目级目录与用户级目录撞名

**风险**：`withDirectory(projectDir, userDir)` 一次注册两棵目录树。如果两边各有一个同名 plugin 子目录
（比如都叫 `demo/`），`SimpleMarketplace.install()` 对重复 `pluginId` 是硬拒绝：

```java
// SimpleMarketplace.install()
if (plugins.containsKey(pluginId)) {
    throw new IllegalStateException("Plugin already installed: " + pluginId);
}
```

第二次 `install()` 直接抛异常，会把整个 `buildAgent()`（进而 CLI 启动、或 `/switch` 触发的
`AGENT_REBUILT` 重建）炸掉，而不是优雅跳过。原方案没考虑这个情况，这里补一个修复。

**修复**：`loadPlugin()` 里包一层 try/catch，把"安装冲突"从异常降级成 warning + 跳过：

```java
// DefaultPluginManager.java — loadPlugin() 末尾

try {
    marketplace.install(PluginDescriptor.builder()
            .pluginId(pluginId).version(version)
            .name(name).description(desc).tags(tags)
            .capabilities(caps)
            .build());
    log.info("Installed plugin '{}' with {} capabilities", pluginId, caps.size());
} catch (IllegalStateException e) {
    // 目录扫描按 directories 列表顺序进行：先扫到的 pluginId 赢。
    // 调用方把项目级目录排在用户级目录前面，天然得到
    // "项目级同名插件覆盖用户级" 的语义，不用额外写覆盖逻辑。
    log.warn("Skipping plugin '{}' from {}: {}", pluginId, pluginDir, e.getMessage());
}
```

**效果**：`regnexe-cli` 只要保证调用顺序是 `withDirectory(projectDir, userDir)`（项目级在前），
就自动获得"项目级覆盖用户级同名插件"的语义，不需要在 `regnexe-cli` 侧写任何额外的去重/覆盖逻辑。
第三节的 `buildAgent()` 代码里参数顺序已经是项目级在前，不用调整。

---

### 3.2 regnexe-agent：`RegnexeAgent.executeSkill()`

**目标**：给定一个 capabilityId 和一段参数文本，直接构造并运行该 Skill，不经过
`CapabilitySearcher`/`TaskPlanner`/`Reflector`，返回值形态和 `execute()`/`resume()` 保持一致
（`AgentResult`），这样 `regnexe-cli` 侧 `handleAgentResult()` 不用改就能渲染。

```java
// RegnexeAgent.java

/**
 * Directly invoke a SKILL capability by id, bypassing Search/Plan/Reflect.
 * Used by explicit user-triggered invocation (e.g. CLI "/skill-name args"),
 * as opposed to the planner autonomously selecting it during execute().
 *
 * @param capabilityId capability id as registered in the marketplace
 *                      (format: {@code <pluginId>.<skillName>})
 * @param args          raw argument text typed after the skill name; passed to
 *                      the skill's internal executor as the user turn
 * @param sessionId     session to store the resulting turn under (nullable —
 *                      null skips session-history storage)
 * @param displayGoal   human-readable form for session history (e.g. "/review src/Foo.java");
 *                      falls back to capabilityId + args when null
 */
public AgentResult executeSkill(String capabilityId, String args, String sessionId, String displayGoal) {
    CapabilityDescriptor cap = marketplace.resolveDescriptor(capabilityId);
    if (cap == null) {
        throw new IllegalArgumentException("Unknown skill: " + capabilityId);
    }
    if (cap.getType() != CapabilityType.SKILL || cap.getSkillConfig() == null) {
        throw new IllegalArgumentException("Not a skill capability: " + capabilityId);
    }

    String taskId = UUID.randomUUID().toString();
    BaseChatModel llm = llmProvider.provide(defaultModel);

    eventListener.dispatch(AgentEvent.of(taskId, 0, EventType.AGENT_STARTED,
            "Skill: " + capabilityId + " | args: " + args));

    Skill.Builder skillBuilder = Skill.from(cap.getSkillConfig(), chainActor).llm(llm);
    if (verbose) {
        skillBuilder.verbose(true);
    } else {
        String scope = "[skill:" + cap.getName() + "]";
        skillBuilder.onLlm(text -> eventListener.dispatch(
                AgentEvent.of(taskId, 0, EventType.SKILL_LLM_RESPONDED, scope + " " + text)));
        skillBuilder.onToolCall(tc -> eventListener.dispatch(
                AgentEvent.of(taskId, 0, EventType.TOOL_CALLED, scope + " " + tc)));
        skillBuilder.onObservation(obs -> eventListener.dispatch(
                AgentEvent.of(taskId, 0, EventType.TOOL_RESULT, scope + " " + obs)));
    }
    skillBuilder.onTokenUsage(u -> eventListener.dispatch(
            AgentEvent.ofCapabilityTokenUsage(taskId, 0, cap.getName(), u)));

    String finalText;
    TaskStatus status;
    try {
        finalText = skillBuilder.build().invoke(args == null ? "" : args);
        status = TaskStatus.FINISHED;
    } catch (Exception e) {
        log.warn("executeSkill '{}' failed: {}", capabilityId, e.getMessage());
        finalText = "Skill execution failed: " + e.getMessage();
        status = TaskStatus.FAILED;
    }

    if (sessionId != null && status == TaskStatus.FINISHED) {
        String humanTurn = (displayGoal != null && !displayGoal.isBlank())
                ? displayGoal : ("/" + capabilityId + " " + (args == null ? "" : args)).trim();
        storeSessionRound(sessionId, humanTurn, finalText);
    }

    eventListener.dispatch(AgentEvent.of(taskId, 0, EventType.AGENT_COMPLETED,
            "Status: " + status + " | Skill: " + capabilityId));

    return AgentResult.builder()
            .taskId(taskId)
            .status(status)
            .finalText(finalText)
            .build();
}
```

**设计要点**：
- `eventListener` 字段本身已经被 `RegnexeAgentBuilder.build()` 包了一层
  `TokenAggregatingEventListener`（见 `RegnexeAgentBuilder.Builder#build()`），所以只要按
  `AGENT_STARTED → ... → AGENT_COMPLETED` 的顺序派发，`TASK_TOKEN_SUMMARY` 就会在
  `AGENT_COMPLETED` 之前自动补发一次 —— `regnexe-cli` 的 `CliEventListener` 不用做任何适配。
- 不写 `taskStore`（不生成 `TaskExecutionState`）—— 即这次不支持 skill 执行中途 pause/resume。
  这是刻意简化：Skill 内部本来就是一个独立的 Function Calling 循环（`McpAgentExecutor`），
  接入 pause/resume 需要把 `TaskExecutionState`/`RoundRecord` 那套摁到单 Skill 调用上，
  超出本次范围，后续如果有真实需求再加。
- `AgentResult.state` 留空（`null`）。`handleAgentResult()` 现有逻辑只在 `status == PAUSED` 时才读
  `result.getState()`，`executeSkill()` 不会产生 `PAUSED` 状态，因此安全。

---

### 3.3 regnexe-cli：目录约定与 `buildAgent()` 接线

> **更新（2026-07-17）**：v1 上线后按用户要求放开了"只认 `default` 一个 marketplace"的限制，
> 改成自动发现任意个 marketplace 子目录，并加了一个 config 项支持完全在约定之外的额外目录。
> 下面是当前实现，不再是 v1 时的固定两目录版本。

**目录约定**——`.rex/marketplaces/` 下任意个子目录都是一个 marketplace，不需要在代码或 config
里登记名字，新建目录即可被发现：

```
{workspace-root}/.rex/marketplaces/<marketplace任意名>/plugins/<plugin>/skills/<skill>/SKILL.md   ← 项目级
~/.rex/marketplaces/<marketplace任意名>/plugins/<plugin>/skills/<skill>/SKILL.md                  ← 用户级
```

再加一个不受目录约定限制的口子——`~/.rex/config.yml` 里的 `skills.extra_dirs`，每一项直接是一个
"plugin 根目录"（和 `DefaultPluginManager.addDirectory()` 期望的形状一样，即该目录下每个子目录是
一个 plugin），可以直接指向仓库外的任意位置（比如不拷贝、直接指向一个已安装的 Claude Code
marketplace 的 `plugins/` 目录）：

```yaml
skills:
  extra_dirs:
    - ~/shared/team-plugins        # 团队共享目录
    - ~/.claude/plugins/marketplaces/claude-plugins-official/plugins   # 直接复用已装的 Claude Code 插件
```

扫描优先级（前面的赢，同 pluginId 冲突时后面的按 3.1.1 的降级规则被跳过并 `log.warn`）：

1. 项目级 `.rex/marketplaces/*/plugins`（按目录名排序）
2. 用户级 `~/.rex/marketplaces/*/plugins`（按目录名排序）
3. `skills.extra_dirs`（按 config 里列出的顺序）

```java
// CliMain.java — buildAgent()

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
            // Project marketplaces first, then user marketplaces, then extra_dirs — earlier
            // entries win on a pluginId collision (3.1.1's skip-with-warning degrade).
            .withDirectory(resolveSkillDirectories(workspace, config).toArray(new String[0]));
    if (db != null) {
        builder = builder.withSessionStorage(new SqliteConversationStorage(db));
        builder = builder.withTaskStore(new SqliteTaskStore(db));
    }
    return builder.build();
}

/** Every subdirectory of {@code <root>/.rex/marketplaces/} is a marketplace; its plugins live
 *  under {@code <name>/plugins}. Missing roots just contribute no directories. */
private List<String> resolveSkillDirectories(WorkspaceContext workspace, RexConfig config) {
    List<String> dirs = new ArrayList<>();
    dirs.addAll(listMarketplacePluginDirs(workspace.primaryRoot().resolve(".rex/marketplaces")));
    dirs.addAll(listMarketplacePluginDirs(Path.of(System.getProperty("user.home"), ".rex/marketplaces")));
    for (String d : config.getSkills().getExtraDirs()) {
        if (d != null && !d.isBlank()) dirs.add(expandHome(d.trim()));
    }
    return dirs;
}

private List<String> listMarketplacePluginDirs(Path marketplacesRoot) {
    if (!Files.isDirectory(marketplacesRoot)) return List.of();
    try (var subdirs = Files.list(marketplacesRoot)) {
        return subdirs.filter(Files::isDirectory).sorted()
                .map(p -> p.resolve("plugins").toString())
                .toList();
    } catch (IOException e) {
        return List.of();
    }
}
```

**注意**：`withTool(...)` 和 `withDirectory(...)` 都是 `RegnexeAgentBuilder.Builder` 上的"便捷方法"，
内部各自会在 `marketplace == null` 时新建一个 `SimpleMarketplace()` ——但只会新建一次（第一次调用后
`marketplace` 字段非空，后续便捷方法复用同一个实例），所以调用顺序不影响结果，两次调用最终注册进
**同一个** marketplace。

**实测**：本地搭了 `default`（`demo.hello`）+ `team`（`toolkit.lint-note`，验证不再写死 `default`）
两个 marketplace，外加 `skills.extra_dirs` 指向一个完全在约定之外的目录（`adhoc.ping`），
`/skills` 三个都能正确列出。

---

### 3.4 regnexe-cli：短名解析与 `/<skill>` 分发

**短名解析规则**：
- `capabilityId` 格式固定是 `<pluginId>.<skillName>`（`DefaultPluginManager.loadSkills()` 决定的）
- CLI 里用户敲的是短名（`SkillConfig.name`，即 `SKILL.md` frontmatter 的 `name` 字段）
- 唯一命中 → 直接用；**多个 plugin 注册了同名 skill → 报歧义，列出候选 `<pluginId>.<skillName>`
  全称，要求用户用全称重新指定**，不做静默 first-match（避免"以为调用的是 A 插件，实际跑的是 B"）

```java
// CliMain.java — 新增一个小 record + 一个 resolver 方法

private record SkillMatch(String capabilityId, String shortName, String description) {}

/** Scans marketplace.listEnabled() for SKILL capabilities, keyed by both short name and full capabilityId. */
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

/** Resolves a user-typed name (short name or full capabilityId) to exactly one capabilityId. */
private String resolveSkillId(String typed, List<SkillMatch> all, PrintWriter out) {
    // 1. exact capabilityId match always wins (handles the disambiguated "<pluginId>.<name>" form)
    for (SkillMatch m : all) {
        if (m.capabilityId().equals(typed)) return m.capabilityId();
    }
    // 2. short-name match
    List<SkillMatch> byShortName = all.stream().filter(m -> m.shortName().equals(typed)).toList();
    if (byShortName.size() == 1) return byShortName.get(0).capabilityId();
    if (byShortName.size() > 1) {
        out.println("  [warn] Ambiguous skill name '" + typed + "', candidates:");
        byShortName.forEach(m -> out.println("    " + m.capabilityId()));
        out.println("  Re-run with the full id, e.g. /" + byShortName.get(0).capabilityId() + " ...");
        out.flush();
        return null;
    }
    return null;  // no match — not a skill, fall through to "Unknown command"
}
```

`RegnexeAgent` 需要补一个只读 getter：

```java
// RegnexeAgent.java
public Marketplace getMarketplace() {
    return marketplace;
}
```

分发点：`handleSlashCommand()` 的 `default` 分支不再直接打印 "Unknown command"，先查 skill：

```java
// CliMain.java — handleSlashCommand() 的 default 分支替换为：

default -> {
    String skillName = cmd.substring(1);  // 去掉前导 "/"
    String skillArgs = parts.length > 1 ? parts[1] : "";
    List<SkillMatch> skills = listSkills(agent);
    String capabilityId = resolveSkillId(skillName, skills, out);
    if (capabilityId == null) {
        if (skills.stream().noneMatch(m -> m.shortName().equals(skillName))
                && skills.stream().noneMatch(m -> m.capabilityId().equals(skillName))) {
            out.println("Unknown command: " + cmd + "  (type /help for commands, /skills for available skills)");
            out.flush();
        }
        return SlashResult.CONTINUE;
    }
    return SlashResult.RUN_SKILL.withPayload(capabilityId, skillArgs, raw);
}
```

> `SlashResult` 目前是个没有字段的 `enum`（`CONTINUE`/`EXIT`/`AGENT_REBUILT`）。要带 `capabilityId`/
> `args`/`displayGoal` 这几个值出去，最省事的方式是把 `handleSlashCommand` 的返回类型从
> `enum` 换成一个小的 sealed 结果类（或者简单点：给 `SlashResult` 加三个可变实例字段，反正
> `handleSlashCommand` 每次调用都是新求值，没有并发问题）。具体写法留到实现阶段定，这里只定行为契约。

主循环里消费 `RUN_SKILL`（对应现在 `input.startsWith("/")` 分支里 `handleSlashCommand` 返回值的
`switch`）：

```java
// CliMain.java — 主循环，在 "if (input.startsWith("/"))" 分支里追加一个 case

SlashResult result = handleSlashCommand(input, out, config, terminal, agent, ctx, db);
if (result == SlashResult.EXIT) break;
if (result == SlashResult.AGENT_REBUILT) {
    agent = buildAgent(ctx, config, terminal, db, pauseAction);
    agentRef.set(agent);
} else if (result.isRunSkill()) {
    RegnexeAgent taskAgent = agent;
    String capId = result.capabilityId();
    String skillArgs = result.args();
    String displayGoal = result.rawInput();  // 原始 "/name args"，存历史用
    AgentResult skillResult = runAgentTask(
            () -> taskAgent.executeSkill(capId, skillArgs, ctx.getSessionName(), displayGoal),
            ctx, out, executing, interruptCount);
    handleAgentResult(skillResult, ctx, out, db);
    if (db != null) {
        try { db.touchSession(ctx.getSessionName()); } catch (Exception ignored) {}
    }
}
continue;
```

这样 skill 执行复用了跟普通任务完全一样的：后台线程执行（`runAgentTask`，Ctrl+C 中断处理不变）、
结果渲染（`handleAgentResult`）、session touch。

---

### 3.5 regnexe-cli：`/skills` 命令

加在 `handleSlashCommand()` 的 switch 里，紧挨着 `/sessions`：

```java
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
```

---

## 四、历史记录集成

已经在 3.2 里覆盖：`executeSkill()` 内部直接调用现有私有方法 `storeSessionRound(sessionId, humanTurn, finalText)`
—— 因为 `executeSkill()` 是 `RegnexeAgent` 自己的方法，不需要放宽 `storeSessionRound` 的访问级别。

效果：`/review src/Foo.java` 执行完，`humanTurn = "/review src/Foo.java"`（原始输入）、
`answer = finalText`，作为一轮 `HUMAN`/`AI` 消息存进 `ConversationSummaryBufferMemory`，
和普通对话轮次一样参与后续多轮上下文压缩。`/sessions` 列表、`SqliteConversationStorage` 都不用改。

不落 `taskStore` → 不支持 `/resume` 一个执行到一半的 skill（见 3.2 的设计取舍）。

---

## 五、示例 Skill 目录（用于冒烟测试）

```
.rex/marketplaces/default/plugins/
  demo/
    plugin.yaml
    skills/
      hello/
        SKILL.md
```

`plugin.yaml`：

```yaml
pluginId: demo
name: Demo Plugin
description: 冒烟测试用的示例插件
version: "1.0"
```

`SKILL.md`：

```markdown
---
name: hello
description: 打印一句问候语，用于验证 /skill 调用链路是否打通
---

你是一个简单的问候助手。收到用户输入后，直接用一句话打招呼并复述用户输入的参数，不需要调用任何工具。
```

冒烟验证步骤：
1. `/skills` 应该列出 `hello | demo.hello | 打印一句问候语...`
2. `/hello 张三` 应该触发一次 LLM 调用并返回问候语，`TASK_TOKEN_SUMMARY` 正常打印
3. `/sessions` 或后续多轮对话应该能看到这轮记录被计入历史

再用本机已安装的 Claude Code 插件目录（`~/.claude/plugins/marketplaces/claude-plugins-official/plugins/claude-md-management/`）
验证 `.claude-plugin/plugin.json` 兼容路径：把该目录（或软链）放进
`~/.rex/marketplaces/default/plugins/`，`/skills` 应该能看到 `claude-md-improver`。

---

## 六、实施顺序

1. **regnexe-agent**：`DefaultPluginManager.loadManifest()` 改动（3.1）+ pluginId 冲突降级为
   warning（3.1.1）+ 单测（自建 fixture 用 `.claude-plugin/plugin.json`；另建一组"两个目录各有
   同名 plugin"的 fixture 断言不抛异常、先扫到的赢；再跑一次针对本机真实 Claude Code 插件目录的
   集成测试，风格参考 `Article30ClaudeCodeSkillCompat` 的 `Assume` 跳过写法）
2. **regnexe-agent**：`RegnexeAgent.executeSkill()` + `getMarketplace()`（3.2）+ 单测
   （mock marketplace 里塞一个 `SkillConfig`，断言 `AgentResult.finalText`、事件派发顺序、
   `sessionId == null` 时不落历史）
3. **regnexe-cli**：`buildAgent()` 接线（3.3）
4. **regnexe-cli**：`SlashResult` 结构调整 + skill 分发 + `/skills`（3.4、3.5）
5. 用第五节的 demo 插件冒烟测试，再用真实 Claude Code 插件目录测一次兼容性
