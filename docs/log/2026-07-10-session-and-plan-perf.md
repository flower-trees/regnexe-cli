# 开发日志：Step 8 Session 管理 + Plan/Reflect 性能与可靠性优化

- 日期：2026-07-10
- 涉及仓库：`regnexe-cli`（本仓库）、`regnexe-agent`（sibling repo，`../regnexe-agent`）、`j-langchain`（sibling repo，`../j-langchain`，SNAPSHOT 依赖）
- 面向读者：下一个接手这部分代码的人或 AI；写这份日志的目的是让你不用重新 grep 一遍就能知道"现在到哪一步了、为什么这么改、接下来该做什么"。

## 0. 三仓库关系与构建方式（先看这个，否则改了白改）

`regnexe-cli` 依赖 `regnexe-agent`（1.0.x SNAPSHOT），`regnexe-agent` 依赖 `j-langchain`（`1.0.19-SNAPSHOT`）。三个都是本地 sibling 目录，走的是本地 Maven 仓库（`~/.m2`），**不是**远程发布。改动顺序必须是：

```bash
# 1. 改了 j-langchain 就先装它
cd ../j-langchain && mvn -q -o install -DskipTests

# 2. 改了 regnexe-agent（或刚装完 j-langchain）
cd ../regnexe-agent && mvn -q -o install -DskipTests

# 3. 最后编译/运行 regnexe-cli
cd ../regnexe-cli && mvn -q -o compile
```

漏掉任何一步，`regnexe-cli` 编译能过，但运行时用的是 `~/.m2` 里的旧 jar，改动不生效——这个坑在本 session 里踩过，记录一下避免重复。

## 1. 本 session 做了什么（摘要）

按时间顺序，两条主线：

1. **补完设计文档 Step 8（Session 管理）**，随后顺带把 Step 9（多目录）、Step 10（Pause/Resume）也在同一批工作里做掉了（commit 历史证实，不是本 log 单独驱动的，但状态一并记录在这里方便查）。
2. **分析并优化 Plan/Reflect 阶段的性能与可靠性**——起因是用 `regnexe-cli` 自举开发时，Step 8 这种"改 4 个文件、建 1 个新文件"的任务在 `max_agent_iterations=20` 下容易失败，順藤摸瓜发现了一串问题：plan parse 无 fallback、reflect 幻觉 FINISH、LLM HTTP 调用超时后任务不可恢复、Plan 阶段冗余 token 太多。逐条修完。

设计文档 `docs/design/agent-improvement.md` 记录了问题的原始诊断（六号"任务分解"提案），最终结论是**不做 coordinator/sub-task 那套重架构**，因为框架本身的"轮次（round）"机制已经天然提供了分解能力（每轮 `CapabilityExecutor` 都是全新的 LLM 会话，不共享上一轮的工具调用历史），真正的问题是 plan/reflect 的健壮性和 prompt 冗余，不是缺一层任务调度。

## 2. 主要开发内容

### 2.1 Step 8/9/10 —— Session 管理 / 多目录 / Pause-Resume

对应 commit（`regnexe-cli`）：
- `d8a9b6e` Feature (cli): Add session management and workspace support functionality
- `3d6e0df` Feature (cli): Add pause and resume functionality and workspace directory management

实现内容（与 `docs/design/design.md` Step 8/9/10 对照）：

| Step | 设计文档要求 | 实际实现 | 状态 |
|---|---|---|---|
| 8 | `--session <name>`、`--resume <id>`、`/sessions`、`/switch`、`/clear` | `db/SessionDao.java`（新建，plain JDBC，不是设计文档最初设想的 JDBI）+ `RexDatabase` 加 `sessions` 表 + `CliMain.java` 里 `resolveSession()` 等 | ✅ 完成 |
| 9 | `/add-dir`、`/dirs`，`WorkspaceContext` 多 root | `WorkspaceContext.addRoot()/resolveForRead()/describeRoots()` + `CliMain.java` 的 `/add-dir`、`/dirs` case | ✅ 完成 |
| 10 | `/pause` 斜杠命令、`--resume` | **没有做成独立的 `/pause` 斜杠命令**，而是复用了 `BashTool`/`FileTools` 已有的 `[y/N/pause]` 确认框——在任意工具确认处输入 `pause` 即可触发 `agent.pause()`（`CliMain.java:149-151` 的 `pauseAction`）。`--resume <session-id>` 已实现，走 `agent.resume(id, null)`。 | ✅ 功能等价，UX 路径不同于设计文档字面描述 |

