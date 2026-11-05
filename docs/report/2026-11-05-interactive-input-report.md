# 改动分析报告：CLI 持续输入、运行中补充指令与取消

报告日期：2026-11-05
仓库：`regnexe-cli`（Maven / Java，Spring Boot 打包为 `target/regnexe-cli.jar`）
基线提交：`bebae5b fix: hold exclusive session locks from open until close`

---

## 1. 改动概况

| 项目 | 数值 |
|---|---|
| 修改文件数 | 11 |
| 新增行 | +384 |
| 删除行 | −133 |
| 新增 Java 包 | `org.salt.regnexe.cli.interactive`（4 个 main 类）+ 1 个测试类 |
| 新增文档 | `docs/design/08-interactive-input-and-cancellation.md` |
| 新增评测脚本 | `evals/test_interactive_controls.py` |

改动是一个完整的「新功能 + 文档 + 测试」切片，承接上一提交的会话锁与 `/resume` 安全性工作，目标是**让任务运行时终端仍可输入**：普通消息补充当前任务、Esc 暂停、Ctrl+C 退出。

---

## 2. 核心新增能力：`cli/interactive` 包

| 文件 | 行数 | 职责 |
|---|---|---|
| `InteractiveTaskRunner.java` | 119 | 单任务执行器：单线程工作池 + `Run` 状态对象，管理补充消息、取消回调、空闲等待；`guard()` 包装工具，在工具开始前检查取消、在取消时中断调用线程并关闭 transport |
| `InteractiveChatModel.java` | 83 | 装饰同步模型：在模型调用边界把补充消息追加为用户消息并写入任务 goal；按 revision 判断是否需要重新请求；`installCancellation()` 通过 OkHttp 拦截器对当前请求登记定向取消（覆盖 common / chatGPT 两组 `HttpStreamClient`） |
| `InteractiveApproval.java` | 37 | 工具确认邮箱：后台工具提交 `CompletableFuture`，仅由主输入循环读取终端作答（yes/no/always/pause），取消时返回 PAUSE，避免与 REPL 抢读 |
| `AboveLineWriter.java` | 16 | 后台输出缓冲后交给 JLine `printAbove()`，保留用户正在编辑的输入缓冲 |
| `InteractiveTaskRunnerTest.java`（test） | 84 | 4 项单元测试 |

关键设计点（来自代码与设计文档 08）：

- 输入线程**不等待任务 Future**，也不并发执行第二个任务；任务停止中拒绝重叠启动。
- 补充消息只取消**模型 HTTP 请求**，正在执行的工具继续，已完成的工具副作用不回滚。
- 取消幂等；控制器保持 active 直到实际工作函数与持锁清理退出，不用 `Future.cancel` 的完成标记判断。
- 补充内容按顺序写入 `TaskExecutionState.request.goal` 并保存，可通过 `/resume` 继续。
- 管道输入保留原串行 REPL，避免批量评测的多轮输入被误当作同一任务的补充。

测试覆盖（`InteractiveTaskRunnerTest`）：
1. 运行中输入归属当前任务、取消后不能再补充、不可重叠启动；
2. 取消后受保护工具不能开始副作用（抛 `AgentStoppedException`）；
3. 审批邮箱等待可被 Esc 解除并返回 PAUSE；
4. 补充指令只触发模型取消回调、不触发工具取消，且 pause 幂等。

---

## 3. 既有代码修改

| 文件 | 变化 | 说明 |
|---|---|---|
| `src/main/java/org/salt/regnexe/cli/CliMain.java` | +363 | 改动重心：真实终端异步 REPL，主线程持续 `readLine()`，后台保持单一执行者；运行中普通输入追加为补充消息；运行中拒绝切换会话与插件操作；Esc 暂停、Ctrl+C 退出（执行器最多等 3 秒，进程 5 秒兜底）；在会话锁内落盘补充消息与 PAUSED 状态 |
| `src/main/java/org/salt/regnexe/cli/ui/TerminalCliRenderer.java` | +17 | 配合输入行重绘 / 输出上移，与 `AboveLineWriter` 协同 |

