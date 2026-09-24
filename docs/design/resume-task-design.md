# `/resume`：恢复未跑完/异常退出的任务

- 状态：**已实现**
- 涉及仓库：`regnexe-agent`（`TaskStore`/`InMemoryTaskStore`/`RegnexeAgent`）、`regnexe-cli`（`SqliteTaskStore`/`CliMain`）
- 关联：这次改动直接依赖 `regnexe-agent` 的 12 号文档（`state.priorSteps`/`priorStepsSummary`，跨轮共享 `AgentTaskContext`）——正是这套机制让"恢复后不丢工具调用"这件事不需要专门再做什么。

---

## 一、背景：这个功能之前做过一版，后来整个删了

`regnexe-agent` 的 `22a9aaf`、`regnexe-cli` 的 `fd896b5`（同一天，9 月 2 号）把整套暂停/恢复机制删掉了：`--resume`/`--force-resume`/`--continue`/`/resume`/`/continue`、`RegnexeAgent.resume()`、`TaskStore.listResumable()`、`ExecutionRecordFormatter`、`CapabilityExecutor` 的 `resumeMode` 参数，以及两个专门的集成测试。提交信息只描述了删了什么，没写为什么——但从时间线看，这次删除发生在这次会话一连串上下文/记忆架构重构（09→12 号文档）之前，是重构前的一次清场。

旧设计的核心机制（`git show 22a9aaf`/`fd896b5` 能看到完整实现）：
- `TaskStatus.PAUSED`：Ctrl+C 或者特定 HTTP 状态码（429/500/502/503 视为瞬时错误，401/402 视为用户可自行解决）触发，跟硬 `FAILED`（403/404、其他未分类异常）区分开。
- `TaskStore.listResumable(sessionId, includeFailed)`：按 session 查 `PAUSED`/`RUNNING`（+`includeFailed`时含`FAILED`）状态的任务，取最新一条。
- `RegnexeAgent.resume(sessionId, supplementInput, force)`：加载任务、状态改回 `RUNNING`、补充输入塞进 `TaskRequest.supplementInput` 字段、重新进 `runLoop()`，靠 `resumeMode` 参数让 `CapabilityExecutor`/`Reflector`/`TaskPlanner` 对"恢复场景"做特殊处理。
- CLI 层：`--resume <name>`/`--force-resume <name>`/`--continue`/`-c`，加上 REPL 内的 `/resume`/`/continue`。

## 二、这次要做成什么样

跟 Claude Code 的 `/resume` 一个思路：选一个 session，加载它的历史；如果这个 session 还有没跑完/异常退出的任务，把它的状态（包括之前的工具调用历史）也加载进来，用户可以直接接着跑，不丢进度。具体决策：

1. **`FAILED` 跟其他状态一视同仁可恢复**，不再要求 `--force-resume` 这种显式确认——旧设计里"重试同一个错误没有意义"的顾虑，现在靠恢复摘要里把失败/升级原因明说出来解决，不靠一个额外的命令行开关拦。
2. **恢复时用户输入的补充内容，直接当成对当前 goal 的追加**，不重新引入 `TaskRequest.supplementInput` 这个已经删掉的字段——`resume()` 直接把它拼到 `state.getRequest().getGoal()` 后面。这样这段内容会自然流经 Plan/Execute/Reflect 每个阶段本来就在读的"Goal"这一节 prompt，不需要新增任何 plumbing。
3. **终端 UI 不做交互式列表选择**——这个 CLI 现在完全没有方向键选择控件，`/sessions` 打印表格、`/switch <name>` 输入名字直达，这次沿用同样的交互习惯：想看有哪些 session 先敲 `/sessions`，`/resume <name>` 直接按名字操作。
4. **`/resume <name>` 完全取代 `/switch <name>`**，不并存两条命令。原因：`/switch` 本来就要按名字查 `SessionRow`、重建 agent，多查一次"这个 session 有没有 resumable 任务"成本趋近于零，没必要为了这一个查询单独维护一条命令；对没有未完成任务的 session，`/resume <name>` 的行为跟今天的 `/switch <name>`完全一样，纯粹是名字换了、多了个"顺便检查一下"的能力，没有额外心智负担。
5. **不自动继续执行**——`/resume <name>` 只做两件事：切换/创建 session、如果有 resumable 任务就加载并打印摘要（状态、跑到第几轮、如果是 FAILED/ESCALATED 就带上原因、最近一次的部分执行结果）。真正的续跑要等用户在 REPL 里敲下一行输入，那行输入就是"追加到 goal 的补充内容"，触发 `agent.resume(...)`。这跟这个 REPL"敲了才动"的交互习惯一致，避免命令一敲就发起一次可能很长/很贵的 LLM 调用。
6. **一个 session 只看最新的一条 resumable 任务**（按 `updatedAt` 取最大），更早的不管，不特意提示"还有 N 条更早的"。
7. **`/resume <name>` 找不到这个名字就新建**，跟 `/switch` 一样 find-or-create；对着当前就在的 session 敲也照样生效（不像旧 `/switch` 那样直接短路"Already in session"），因为这时候"检查有没有未完成任务"才是主要目的，不是切上下文。
8. **`maxRounds` 恢复时给一点余量**：如果任务是正好在 `currentRound >= maxRounds` 时被打断/超时的（`TaskStatus.TIMEOUT`，或者进程在最后一轮崩溃），照原样恢复会立刻再次撞到轮数上限、一步都跑不动。`resume()` 里加一个判断，命中时把 `maxRounds` 往上提几轮（`RESUME_ROUND_MARGIN = 3`）。这是个纯数值字段的一次性判断，不影响别处逻辑，实现和验证都很直接。
9. **不做启动时主动提示**（"这个 session 有未完成任务，输入 /resume 继续"）——用户自己知道就行，这属于锦上添花，先不做。