**已知偏离设计文档的地方**：`/switch` 切换 session 时**不重建 Agent**（设计文档原方案是重建以重置 `BashTool` allowlist / `WorkspaceContext`）。之所以省掉，是因为这版 `BashTool` 本来就没有 per-session allowlist（无状态），`WorkspaceContext` 也不是 session 维度的——重建纯粹是无意义的开销。如果未来给 `BashTool` 加了 session 级别的白名单状态，这个假设要重新检查。

### 2.2 LLM 调用超时导致任务不可恢复（网络层可靠性）

**现象**：`RegnexeAgent loop failed: java.net.SocketTimeoutException: timeout`，来自 `HttpStreamClient.request()`（`j-langchain` 里同步非流式调用，Plan/Reflect 都走这条路径）。

**根因**：
1. `models.http.read-timeout-ms` 默认 60s（`JLangchainConfig.java`），Plan 阶段 prompt 变大后容易顶到。
2. 更严重的问题：`RegnexeAgent.runLoop()` 原来对任何异常（包括这种纯网络抖动）都设 `TaskStatus.FAILED`，而 `TaskStore.listResumable()` 只认 `PAUSED`——一次超时会导致之前几轮已经落盘的文件修改全部"不可恢复"，只能整个任务重开。

**修法**：
- `regnexe-cli/src/main/resources/application.yml`：加 `models.http.read-timeout-ms: 150000`。
- `regnexe-agent/.../RegnexeAgent.java`：`runLoop()` 的 catch 块新增 `isTransientIOException(e)` 判断（遍历 cause chain 找 `IOException`），命中则设 `PAUSED` 而不是 `FAILED`，不 rethrow，走正常的 post-loop 结果构建路径——这样 `--resume` 能捡回来。非网络异常行为不变（仍是 `FAILED` + rethrow）。

对应 commit：`regnexe-cli` 的 `b986d0c`，`regnexe-agent` 的 `1213229`（这个 commit 里和下面 2.3 的字段清理合并在一起了）。

### 2.3 Plan 阶段的冗余与可靠性（这是本 session 花时间最多的部分）

起因：用户观察到"模型没有思考过程，但 Plan 执行时间还是很长"，逐项分析优化。

**a) `PlanOutput.reasoning` 字段——纯浪费的输出 token**

`reasoning`（"why you chose these capabilities"）从写入到现在，下游代码从没读过 `getReasoning()`。删掉：`PlanOutput.java` 去掉字段，`TaskPlanner.java` 的 prompt schema、`recoverPlan()` 里的两处 `setReasoning()` 一并删除。

**b) Session history 每轮重复注入 Planner prompt**

`TaskPlanner.buildChatPrompt()` 原来每一轮都把 session 里之前的 SUMMARY+NORMAL 历史全量塞进 Planner 的 system/messages（且 `process()` 里还会把同一份历史再拼进 `plan.narrative` 给 Executor）。这份历史在任务开始时读一次就不再变化，但每轮都被重新序列化发送——round 2 开始纯属重复 prefill，因为 round 2+ 已经有 `lastHint()` + "Previous round summary" 提供更相关的续接上下文。

修法：`TaskPlanner.process()` 里新增 `isFirstRound = round == 1`，`hasHistory` 改为 `isFirstRound && sessionHistory != null && !isEmpty`；`buildChatPrompt()` 调用时改为 `hasHistory ? sessionHistory : null`。round 1 之后不再重复发送。`state.getCurrentRound()` 由 `CapabilitySearcher.java` 每轮开头 `+1`，第一轮恰好是 1，`resume()` 场景天然不会命中 round==1（因为 currentRound 不会被重置），这也是对的——resume 走的是 `ExecutionRecordFormatter.formatPreviousExecutionRecords()`，信息量更大更贴合。

**c) 没用原生 JSON 模式，纯靠 prompt 文字约束**

原来完全靠 `SYSTEM_PROMPT` 里一句"Output ONLY a valid JSON object"硬约束 + 客户端手工 `extractJson()`/`repairJson()` 兜底 + 失败重试（`MAX_PLAN_RETRIES=2`）。查证发现 `j-langchain` 传输层其实已经支持 `AiChatInput.responseFormat` → `OpenAIConver.convertRequest()` → `OpenAIRequest.responseFormat`，但**没有任何 `BaseChatModel` 子类真正设置过这个字段**，也没人用 `ModelSpec.modelKwargs`（这条通道从 `ModelSpec` 到 `DefaultModelProvider` 就是断的，`modelKwargs` 被悄悄丢弃，没有修复，只是绕开走了新路径）。

