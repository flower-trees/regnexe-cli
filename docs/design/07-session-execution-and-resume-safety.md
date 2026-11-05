# 会话独占与指定任务恢复

实施日期：2026-11-05。对应 `regnexe-agent:0.1.4-SNAPSHOT`。同日最终约定：打开会话即占锁，移除任务状态版本机制。

## 会话生命周期

`/resume <name>` 视为打开会话。先按持久化 UUID 获取本地文件锁，再加载任务摘要；等待输入和执行结束后均继续持锁。同一会话不能被其他窗口打开。没有未完成任务的会话也遵守相同规则。

启动时的 `--session <name>` 和隐式新会话也在初始化 Agent 前获取锁。重复 `/resume` 当前会话复用已有锁，不重复获取。

切换会话先锁住目标，成功后才释放原会话。目标忙碌时保留原会话和原恢复选择，不展示目标任务，也不重建 Agent。退出、EOF 或异常离开会话时释放锁；进程被强制结束则由操作系统释放。任务失败、暂停和 `/clear` 不关闭会话。

锁文件位于 `~/.rex/locks/session-<uuid>.lock`；显式测试数据库使用所在目录下的 `locks/`。文件保留，不按文件存在判断忙碌，也不删除后重建锁文件。

`SessionContext` 持有会话锁。普通任务、恢复和 Skill 工作线程引用已有锁；若主线程被中断并关闭会话，正在执行的工作线程退出前继续保有锁，避免过早交接。暂停摘要写入也在工作线程持有期间完成。

## 身份与任务恢复

`session_id` UUID 用于锁身份，历史任务和对话仍用会话名，保持存储兼容。并发创建同名会话采用 `INSERT ... ON CONFLICT(name) DO NOTHING` 后重读，确保返回数据库中获胜的 UUID 和工作目录。无法读取身份时拒绝打开命名会话；数据库不可用时只允许临时隐式新会话。

预览记录：

```text
PendingResume(sessionUuid, taskId)
```

下一次普通输入检查会话身份，再调用：

```java
agent.resumeTask(sessionName, taskId, supplement);
```

框架按任务 ID 重新加载当前状态，校验所属会话及任务未完成，保存补充输入和 RUNNING 状态后继续。任务已完成或不存在时拒绝恢复；不重新挑选另一个任务。新 `/resume` 替换选择；一次普通输入消耗选择，执行失败后需要重新 `/resume`。

## 存储保持简单

移除 `TaskExecutionState.version`、预览版本、条件更新和 `TaskConflictException`，不再引入乐观锁协议。SQLite 按任务 ID 保存完整状态；内存存储恢复为原先的引用存储。会话写入串行性由宿主持有的会话锁保障。

新数据库不创建任务 version 列。已有数据库遗留的 version 列保持闲置，不执行删除列或重建表；旧 JSON 中的 version 被忽略，下一次保存不再写入该字段。

Agent SDK 保留旧 `resume(sessionId, supplement)` 兼容方法，CLI 使用指定任务 ID 的接口。其他 SDK 宿主必须自行提供会话独占。

## 验证与构建

先构建 Agent，再构建 CLI：

```bash
mvn -f ../regnexe-agent/pom.xml clean -Plocal-tests -Dgpg.skip=true -Dmaven.javadoc.skip=true install
mvn clean package
```

测试覆盖 `/resume` 预览后持续占锁、忙碌目标不被打开、当前会话重复打开、切换失败保留原锁、切换成功释放原锁、工作线程未退出时延迟释放、跨进程争抢与强制退出、指定任务重新加载及旧数据库兼容。无需在线模型或 Opik。

快照仅安装到本机 Maven 仓库，未发布远程快照。

## 边界与后续

这是一台主机上遵守协议的窗口间独占，不是文件系统沙箱或分布式租约。升级后应关闭旧版窗口，避免旧版本绕过新的打开会话规则。

不同会话共用工作目录或外部资源仍可能冲突。本阶段没有工具操作台账、UNKNOWN 状态、外部幂等键、远端结果核验或资源级锁。外部操作已成功但本地尚未保存的崩溃窗口仍存在，留待工具副作用专项处理。
