# 会话执行归属与指定任务恢复

实施日期：2026-11-05。对应 `regnexe-agent:0.1.4-SNAPSHOT`。

## 目标与范围

同一持久化会话在一台主机上只允许一个 CLI 执行者；恢复用户实际预览的任务；过期快照不能覆盖更新状态。这是任务持久化专项的第一阶段，不提供任意工具副作用的 exactly-once 保证。

## 会话身份与执行互斥

- `sessions.session_id` 的 UUID 是执行锁身份，历史任务和对话仍沿用会话名，避免混改历史存储协议。
- 创建会话使用 `INSERT ... ON CONFLICT(name) DO NOTHING` 后重读数据库。同名创建竞争中，所有参与者采用数据库中获胜的 UUID 和工作目录，不覆盖获胜者的工作目录。
- 无法读取持久化身份时停止打开该会话，不再生成随机 UUID 绕过互斥。数据库不可用时只允许创建临时、隐式的新会话。
- 工作线程调用 `FileChannel.tryLock()` 获取 `~/.rex/locks/session-<uuid>.lock`；测试数据库使用其所在目录下的 `locks/`。
- 锁覆盖新任务、任务恢复和直接 Skill 调用，以及暂停摘要写入；`/clear` 也须获取同一把锁。
- 空闲会话允许多个客户端打开。执行冲突立即报 busy，不进入任务 callable，不发起模型或工具调用。不同会话可以独立执行。
- 锁由实际工作线程持有，直到调用退出并完成同步持久化；主线程停止等待不会提前释放。进程退出由操作系统释放锁，锁文件保留且不按文件是否存在判断忙碌，也不删除锁文件。

文件锁是本地主机上的协作协议，不是分布式租约或文件系统沙箱。不同会话共用工作目录、数据库、远程服务时仍可能发生资源冲突。直接调用 Agent SDK 的其他宿主必须自行提供会话执行串行化。

## 恢复预览绑定

`/resume <name>` 仍显示最近更新的未完成任务，但将选择保存为：

```text
PendingResume(sessionUuid, taskId, expectedVersion)
```

下一次普通输入在锁内核对会话 UUID，调用：

```java
agent.resumeTask(sessionName, taskId, expectedVersion, supplement);
```

框架按 ID 加载，校验所属会话、版本和非 FINISHED 状态，然后保存 RUNNING 状态及补充输入。保存成功后才发送开始事件、读取上下文并进入执行循环。预览后出现另一个更新任务，不会改选该任务；目标任务已变化则拒绝恢复，用户需重新执行 `/resume`。

一次普通输入会消耗预览选择，失败后需要重新 `/resume`。新的 `/resume` 替换旧选择，包括目标会话没有任务的情况。原有 SDK `resume(sessionId, supplement)` 保留兼容，但 CLI 不再使用它。

## 版本校验

`TaskExecutionState.version` 表示存储修订。新任务从 0 开始，每次成功保存递增；`TaskStore.save()` 的实现必须执行比较后更新。

SQLite 增加 `version INTEGER NOT NULL DEFAULT 0`，自动兼容旧表。版本列是权威值，加载时覆盖 JSON 内版本和更新时间：

- 新任务插入并进入版本 1。
- 迁移后的旧记录从版本 0 开始，在版本匹配时更新到 1。
- 后续更新使用 `WHERE task_id=? AND version=?`，仅影响一行才算成功。
- 记录已删除或版本不匹配，抛出 `TaskConflictException`；不重建已删除的非零版本记录。
- 冲突属于 Agent 终止信号，工具层不得把它转换成普通工具失败后重试；执行阶段和顶层循环不得继续用旧快照保存 FAILED 状态。

内存 TaskStore 同样比较版本，并通过独立快照隔离嵌套轮次和消息，避免共享对象引用绕过版本控制。

## 验证与构建

CLI：`mvn package`。覆盖数据库迁移、跨连接版本冲突、并发创建身份、同会话互斥、不同会话并行、跨进程争抢、强制退出释放锁、等待线程中断时工作线程继续持锁，以及 busy 时不进入执行函数。

Agent：`mvn -Plocal-tests -Dgpg.skip=true -Dmaven.javadoc.skip=true install`。本地测试 profile 包含 `TaskResumeSafetyTest`，验证恢复指定任务、拒绝过期/完成/跨会话/缺失任务、竞争保存失败先于执行事件，以及内存快照隔离。测试不需要在线模型。

依赖构建顺序先 Agent 的 Maven install，再 CLI 的 package。该快照当前安装到本机 Maven 仓库，未发布到远程仓库。

## 边界与后续

升级后不要让旧版 CLI 与新版共同操作同一数据库：旧版不遵守锁和版本协议。自定义 TaskStore 也必须实现新的保存契约。

本阶段没有新增工具操作台账、UNKNOWN 状态、外部幂等键、远端结果查询、资源级锁或分布式租约。进程退出释放会话锁，并不证明所有外部副作用已经停止；外部请求已成功但本地尚未落盘的崩溃窗口仍存在。下一阶段为工具调用记录稳定操作 ID、参数摘要、开始/结果状态及副作用类别，再按操作能力决定核验或重试。
