# Opik 链路追踪

把每次任务的执行过程（规划、执行、反思、每次 LLM 调用和工具调用）导出到 [Opik](https://github.com/comet-ml/opik)，在界面里按树状查看 prompt、输出、耗时和 token。

默认关闭。trace 里包含 prompt、文件内容和工具输出，只有显式开启才会向外发送。

## 开启

在 `~/.rex/config.yml` 里加：

```yaml
opik:
  enabled: true
  # 以下都有默认值，本地自部署的 Opik 不需要改
  endpoint: http://localhost:5173/api/v1/private/otel
  project: regnexe-cli
  max_text_chars: 20000     # prompt / 工具输出超过这个长度会被截断
  # api_key: ${OPIK_API_KEY}   # 仅 Comet 云端需要
  # workspace: my-workspace    # 仅 Comet 云端需要
```

本地 Opik 的安装和启动见 `~/Documents/develop/my/opik/docs/local-install.md`。

Opik 连不上时，CLI 照常工作：在 stderr 打印一次警告，期间的 trace 丢弃，恢复后自动继续。

## trace 结构

一次任务运行（`execute`、`resume`、`executeSkill`）对应一条 trace，同一个会话的 trace 通过 `thread_id`（会话 id）归到同一个 thread。

```
task                              AGENT_STARTED .. AGENT_COMPLETED
├─ search R1                      SEARCH_STARTED .. SEARCH_COMPLETED
├─ plan R1                        PLAN_STARTED .. PLAN_COMPLETED
│  └─ llm:plan
├─ execute R1                     EXECUTION_STARTED .. EXECUTION_COMPLETED
│  ├─ llm:execute                 带模型名和 token 用量
│  ├─ mcp_tool:read_file          TOOL_CALLED .. TOOL_RESULT
│  ├─ sub_agent:xxx               子 agent / skill 本身是一次工具调用
│  │  ├─ llm:sub_agent
│  │  └─ mcp_tool:bash
│  └─ llm:execute
└─ reflect R1                     REFLECTION_STARTED .. REFLECTION_COMPLETED
   └─ llm:reflect
```

## 实现

| 类 | 作用 |
|---|---|
| `event/OpikEventListener` | 把 `AgentEvent` 流还原成 span 树；和 `CliEventListener` 一起通过 `withEventListener` 注册（`regnexe-agent` 支持注册多个监听器） |
| `observe/OtlpSpanExporter` | 后台线程批量发送，OTLP/HTTP JSON 格式，只用 JDK `HttpClient` 和 Jackson，没有新增依赖 |
| `observe/SpanData` | span 数据 |

两点需要知道的事实：

1. **`*_LLM_RESPONDED` 事件在调用模型之前发出，内容是 prompt，不是模型的回复。** 所以它用来开启 LLM span。Plan 和 Reflect 调用结束后，`regnexe-agent` 会再发一个 `LLM_OUTPUT`（模型的原始回复）和一个 `TOKEN_USAGE`，用来填 span 的输出和用量。执行循环没有 `LLM_OUTPUT`，输出由后续事件补上（这次调用发起的工具调用，或所在阶段的结果）。
2. **事件没有 span id，父子关系靠顺序还原。** 一个任务内 LLM 调用和工具调用是严格串行的，未结束的工具调用一定是当前最内层，所以用栈。如果以后 agent 支持并行工具调用，这里会配错，需要先在 `AgentEvent` 里加 span id。

发给 Opik 的属性（经实测确认 Opik 的映射）：

| 属性 | Opik 里的效果 |
|---|---|
| `input.value` / `output.value` | 输入 / 输出 |
| `gen_ai.prompt` / `gen_ai.completion` | LLM span 的输入 / 输出 |
| `gen_ai.request.model`、`gen_ai.system` | 模型、provider，span 类型识别为 llm |
| `gen_ai.usage.input_tokens` 等 | token 用量，自动汇总到 trace |
| `gen_ai.tool.call.arguments` | 工具参数，span 类型识别为 tool |
| `thread_id` | 归入 thread |
| `metadata.*` | 进 metadata（其他不认识的 key 会被塞进 input） |
| span status = error | error_info |

## 已知限制

- **执行循环里 LLM span 的输出是近似的。** 执行循环的事件来自 `j-langchain` 的 `McpAgentExecutor`，它没有暴露模型的原始回复，所以用"这次调用发起的工具调用"或"阶段最终结果"代替；模型在发起工具调用时附带的文字拿不到。要补上需要在 `McpAgentExecutor`（以及 `SubAgent`、`Skill`）里加一个回复回调。Plan 和 Reflect 的输出是模型的原始回复。
- **Plan / Reflect 调用失败（网络或 HTTP 错误）时没有用量和输出**，span 只有 prompt 和耗时。
- **成本为空。** Opik 不认识自定义模型名，算不出价格。
- 需要 `regnexe-agent` 含 `LLM_OUTPUT` 事件的版本（0.1.3-SNAPSHOT 的本地构建）。
