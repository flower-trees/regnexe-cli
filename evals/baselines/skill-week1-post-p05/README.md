# P05 共享执行链后的内容基线

内容 ID：`1f276d92a0fc79f0680397baa5b9c01ebb00015ccba9b5c82555f07445200697`。

[统一清单](manifest.json)与[验证证据](verification.json)记录本次未提交源码和实际产物；Git HEAD 不是全部改动的提交版本。旧清单保留，检查报告仅 agent_sources/artifact 变化。两个模式后续均应使用这一改造后的链，禁止跨旧/新版本比较。

```bash
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/skill_baseline.py check \
  --manifest evals/baselines/skill-week1-post-p05/manifest.json
```

Agent `mvn -o -Plocal-tests test`：52 项通过，其中 RequiredSkillExecutionTest 8 项；CLI `mvn -o verify`：85 项通过。FakeModel 在进程内返回固定响应，网络 actuator 会直接断言失败；不消耗真实模型 Token。首轮测试暴露了旧 Reflector 将纯指令 Skill 等同于工具调用的问题，修复后保留真实工具/未解析能力的零调用保护。

新 JAR 的隔离加载检查：145 文件/注册/启用/候选，无加载失败；元数据缺陷未修复。内嵌 Agent 与 Maven 缓存一致，sources JAR 的 68 份 Java 文件与仓库一致。原始证据目录：`target/skill-eval-week1/p05-loading-20261112/`；运行日志为 `/tmp/rex-p05-local-tests.log`、`/tmp/rex-p05-cli-verify.log`。

本阶段不调用真实模型、数据库或 MCP，没有实测 Opik/真实终端斜杠命令。P04 参考文风待人工确认，P06 预算/失败分类待实现。清单仍是 loading-only 配置；不能据此宣称正式模型测评准备全部完成。

Review 对应 Agent 实现提交：`9982d8c`（dev-0.1.4）。清单保留采集时未提交状态；提交后仅来源信息变化，内容基线无需重新采集。
