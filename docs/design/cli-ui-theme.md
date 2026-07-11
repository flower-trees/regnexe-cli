# regnexe-cli 交互皮肤设计

> 目标：在不重构为全屏 TUI 的前提下，把当前 JLine REPL 输出整理成更接近 Claude Code / Codex 的 coding-agent 交互体验。

---

## 一、设计目标

- **低噪音**：减少框线、长提示和重复状态；让用户优先看到任务进展、工具动作和最终结果。
- **可扫描**：阶段、工具、确认、暂停、token 汇总都有稳定格式，快速扫一眼能知道当前状态。
- **可降级**：在非 TTY、CI、日志重定向场景下自动退回纯文本，无 ANSI 颜色和动态行覆盖。
- **不引入大重构**：继续使用 JLine + ANSI 输出，不做 Lanterna 这类全屏 TUI。
- **可主题化**：先支持 `minimal` 和 `codex` 两套主题，后续可加 `claude` 风格。

---

## 二、现状问题

当前输出分散在：

- `CliMain.java`：启动信息、prompt、slash command、结果和暂停提示。
- `CliEventListener.java`：阶段状态、工具调用、工具结果、token summary。
- `BashTool.java` / `FileTools.java`：确认框、diff 预览、命令输出。

问题：

- UI 文案散落，难以统一风格。
- tool call 输出偏重，信息密度不稳定。
- 状态词和分隔线混用，视觉节奏不一致。
- token summary 需要更紧凑，并展示 cache 命中。
- Ctrl+C / pause / resume 的提示要更明确，避免自然语言猜测。

---

## 三、主题配置

建议在 `~/.rex/config.yml` 增加：

```yaml
ui:
  theme: codex        # minimal | codex
  color: auto         # auto | always | never
  icons: true         # true | false
  compact: true       # true | false
```

含义：

- `theme`
  - `minimal`：纯文本、CI 友好、少 ANSI。
  - `codex`：紧凑状态、淡色分隔、符号化工具块。
- `color`
  - `auto`：仅 TTY 开启颜色。
  - `always`：强制颜色。
  - `never`：禁用颜色。
- `icons`
  - true：使用 `›`、`✓`、`⟳`、`▶` 等符号。
  - false：使用 ASCII fallback。
- `compact`
  - true：减少空行和长分隔线。
  - false：保留更多说明性文本。

---

## 四、Codex 主题输出规范

### 4.1 启动信息

当前：

```text
rex v0.1.0  (type /help for commands, /exit to quit)
Model: deepseek/deepseek-v4-flash
Workspace: /path/project
Session: default
```

建议：

```text
rex 0.1.0  default  deepseek/deepseek-v4-flash
cwd /path/project
```

多 workspace：

```text
rex 0.1.0  default  deepseek/deepseek-v4-flash
roots
  /path/project
  /tmp/other
```

### 4.2 Prompt

默认：

```text
rex [default]>
```

Codex 主题：

```text
›
```

如果需要显示 session，可在启动信息和 `/sessions` 里展示，不在每一行 prompt 重复。

Minimal 主题：

```text
rex>
```

### 4.3 阶段状态

当前：

```text
  ⟳ Thinking...
  ✓ Ready
  ⟳ Executing...
```

Codex 主题：

```text
thinking
ready
executing
```

带 icons：

```text
⟳ thinking
✓ ready
⟳ executing
```

规则：

- 状态词小写。
- 不超过一行。
- `thinking` 可以用 carriage return 覆盖为 `ready`。
- 非 TTY 时不要覆盖行，直接逐行打印。

### 4.4 工具调用

Bash：

```text
┌ bash
│ echo "hello"
└ exit 0
  hello
```

读文件：

```text
┌ read_file pom.xml
└ 120 lines
```

写文件确认：

```text
write src/main/java/App.java
Apply? [y/N/pause]
```

规则：

- 工具名是第一视觉锚点。
- 参数只展示关键字段，不直接 dump 整个 JSON。
- 成功结果最多展示摘要，长输出截断。
- 详细 JSON 只在 debug 模式展示。

### 4.5 暂停和恢复

Ctrl+C 暂停：