修法（`j-langchain`）：
- `BaseChatModel` 新增 `public BaseChatModel withJsonMode()`，默认 no-op（`return this`），不支持的 vendor 不用管。
- 11 个 OpenAI 兼容 vendor（`ChatDeepseek`、`ChatAliyun`、`ChatDoubao`、`ChatMinimax`、`ChatStepfun`、`ChatHunyuan`、`ChatZhipu`、`ChatLingyi`、`ChatMoonshot`、`ChatQianfan`、`ChatOpenAI`）全部重写：加 `jsonMode` flag，`withJsonMode()` 返回打了 flag 的 `copy()`，`otherInformation()` 里 `jsonMode=true` 时设置 `responseFormat={type:"json_object"}`。
- `ChatOllama`：Ollama 协议没有 OpenAI 式 `response_format`，改成往 `OllamaRequest` 加 `format` 字段，`OllamaConvert.convertRequest()` 把 `responseFormat.type=="json_object"` 翻译成 Ollama 自己的 `"format":"json"`。
- `ChatCoze` **刻意不实现**（继承默认 no-op）——它是 bot 调用协议（固定 `botId`），没有通用 completion 的 `response_format` 概念，硬做没有意义。

`regnexe-agent` 侧：`TaskPlanner.java`、`Reflector.java` 里 `llmProvider.provide(modelSpec)` 后面都加了 `.withJsonMode()`。**`CapabilityExecutor.java` 故意没动**——Execute 阶段是工具调用循环，json_object 模式和 tool-calling 的响应格式不兼容，只在 Plan/Reflect 这两个"输出结构化 JSON"的角色上开启。

对应 commit：`j-langchain` 的 `0922555`，`regnexe-agent` 的 `8435bf1`。

**d) Reflector 第三次转发同一份 Plan 内容**

`Reflector.buildPrompt()` 原来会把 `round.getPlan().getNarrative()` 和 `capabilityInputDescriptions` 原样再塞一遍进 Reflect 的请求——同一份内容在一轮里被发送了 3 次（Planner 生成一次、Executor 收一次、Reflector 又收一次），而 Reflector 判断 FINISH/CONTINUE 主要该看执行结果，不是看计划怎么写的。已删除这段注入，只保留 goal + 工具执行数 + 执行结果文本。`evaluateGuardRules()` 不受影响，因为它是从 Java 对象 `round.getPlan()` 直接读字段判断，不依赖 LLM prompt 文本。

同时给 `TaskPlanner.SYSTEM_PROMPT` 加了一条 `NARRATIVE LENGTH` 规则，明确要求 narrative 简短、不要重复 `capabilityInputDescriptions`/`finalAnswerRequirements` 里已有的内容。

对应 commit：`regnexe-agent` 的 `8435bf1`（和 c 项同一个 commit）。

## 3. 开发日志记录（按 commit 时间顺序）

| 时间 | 仓库 | commit | 内容 |
|---|---|---|---|
| — | regnexe-agent | `5c7afb1` | plan parse retry（最多 2 次）+ recovery plan（复用上一轮 plan / 全量 candidates 兜底）+ Reflector 零工具执行护栏 + `iterationsHint`（**本 session 开始前已完成，不是本 session 产出，但后续优化都建立在它之上**） |
| — | regnexe-cli | `d8a9b6e` | Step 8 Session 管理核心（sessions 表、SessionDao、`--session`） |
| — | regnexe-cli | `3d6e0df` | Step 10 Pause/Resume + Step 9 多目录 |
| 2026-07-10 | regnexe-cli | `b986d0c` | `models.http.read-timeout-ms` 调到 150s |
| 2026-07-10 | regnexe-agent | `1213229` | 移除 `PlanOutput.reasoning`；`RegnexeAgent.runLoop()` 网络异常转 `PAUSED` |
| 2026-07-10 | regnexe-agent | `8ebd408` | Planner session history 只在 round 1 注入 |
| 2026-07-10 | j-langchain | `0922555` | `BaseChatModel.withJsonMode()` + 11 个 OpenAI 兼容 vendor + Ollama 实现 |
| 2026-07-10 | regnexe-agent | `8435bf1` | `TaskPlanner`/`Reflector` 接入 `withJsonMode()`；Reflector 去掉重复的 narrative/capabilityInputDescriptions 转发；narrative 长度约束 prompt 规则 |

## 4. 已知问题 / 未验证项

