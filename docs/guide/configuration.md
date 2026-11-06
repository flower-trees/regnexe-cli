# 配置说明 / Configuration

配置文件为 `~/.rex/config.yml`，键使用 snake_case。`${ENV_VAR}` 从环境读取，缺失时尝试同名 JVM 属性，仍缺失则为空字符串。不要将含真实 Key 的配置加入版本控制。

The configuration file is `~/.rex/config.yml`, using snake_case keys. `${ENV_VAR}` resolves from the environment, then a JVM property, then an empty string. Keep real API keys out of version control.

```yaml
model:
  vendor: deepseek
  name: deepseek-v4-flash
  api_key: ${DEEPSEEK_KEY}
  # Optional overrides; omit to reuse the main model.
  # planner_name: deepseek-chat
  # reflector_name: deepseek-chat
agent:
  max_rounds: 10
  max_agent_iterations: 20
  max_consecutive_tool_failures: 5
  context_window_size: 8
  session_compact_period: 20
ui:
  theme: codex
  color: auto
  icons: true
  compact: true
tools:
  bash:
    require_confirmation: true
```

省略配置时当前内置主模型为 `deepseek-v4-pro`。上面显式配置为 `deepseek-v4-flash`，使环境可重复。`REX_MODEL` 只覆盖模型名，不改变 vendor。

The built-in main model is currently `deepseek-v4-pro`. The example explicitly selects `deepseek-v4-flash` for reproducibility. `REX_MODEL` changes the name, not the vendor.

## 独立角色 / Per-role models

`planner_name`、`reflector_name` 可以单独配置。厂商不同时，设置相应的 `planner_vendor` / `reflector_vendor` 和 `planner_api_key` / `reflector_api_key`。同厂商未提供独立 Key 时复用主 Key。同厂商角色当前通过共享 actuator 的厂商配置取 Key，不支持同一厂商的多个独立账号同时工作。

Set `planner_name` / `reflector_name` separately if needed. For a different provider, also set the corresponding `*_vendor` and `*_api_key`. Same-provider roles reuse the main key when no override is given. Actuator credentials are shared per provider; multiple independent accounts for the same provider are not supported simultaneously.

## 自定义接口 / Custom endpoints

```yaml
model:
  vendor: custom
  name: your-model-id
  chat_url: https://your-gateway.example/v1/chat/completions
  api_key: ${CUSTOM_KEY}
```

`chat_url` 接受完整 Chat Completions 地址，内置厂商也可通过该字段覆盖地址。此字段在启动时写入主厂商的 Spring 属性；没有独立的 planner / reflector URL 字段。

`chat_url` is the full Chat Completions URL and can also override a named provider’s endpoint. It configures the main provider at startup; there are no separate planner / reflector URL fields.

## 工作目录、技能与 MCP / Workspaces, Skills, and MCP

- `workspace.dirs`：目录列表，省略时使用当前目录；运行时可 `/add-dir`。
- `skills.extra_dirs`：额外的插件根目录列表，支持 `~` 展开。
- 独立技能位置：项目 `.rex/skills`、用户 `~/.rex/skills`。
- MCP 配置位置：项目 `.rex/mcp.json`、用户 `~/.rex/mcp.json`；插件也可自带配置。
- `/plugin` 和 `/mcp` 的安装及启用命令见 `/help`，支持 user / project 作用域。

`workspace.dirs` lists roots and defaults to the current directory; `/add-dir` extends them at runtime. `skills.extra_dirs` adds plugin roots with tilde expansion. Standalone skills live in project `.rex/skills` or user `~/.rex/skills`. MCP configurations live in project/user `.rex/mcp.json`, and plugins can carry their own. Use `/help` for plugin and MCP commands and user/project scope options.

## 确认授权的作用域 / Approval scope

工具确认中的 `yes` 只批准当前调用；`always (this session)` 为当前会话记住该工具的批准记录。以会话 UUID 隔离，不会因 `/resume` 切换到其他会话而共享；同一 rex 进程内切回原会话会恢复该会话的批准记录，插件/MCP 重建 Agent 也不会清空。批准记录只在内存中保存，退出或重启 rex 后失效。

