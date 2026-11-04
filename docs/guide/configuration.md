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