- **本 session 里 2.3 节的改动没有做过真实的端到端 smoke test**（没有实际跑一个多文件任务观察 Plan 延迟是否真的降低、json_object 模式是否真的减少了 parse 失败率）。建议下一步找一个类似"改 3-4 个文件"的真实任务跑一遍，对比修改前后的 Plan 阶段耗时和 retry 次数。
- **`ModelSpec.modelKwargs` 链路仍然是断的**：`DefaultModelProvider.byVendor()/byPrefix()` 构建 `BaseChatModel` 时完全没有把 `spec.getModelKwargs()` 传给 builder。目前没有任何代码依赖这条路径（`withJsonMode()` 是另开的独立机制，没有用 kwargs），但如果以后想通过 `ModelSpec` 传 `temperature`/其他 vendor 专属参数（比如 `ModelSpec` 类注释里提到的 `"thinking":"enabled"`），这里要补上。
- **Reflector 精简后是否会影响某些边缘场景的判断质量没有验证**——理论上 `evaluateGuardRules()` 不受影响，但 LLM 自主判断部分（不是硬编码规则那部分）失去了 narrative 上下文，如果发现 Reflector 误判变多，第一个该看的就是这次改动。
- **`docs/design/agent-improvement.md` 六号"任务分解"提案的结论是不做**，理由记录在该文档和本 session 的讨论历史里；如果未来又出现"复杂任务在轮次预算内做不完"的情况，先确认是不是 Planner 把所有工作都规划进了一个巨型 `iterationsHint` 单轮（该被拆成多轮），而不是重新捡起 coordinator/sub-task 那套方案。

## 5. 未来开发计划

按优先级：

1. ~~**Step 11：打包与安装脚本**（`docs/design/design.md` 最后一步，完全没开始）——`pom.xml` 加 `spring-boot-maven-plugin` repackage、写 `install.sh`、`rex` wrapper 脚本。设计文档里有验证步骤可以直接抄。~~ 已完成，见第 6 节。
2. **验证 2.3 节的优化效果**（见"已知问题"第一条）——找真实任务跑一遍，量化 Plan 阶段延迟变化。
3. **`ModelSpec.modelKwargs` 断链问题**——如果要支持切换 Plan/Reflect 用更小更快的模型（本 session 讨论过、评估为"值得做但成本较高"的优化项），需要先把这条链路接上：`DefaultModelProvider` 把 kwargs 传给 vendor builder，各 vendor 的 `otherInformation()` 消费它。
4. **给 Plan/Reflect 配独立（更快/更便宜）模型**——`RegnexeAgentBuilder` 目前只有一个全局 `defaultModel`，Search/Plan/Execute/Reflect 四个角色共用。这是讨论过但判定为"比前几项成本高一档"、故意没做的优化，需要给 `ContextBusKeys` 加类似 `PLANNER_MODEL` 的 override 通道。
5. **Coze（`ChatCoze`）的 `withJsonMode()` 保持 no-op**，除非未来 Coze API 真的加了等价能力，否则不需要动。

## 6. Step 11 追加记录：打包与安装脚本

继续开发时已补完 `docs/design/design.md` Step 11：

- `pom.xml`：设置 `<finalName>regnexe-cli</finalName>`，并把 `spring-boot-maven-plugin` 的 `repackage` goal 绑定到 package 生命周期，产物固定为 `target/regnexe-cli.jar`。
- `install.sh`：新增安装脚本，默认把 JAR 安装到 `/opt/regnexe/regnexe-cli.jar`，把 wrapper 写到 `/usr/local/bin/rex`；支持 `SOURCE_JAR`、`INSTALL_DIR`、`BIN_DIR`、`BIN_NAME` 环境变量覆盖，方便无 sudo smoke test。
- `scripts/smoke-test.sh`：新增安装 smoke test，串起 Maven package、临时目录安装、wrapper version 验证。
- `CliMain.java`：新增早期 `--help`/`-h`、`--version`/`-v` 处理，避免 `java -jar target/regnexe-cli.jar --help` 初始化 Spring 或进入 REPL。

已验证：

```bash
scripts/smoke-test.sh
```

没有执行默认 `/opt/regnexe` + `/usr/local/bin` 的真实全局安装，避免在开发机上写系统目录；脚本默认路径仍按设计文档保留。

## 7. 给下一个 AI 的建议

- 改 `regnexe-agent` 或 `j-langchain` 之后，**一定要按第 0 节的顺序 `mvn install`**，否则 `regnexe-cli` 用的是本地仓库缓存的旧版本，改了等于没改，还会误判"这个 bug 还在"。
- 三个仓库目前都在 `master` 分支直接提交，没有走 PR review 流程；改动前后建议自己跑一下 `mvn -q -o compile`（三个仓库都要过）。
- 这份日志覆盖的是"性能与可靠性"这条线，不是功能新增；如果要继续做 Step 11 或新功能，起点应该是 `docs/design/design.md`，不是这份日志。