---

## 4. 文档改动清单

- 新增 `docs/design/08-interactive-input-and-cancellation.md`（64 行）：输入与任务执行、补充指令生效路径、Esc 暂停、Ctrl+C 退出、验证与边界。
- `docs/design/README.md`：索引登记编号 08。
- `docs/guide/configuration.md`：+12 行。
- `docs/guide/release-validation.md`：小幅更新。
- `docs/log/2026-11/2026-11-05.md`：+49 行（第 4 节「CLI 持续输入、运行中补充指令及快捷键」）。
- `docs/log/README.md`、`docs/plan/01-project-analysis-and-roadmap.md`：同步更新。

---

## 5. evals 改动清单

- `evals/interactive_runner.py`：+38 行，改为等待任务完成通知而非持续提示符，暂停步骤使用 Esc，补充 Ctrl+C 退出断言。
- `evals/dataset/hardening-interactive-2026-11.yaml`：微调 5 行。
- 新增 `evals/test_interactive_controls.py`（210 行）：真实 PTY + 本地模拟模型 HTTP 服务（stall / steer / shell 模式），无 Key、无 Opik、无付费请求，覆盖 5 个场景：
  1. 模型不返回时可补充指令，同一任务保存补充并由 Esc 暂停；
  2. 补充指令把原定文件写入改为另一文件，保持单一 taskId；
  3. Shell 执行中 Esc 停止真实子进程，保留部分输出并保存 PAUSED；
  4. 确认期间正常回答可完成任务，Esc 可取消确认；
  5. 模型不返回时 Ctrl+C 及时退出。
- `evals/README.md`：+14 行。

---

## 6. 验证结果（开发日志记录）

| 验证项 | 结果 |
|---|---|
| Agent 本地测试 | 44 项通过 |
| CLI `mvn -q package` | 59 项 Java 测试通过，打包成功（新增 4 项交互控制测试） |
| `evals/test_hardening.py` | 11 项通过 |
| `evals/test_interactive_controls.py`（真实 PTY） | 5 项通过 |
| `scripts/smoke-test.sh --skip-build` | 通过 |
| `git diff --check` / Python AST | 通过 |

断言时延阈值：补充输入响应 < 3s、模型重新请求 < 5s、Esc 暂停 < 5s、Ctrl+C 退出 < 6s（含 PTY ESC 歧义判定与清理，非生产时延保证）。

---

## 7. 改动意图与风险评估

**意图**：解决主输入线程等待 Future、取消信号无法及时影响阻塞调用的问题，使 CLI 从「串行 REPL」升级为「持续输入 + 单任务后台执行」，并把暂停/恢复与已有的会话独占锁、`/resume` 机制打通。

**风险与边界**：

| 风险 | 说明 |
|---|---|
| 外部副作用不可回滚 | 关闭本地 HTTP 连接不能撤销外部已发生的操作；Shell 子进程清理依赖已有取消检测 |
| MCP 取消/重连未验收 | 未验证真实外部 MCP 服务的取消与重连，部署环境需自行验收 |
| 直接 Skill 不可恢复 | 沿用非 `TaskExecutionState` 路径，可中断但无断点恢复；极晚到达的补充会提示重发，避免重放副作用 |
| 双模型客户端装配 | 本地 PTY 启动曾发现 common/chatGPT 两个 `HttpStreamClient` Bean，已改为按列表装配并分别安装拦截器；新增模型客户端时需同步 |
| 评测未覆盖真实模型 | 未重跑官方 DeepSeek 四场景、未上传 Opik 新结果 |
| 提交状态 | 本节代码与文档尚未提交或 push |

---

## 8. 结论

本次改动以 4 个新类（共 255 行 main 代码）+ `CliMain` 363 行改造为核心，配套 1 篇设计文档、6 处文档同步与 5 个真实 PTY 场景测试，形成可验证的完整功能切片。核心取舍是「只取消模型请求、不打断正在执行的工具、不回滚已完成副作用」，在保证交互响应性的同时维持执行归属与副作用安全。建议合入前补充一次真实模型与真实 MCP 服务的验收。