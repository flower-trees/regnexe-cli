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

一个任务对应一条 trace，同一个会话的 trace 通过 `thread_id`（会话 id）归到同一个 thread。`/resume` 恢复执行不产生新 trace，而是挂在原任务的根 span 下。

```
task                              AGENT_STARTED .. AGENT_COMPLETED
├─ search R1                      SEARCH_STARTED .. SEARCH_COMPLETED
├─ plan R1                        PLAN_STARTED .. PLAN_COMPLETED
│  └─ llm:plan
├─ execute R1                     EXECUTION_STARTED .. EXECUTION_COMPLETED
│  ├─ llm:execute                 带模型名和 token 用量
│  ├─ mcp_tool:read_file          TOOL_CALLED .. TOOL_RESULT
│  ├─ sub_agent:xxx               子 agent 本身是一次工具调用
│  │  ├─ llm:sub_agent
│  │  └─ mcp_tool:bash
│  └─ llm:execute
├─ reflect R1                     REFLECTION_STARTED .. REFLECTION_COMPLETED
│  └─ llm:reflect
└─ resume                         之后的 /resume，可以有多次
   └─ search R2 / plan R2 / execute R2 / reflect R2
```

## 实现

| 类 | 作用 |
|---|---|
| `event/OpikEventListener` | 把 `AgentEvent` 流转成 span；和 `CliEventListener` 一起通过 `withEventListener` 注册 |
| `observe/OtlpSpanExporter` | 后台线程批量发送，OTLP/HTTP JSON 格式，只用 JDK `HttpClient` 和 Jackson，没有新增依赖 |
| `observe/SpanData` | span 数据 |

要点：

1. **父子关系来自事件本身。** 每个事件带 `spanId`，开启一次调用的事件还带 `parentSpanId`，监听器直接照用，不靠事件顺序推断。trace id 和根 span id 由 `taskId` 确定（`SpanIds.traceId` / `SpanIds.rootSpanId`）。模型见 `regnexe-agent` 的 `docs/design/13-event-span-model-design.md`。
2. **`*_LLM_RESPONDED` 事件在调用模型之前发出，内容是 prompt，不是模型的回复。** 所以它用来开启 LLM span。Plan 和 Reflect 调用结束后会再发 `LLM_OUTPUT`（模型的原始回复）和 `TOKEN_USAGE`。执行循环没有 `LLM_OUTPUT`，输出由后续事件补上（这次调用发起的工具调用，或所在阶段的结果）。
3. **任务一开始就发根 span，结束时再更新 trace。** OTLP 的 span 只能在结束后发送一次，Opik 又要收到根 span 才显示 trace。为了执行过程中就能看到，`task`（以及 `/resume` 的 `resume`）span 在 `AGENT_STARTED` 时作为零时长的标记立刻发出；其余 span 仍在各自结束时发送。任务结束后通过 Opik 的 REST 接口（按 `metadata.task_id` 查到 trace，再 `PATCH`）写入输出、结束时间、错误信息和 token 汇总。如果查不到 trace（任务开始时 Opik 不可用，或被恢复的任务原来没开追踪），就补发一个带完整结果的根 span。这一步只对 Opik 形态的 endpoint（以 `/otel` 结尾）生效。
4. **恢复执行不重发根 span。** Opik 不接受同一条 trace 的第二个根 span，`resume` span 以原任务的根为父。

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
- **`task` 和 `resume` span 本身没有时长和输出。** 它们是开始时发出的标记，结果写在 trace 级别；各阶段、LLM、工具 span 的时长和输出是完整的。
- **执行中只能看到已结束的步骤。** 正在进行的那一步要等它结束才出现；一个阶段没结束时，它下面已完成的 LLM / 工具 span 已经在 Opik 里，但父节点还没到。进程被强杀时，这些 span 的父节点永远不会到。
- **进程被杀后 trace 停在未完成状态**（没有输出和结束时间），直到这个任务被 `/resume` 并跑完。
- **工具名和参数仍从事件文本里切出来。** 事件的 `text` 格式没有改，结构化要等 `j-langchain` 的回调改造。
- 需要 `regnexe-agent` 带 `spanId` / `LLM_OUTPUT` 的版本（0.1.3-SNAPSHOT 的本地构建）。