## 三、跟旧设计相比，一个明显的简化

旧设计里 `resumeMode` 参数要一路传进 `CapabilityExecutor`/`Reflector`/`TaskPlanner`，让这几个阶段对"这是恢复场景"做特殊处理（`ExecutionRecordFormatter` 专门负责把历史记录重新渲染进 prompt）。这次完全不需要——`state.priorSteps`/`state.priorStepsSummary`（12 号文档那套跨轮共享 `AgentTaskContext`）本来就是每轮无条件回写的，`TaskPlanner` 的 `hasHistory`/`noRoundSucceededYet` 判断也是通用的、不区分"这是第一次跑还是恢复后跑"。`resume()` 只需要把持久化的 `TaskExecutionState` 加载回来、状态改回 `RUNNING`，直接调用跟 `execute()` 尾部完全一样的 `runLoop(state, sessionHistory)`——不需要一个专门的 `resumeMode` 标志位，也不需要任何阶段知道"这轮是不是恢复的"。

## 四、哪些"产出方"其实从来没坏过

删除的时候只删了"消费方"（CLI 的 `--resume`/`/resume`、`RegnexeAgent.resume()`、`TaskStore.listResumable()`），下面这些"产出方"从头到尾都还在正常工作，不需要动：

- `TaskStatus.PAUSED` 枚举值还在，`CapabilityExecutor` 的 `AgentStoppedException` 分支现在依然会把状态设成它（`CapabilityExecutor.java:227`）。
- `regnexe-cli` 的 Ctrl+C 处理依然调用 `agent.pause()`（`CliMain.java:327`），链路完整：Ctrl+C → `pause()` → `stopSignal` 置位 → `AgentStoppedException` → `CapabilityExecutor` 存 `PAUSED` → `taskStore.save(state)`。
- `RexDatabase.listSessions()`/`findSessionByName()`/`upsertSession()` 都还在，`/sessions` 一直用着。
- `CliMain.handleAgentResult()` 遇到 `TaskStatus.PAUSED` 已经会调 `storePausedTaskSummary()`（把"[Task paused]"摘要存进 session 对话历史）+ `renderer.paused(...)`。

真正缺的只是"Ctrl+C 之外的异常退出"（`kill -9`、终端被强关、系统崩溃）——这几种连 shutdown hook 都来不及跑，任务会一直以 `RUNNING` 状态停留在 DB 里。这次不打算靠 shutdown hook 补（旧设计那个 `markAllRunningAsPaused()` 也堵不住 `kill -9`），而是直接把 `RUNNING` 纳入 `listResumable()` 的可恢复状态集合——反正这个 CLI 是单进程串行跑任务的，只要有 `RUNNING` 状态的行还在库里、本次进程又是刚启动的，那必然是上一个进程留下的孤儿任务，直接当可恢复处理即可。

## 五、实现

### `regnexe-agent`

- `TaskStore` 接口新增：
  ```java
  /**
   * 这个 session 里所有还没定论、值得续跑的任务：除了 FINISHED 之外的所有状态。
   * RUNNING（进程被强杀，没机会存成 PAUSED，也不该被无声丢弃）、PAUSED（正常 Ctrl+C）、
   * FAILED、ESCALATED、TIMEOUT（都可以带着新的补充指令重试，不是盲目重放同一个错误）。
   */
  List<TaskExecutionState> listResumable(String sessionId);
  ```
- `InMemoryTaskStore` 实现：按 `sessionId` + `status != FINISHED` 过滤。
- `RegnexeAgent` 新增 `resume(String sessionId, String supplementInput)`：
  - `taskStore.listResumable(sessionId)`，取 `updatedAt` 最大的一条，没有则抛 `IllegalStateException`。
  - 补充输入非空时拼进 `state.getRequest().getGoal()`。
  - `currentRound >= maxRounds` 时把 `maxRounds` 加 `RESUME_ROUND_MARGIN`（3）。
  - `state.setStatus(RUNNING)`，派发一个 `AGENT_STARTED` 事件说明是恢复。
  - `loadSessionHistory()` + `runLoop(state, sessionHistory)`——跟 `execute()` 尾部完全一样，不带任何 resume 专属参数。

### `regnexe-cli`

- `SqliteTaskStore` 实现 `listResumable`：`SELECT data FROM task_execution_states WHERE session_id = ? AND status != 'FINISHED'`。
- `CliMain`：`/switch` 案例改名合并成 `/resume <name>`——保留原有 find-or-create + 重建 agent 逻辑，额外查一次 `listResumable`，有结果就打印摘要（状态/轮数/失败原因/最近部分结果）并把"下一行输入当 resume 补充内容"这个标志位置位；`SlashResult` 加一个 `Boolean pendingResume` 字段（`null` = 不改动标志位，其他 slash 命令都不碰它；非 null 才是 `/resume` 自己要设置的值），主循环里遇到非斜杠输入时，标志位为真就调 `agent.resume(...)`、消费一次后复位，否则走原来的 `agent.execute(...)`。

## 六、暂不处理的

- 启动时主动提示 resumable 任务（可以后续加，不影响现在能不能用）。
- 一个 session 里多条 resumable 任务只处理最新一条，更早的没有专门的列表/清理机制。
- 没有专门的"取消 pending resume"命令——真要放弃，重新 `/resume` 一个别的 session 就会覆盖掉标志位。
