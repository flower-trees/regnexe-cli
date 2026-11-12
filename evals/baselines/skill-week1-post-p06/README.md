# P06 预算与失败分类验证

结论：工程检查通过，不是 Skill 业务效果分数。内容 ID：`d9f070f42ad7fcc34aafebe7b66ad0be2fd3a9237fddb824f024de5aa9edfb67`。

[统一清单](manifest.json)、[测试与产物证据](verification.json)。相对 P05，变化为 Agent 源码、CLI 源码、运行产物及评测代码/输入；Python 环境与 Skill 材料不变。清单依旧标记 loading-only，正式模型配置尚未采集，工作区改动尚未提交。

## 配置与计数

预算片段见 [skill-week1-budget.yml](../../skill-week1-budget.yml)，不是可直接传给 REX_CONFIG 的完整配置。新增可选 agent.execution_budget：hard_max_iterations 为每轮硬上限，max_model_calls 为任务累计框架模型调用上限，retry_execution=false 关闭整段执行与工具函数自动重试；0 表示没有额外对应上限。未配置时保持原有动态预算设置。

- 外层轮数：Search/Plan/Execute/Reflect 循环；执行器迭代：一轮内模型调用步骤；工具数量：工具调用请求或完成记录。一次模型请求可要求多个工具，三者不能互换。
- modelCallAttempts 按 plan/execute/reflect/summary/subagent 累计实际进入同步模型 invoke 的尝试，包括失败、解析重试与交互补充指令后的重请求；并不等于成功 Token 汇总中的调用数。
- 预算附在任务请求中持久化；恢复保留累计计数，不自动重置耗尽额度。显式 resume 可以扩展外层轮数，但不能静默补充调用额度。
- 会话压缩以及 CLI 上下文压缩的模型适配参与预算；外部自定义代码自行调用其他模型不在此接口保证范围内。供应商 SDK 内部透明 HTTP 重试也不等于框架 invoke 次数，不能据此推断精确账单。

## 分类

| 层次 | 标签 | 含义 |
|---|---|---|
| 任务停止 | MODEL_CALL_LIMIT / ITERATION_LIMIT / ROUND_LIMIT | 累计模型调用、单轮迭代、外层轮数耗尽 |
| 任务停止 | USER_INTERRUPTED | 用户暂停，可恢复 |
| 任务停止 | MODEL_ERROR / TOOL_FAILURE_LIMIT / FRAMEWORK_ERROR / INCOMPLETE | 执行模型异常、连续工具异常、其他框架异常、需要后续处理 |
| 任务停止 | EXECUTOR_TIMEOUT / EXECUTOR_ABORT | 底层执行器超时/其他中止，不能误说成迭代耗尽 |
| 工具观察 | SUCCESS / TOOL_NONZERO_EXIT / TOOL_TIMEOUT / TOOL_ERROR / UNKNOWN | 已知 Shell 返回协议/工具异常的诊断；UNKNOWN 不能当成功 |
| 进程 | RUNNER_TIMEOUT / PROCESS_ERROR | Runner 外层截止时间/非零退出；独立于 Agent 状态 |

工具出错不必然让整个任务失败，模型可能恢复；反之 FINISHED 也不能代替业务产物验收。Shell 非零退出是返回值，不是 Java 异常，底层“连续工具异常次数”不能替代它的规则检查。进程退出 0 同样不代表 Agent 任务成功。

## 证据与边界

Agent 本地 64 项通过，其中预算新增 12 项；CLI Java 87 项通过，其中交互请求计数与真实 Shell 输出新增 2 项；最终真实 PTY + 本地 HTTP 模型 12 项全部通过；基线测试 4 项、进程保护测试 3 项通过。全程无官方模型、业务库、MCP 或 Opik 请求。进程超时测试保留 started 输出并阻止子进程延迟写入。

实际 BashTool 输出 5000 字符：共享模型收到的初次工具结果少于 2800 字符，含预览与完整文件指针，完整文件恰为 5000 字符。这是模型入口检查，不是 UI/Trace 展示检查；后续主动读取大文件仍会增加上下文。

调用计数有硬上限，Token 30000 只设观察阈值，没有硬 Token 断路器。框架未新增通用可强杀的网络超时；外层 [skill_process.py](../../skill_process.py) 用 POSIX 进程组兜底，已单独验证，尚未改动旧综合 runner.py。P07 单例入口必须接入它，设 120 秒且不自动重跑，然后冻结实际模型配置。另需完成 P04 人工文风确认及业务输出评分接入。

原始加载证据在 `target/skill-eval-week1/p06-loading-20261112/`；日志为 `/tmp/rex-p06-agent-tests.log`、`/tmp/rex-p06-cli-verify.log`、`/tmp/rex-p06-pty-final.log`。内部解析重试仍有界进行并纳入配额，禁止把它们当成新的评测样本。

Review 对应 Agent 提交：`8f9c0f5`（dev-0.1.4）。清单保留采集时的工作区状态，提交后内容 ID 不变，仅 Git 来源信息变化。
