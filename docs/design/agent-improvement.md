# regnexe-agent 改进方向（基于 Step 8 执行观察）

> 来源：regnexe-cli 用自身工具链实现 Step 8 Session 管理时暴露的问题
> 日期：2026-07-07

---

## 一、isReadOnly() 误判导致 `2>/dev/null` 触发确认

### 问题

`WRITE_SIGNALS` 列表里有 `">"`，而 `find ... 2>/dev/null` 命令包含 `>`，
导致一个纯读操作被判定为"有写信号"，弹出不必要的确认。

### 修复方案

在 `isReadOnly()` 里，先对命令做预处理：去掉 stderr/stdout 的空重定向模式后再判断写信号。

```java
private static final Pattern NULL_REDIRECT = Pattern.compile("\\d*>\\s*/dev/null");

private static boolean isReadOnly(String command) {
    String trimmed = command.trim().toLowerCase();
    boolean safePrefix = READ_ONLY_PREFIXES.stream()
            .anyMatch(p -> trimmed.equals(p) || trimmed.startsWith(p + " ") || trimmed.startsWith(p + "\t"));
    if (!safePrefix) return false;
    // Strip N>/dev/null redirects (stderr/stdout suppression) before checking write signals
    String stripped = NULL_REDIRECT.matcher(command).replaceAll("");
    return WRITE_SIGNALS.stream().noneMatch(stripped::contains);
}
```

---

## 二、确认输入竞态：`terminal.reader().read()` 单字符读

### 问题

当一次 `agent.execute()` 内有多个工具连续弹出确认（如 edit_file → bash → bash），
用户键入 `y\n` 时：
- `y` → 第一个确认（accepted）
- `\n` → 第二个确认（rejected，因为 `\n ≠ 'y'/'Y'`）

后续所有确认都被吞掉，命令全部 cancel。

### 修复方案

改为 **行读取**，而不是单字符读取：

```java
// 当前（有问题）
int ch = terminal.reader().read();
if (ch != 'y' && ch != 'Y') return "Command cancelled by user.";

// 改为
String answer = terminal.reader().readLine("").trim().toLowerCase();
if (!answer.equals("y") && !answer.equals("yes")) return "Command cancelled by user.";
```

`readLine()` 会等到用户按 Enter 才返回，避免把 Enter 键误判为下一个问题的输入。

> 同样适用于 write_file / edit_file 的 Apply? [y/N] 确认。

---

## 三、plan parse error 无 fallback

### 问题

Round 2 的 plan narrative 里出现了 XML tool call 格式（`<｜｜DSML｜｜invoke name="read_file">`），
说明 LLM 混淆了 plan 输出 schema 和工具调用 schema。框架检测到 parse error 后直接跳过，
进入空执行 → reflect 判断 FINISH → 输出虚构的"Task complete"。

### 修复方案（三层降级）

```
Level 1: 解析失败 → 立即 retry plan（最多 2 次，system prompt 追加纠错提示）
Level 2: retry 仍失败 → 用上一轮的 plan 继续执行（hint: "继续未完成的子任务"）
Level 3: 无历史 plan → 构造最小默认 plan：使用全部工具，SYNTHESIZE 策略，max_iterations=10
```

Retry 时在 system prompt 追加：
```
[WARN] Previous plan output was not valid JSON. 
Do NOT output XML or tool calls in the plan fields.
Output ONLY the required JSON structure.
```

---

## 四、Reflection FINISH 条件过于宽松

### 问题

当前 Round 2：0 个工具调用，0 个文件变更，reflect 仍然返回 `FINISH`。
这让 agent 可以在没有任何实际产出的情况下结束任务。

### 修复方案

在 Reflection 阶段增加"完成证据校验"：

```
规则 1：当前轮次 tool_executions 为空 AND 任务声明需要文件变更
        → 不允许 FINISH，强制 CONTINUE 并给 hint "尚未执行任何工具"

规则 2：round_number == 1 AND tool_executions 为空
        → 不允许 FINISH（第一轮什么都没做就结束不合理）

规则 3：reflection reason 中包含推测性语言（"should be"、"已经"、"应该"）
        但 tool_executions 中没有对应的成功写操作
        → 降级为 CONTINUE with hint "请验证实际文件状态"
```

或者更简单的实现：在 Reflection prompt 里增加结构化约束字段：

```json
{
  "action": "FINISH | CONTINUE | RETRY_PLAN",
  "evidence": "具体说明哪些文件/操作已完成（不允许留空）",
  "reason": "..."
}
```