```text
paused
resume: /resume
```

带 session 级命令：

```text
paused
resume: /resume  or  rex --resume default
```

规则：

- 普通输入永远是新任务。
- `/resume` 恢复最新 paused task。
- `/resume <补充说明>` 恢复并传入 supplement。
- 不再通过“继续”“continue”等自然语言自动恢复。

### 4.6 Token Summary

建议格式：

```text
tokens 1238 -> 140 · cache 512 · tools 1 · 3.7s · llm 2.6s
```

无 cache：

```text
tokens 1238 -> 140 · tools 1 · 3.7s · llm 2.6s
```

Minimal 主题：

```text
Tokens: 1238 in -> 140 out · tools 1 · 3.7s (LLM 2.6s)
```

规则：

- 用 `->` 表示输入到输出，避免 `/` 被误读。
- cache 命中为 0 时不展示。
- `tool calls` 缩写为 `tools`。

---

## 五、代码结构建议

新增：

```text
src/main/java/org/salt/regnexe/cli/ui/
  CliRenderer.java
  CliTheme.java
  ThemeConfig.java
  Ansi.java
```

### CliTheme

```java
public enum CliTheme {
    MINIMAL,
    CODEX
}
```

### ThemeConfig

```java
public record ThemeConfig(
        CliTheme theme,
        ColorMode color,
        boolean icons,
        boolean compact
) {}
```

### CliRenderer

集中所有 UI 输出：

```java
public interface CliRenderer {
    String prompt(SessionContext ctx);
    void startup(SessionContext ctx, RexConfig config);
    void thinking();
    void ready();
    void executing();
    void toolCalled(String text);
    void toolResult(String text);
    void tokenSummary(String json);
    void paused(String sessionName);
    void error(String message);
}
```

`CliMain`、`CliEventListener`、`BashTool`、`FileTools` 不直接拼 ANSI 和文案，而是调用 renderer。

---

## 六、落地步骤

### Step 1：配置扩展

- `RexConfig` 增加 `ui` 配置。
- 默认值：

```yaml
ui:
  theme: codex
  color: auto
  icons: true
  compact: true
```

验证：

```bash
mvn -q -o compile
```

### Step 2：抽 `CliRenderer`

- 从 `CliEventListener` 迁移：
  - thinking / ready / executing
  - tool called / tool result
  - token summary
- 从 `CliMain` 迁移：
  - startup
  - prompt
  - paused
  - error

验证：

```bash
scripts/smoke-test.sh
java -jar target/regnexe-cli.jar --help
```

### Step 3：实现 `minimal` 和 `codex`

- `minimal` 不使用 ANSI 动态覆盖。
- `codex` 使用轻量 ANSI 灰色、绿色、黄色。
- 非 TTY 或 `color: never` 禁用颜色。

验证：

```bash
rex --session ui-smoke
/help
```

### Step 4：工具输出收敛

- `BashTool` 输出改由 renderer 负责。
- `FileTools` 确认和 diff 预览改由 renderer 负责。
- 长结果截断规则集中配置。

验证：

```text
rex> 运行 ls -la
rex> 在当前目录创建 hello.txt，内容为 hello
```

### Step 5：回归 Pause/Resume

验证：

```text
rex> 写一首夏天的诗
^C
paused
resume: /resume
rex> /resume
```

确认：

- Ctrl+C 不打印 WARN 堆栈。
- `/resume` 恢复 paused task。
- 普通输入不会隐式恢复。
- PAUSED 摘要进入 session history。

---

## 七、暂不做的事

- 不做全屏 TUI。
- 不引入 Lanterna。
- 不做复杂动画。
- 不把自然语言“继续”自动解释成 resume。
- 不在默认输出里展示完整 event JSON。

---

## 八、示例终态

```text
rex 0.1.0  default  deepseek/deepseek-v4-flash
cwd /Users/me/project

› 写一首夏天的诗
⟳ thinking
✓ ready
⟳ executing

夏风吹过绿荫长，
荷影摇波映日光。
蝉唱午庭人半醉，
一帘清梦落池塘。

tokens 1238 -> 140 · cache 512 · tools 0 · 3.7s · llm 2.6s

›
```