当前授权粒度仍是工具：例如当前会话对 `bash` 选择 `always` 后，该会话后续需要确认的 Shell 命令免确认；文件工具按各自工具名、MCP 按工具标识分别记忆。命令拦截和工作目录检查仍然执行。该选项不提供操作系统隔离，Shell 自动放行规则仍需后续单独收紧。

`yes` approves one call. `always (this session)` remembers approval for that tool under the current session UUID. Other sessions have independent approvals; switching back restores that session's approvals within the same rex process, including across agent rebuilds. Approvals are memory-only and expire when rex exits. The scope is still per tool, so approving `bash` this way skips later Shell confirmations in that session; command blocking and workspace checks still run.

## Opik（可选 / Optional）

```yaml
opik:
  enabled: true
  endpoint: http://localhost:5173/api/v1/private/otel
  project: regnexe-cli
  max_text_chars: 20000
```

默认关闭。开启后会向配置服务发送提示词、工具参数和结果（可能包含代码或文件内容）。云端部署可配置 `api_key` 和 `workspace`。协议和已知限制见 [Opik 设计说明](../design/06-opik-tracing.md)。

Disabled by default. Enabling it sends prompts, tool arguments, and output, potentially including code and file contents, to the configured service. Hosted deployments can set `api_key` and `workspace`. See the [Opik design and limitations](../design/06-opik-tracing.md).


## 会话打开与独占

`/resume <name>` 打开会话后持续占锁，包括任务预览、等待输入及执行完成后的空闲时间；其他 rex 窗口不能再打开同一会话。`--session <name>` 启动也遵守相同规则。重复 `/resume` 当前会话复用锁；切换到另一会话成功后释放原会话，目标忙碌则保留原会话。退出 rex 后释放锁。

任务恢复按预览的任务 ID 重新加载当前状态，不使用 state version。详细实现见[会话独占与指定任务恢复](../design/07-session-execution-and-resume-safety.md)。


## 任务运行中的输入与快捷键

真实终端中，任务运行时可以继续输入普通消息；消息补充到当前任务，不另建任务。等待模型响应时会取消旧请求，携带补充消息重新请求；工具正在执行时，补充指令由后续模型请求接入，已经发生的操作不撤销。

- **Esc**：暂停当前任务，取消模型请求、Shell 或工具确认等待，保留可恢复的任务进度。实际调用退出前仍视为运行中，不会启动另一个任务。
- **Ctrl+C**：退出 CLI，同时取消任务并清理资源；清理最多等待数秒，无法结束时执行进程退出兜底。
- **Enter**：空闲时启动任务，运行中补充指令；工具确认待答时，↑/↓ 或 ←/→ 切换选项，Enter 确认，默认选中 no；也可输入 yes/no/always/pause。
- 运行期间不切换会话或重建插件；先 Esc 暂停后再操作。

任务暂停后可用 `/resume <当前会话名>` 预览并补充指令继续。MCP 调用取消时会关闭本地连接，并在任务结束后重建连接；服务端已产生的副作用无法通过本地取消撤销。管道输入保持串行模式，兼容现有批量评测。

授权菜单选项为 `no / yes / always (this session) / pause`。只有等待授权时方向键用于选择，其他时间继续用于光标移动和历史记录；菜单关闭后恢复原按键功能。菜单导航不会提交正在编辑的指令草稿。Esc 仍暂停任务，Ctrl+C 仍退出。


## 启动与恢复会话历史

```bash
java -jar target/regnexe-cli.jar --session demo
```

显式指定 `--session demo` 启动，与进入 CLI 后执行 `/resume demo` 一样，会展示该会话已保存的对话历史；若有未完成任务，再显示其状态和进度，下一条普通输入继续该任务。只有历史而没有未完成任务时，下一条输入开启新任务，并沿用会话上下文。会话不存在时创建新会话。

打开会话只加载和展示，不自动调用模型。历史与现场任务复用当前主题的渲染：用户消息显示当前提示符，回答与实时任务结果使用同一入口，保留段落、代码块和保存的完整文本；已压缩的旧记录标注为历史摘要。两种入口都先取得会话锁。