框架在 action=FINISH 时检查 evidence 是否与 tool_executions 的实际结果对应。

---

## 五、max_iterations 任务级别动态覆盖

### 问题

全局 `max_agent_iterations=20` 对简单任务足够，但对"重写多个文件"这类任务严重不足。
一旦 bash confirm 被 cancel 几次，就撞上 limit，任务截断。

### 方案 A：Plan 输出 iterations_hint

在 plan 的输出 schema 里增加可选字段：

```json
{
  "narrative": "...",
  "iterations_hint": 40,
  "selected_capability_ids": [...]
}
```

框架在执行时取 `min(config.maxAgentIterations * 2, iterations_hint)` 作为本轮上限，
或直接使用 hint 值（cap 在某个安全上限如 100）。

Plan LLM 的 prompt 里给出估算指导：
```
- 每个文件读取 = 2 iterations
- 每个文件写入（含确认）= 3 iterations
- 每次 bash 执行（含确认）= 2 iterations
- 建议预留 20% buffer
```

### 方案 B：按任务类型自动调整（更简单）

```java
// 在 TaskPlanner 或 execute() 入口处
int estimatedIterations = estimateIterations(plan);
if (estimatedIterations > maxAgentIterations) {
    maxAgentIterations = Math.min(estimatedIterations, MAX_SAFE_ITERATIONS);
}
```

---

## 六、复杂任务自动分解（Task Decomposition）

### 问题

"重新完成 Step 8" 实际上需要修改 4 个文件、创建 1 个新文件，大约需要 40-50 iterations。
作为单个任务在 max_iterations=20 下注定失败。

### 设计方案

引入 **sub-task** 概念：coordinator task → N 个 sub-tasks。

```
Coordinator Task（负责分解和汇总）
├── SubTask 1: 创建 SessionContext.java（max_iter=10）
├── SubTask 2: 更新 RexDatabase.java（max_iter=15）
├── SubTask 3: 更新 SqliteConversationStorage.java（max_iter=12）
├── SubTask 4: 更新 CliMain.java（max_iter=20）
└── SubTask 5: 编译验证（max_iter=5）
```

**触发条件**：Plan 阶段识别出任务涉及 ≥3 个文件修改，或 estimated_iterations > threshold。

**实现路径**（最小改动）：

```java
// TaskPlanner 输出中增加可选字段
{
  "decompose": true,
  "sub_tasks": [
    { "goal": "创建 SessionContext.java，内容为...", "max_iterations": 10 },
    { "goal": "更新 RexDatabase.java，添加 sessions 表和 CRUD 方法", "max_iterations": 15 }
  ]
}
```

框架检测到 `decompose=true` 时，串行（或部分并行）执行 sub-tasks，
各 sub-task 共享同一个 `WorkspaceContext` 和 `db`，结果写入同一组文件。

**最简替代方案**（不改框架）：

在 REPL 层面引入 `/plan` 命令，用户或 agent 可以把大任务拆成多条 `/exec` 指令并顺序提交。

---

## 七、上下文污染问题

### 问题

agent 运行在 `default` session，这个 session 里存着所有历史对话（写诗、MySQL 迁移等）。
Plan LLM 看到这些内容，消耗了 context window，也可能导致格式漂移。

这是一个递归讽刺：**缺少 session 管理的任务，被 session 管理缺失本身搞砸了**。

### 方案

1. 给 agent 自身的执行任务分配独立 session（`rex --session agent-bootstrap`）
2. 或者：在 agent 内部启动的子 agent 自动使用独立 session_id（如 `parent_session_id + ":subtask:" + task_id`）
3. 长期：`ConversationSummaryBufferMemory` 的 buffer 应该只保留与当前任务相关的轮次，
   和"写诗"无关的 turns 不应该出现在 plan context 里

---

## 优先级排序

| 优先级 | 改动 | 影响 | 成本 |
|--------|------|------|------|
| P0 | 修复 `isReadOnly()` 的 `2>/dev/null` 误判 | 直接减少无效确认 | 小 |
| P0 | 确认输入改为 `readLine()` | 解决多确认竞态 | 小 |
| P1 | plan parse error retry | 防止幻觉完成 | 中 |
| P1 | Reflection 增加 evidence 字段约束 | 防止虚报完成 | 中 |
| P2 | Plan 输出 iterations_hint | 复杂任务不截断 | 中 |
| P3 | Sub-task 分解机制 | 大任务可靠执行 | 大 |
