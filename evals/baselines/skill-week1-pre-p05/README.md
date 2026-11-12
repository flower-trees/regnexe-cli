# 统一内容基线：执行链改造前

基线 ID：`2dd3b3dcc17d3df024255cf138ef425f43669ea8dd20b4ef4d1e6c0ac0d0bf22`。这是 P05 前的内容清单，不是业务测评结果。实际版本、工作区状态和每项文件摘要见 [manifest.json](manifest.json)。未提交文件也按内容记录，不把 Git HEAD 当作它们已提交的证明。

```bash
PYTHONDONTWRITEBYTECODE=1 evals/.venv/bin/python evals/skill_baseline.py check \
  --manifest evals/baselines/skill-week1-pre-p05/manifest.json
```

退出码 0 表示内容一致，1 表示存在变化，2 表示缺失输入或清单异常。报告按 cli_sources、agent_sources、artifact、skills、config、evaluation、runner_python、business_python 列出变化。Git 提交/分支/dirty 作为来源信息单独比较；仅文档提交不改变内容 ID。新增/删除源文件、配置、Skill 或依赖均会改变对应内容。

采集新版本使用 `capture --manifest <新的路径>`，禁止覆盖旧清单。旧清单检查失败时先解释差异、重建受影响产物并补跑相关检查，再采集新版本；不能只生成新清单消除失败。

范围：哈希实际 CLI JAR 和两个内嵌依赖，记录源码、Skill 材料/辅助 Python、配置、夹具与评测代码，以及两个 Python 环境的版本与安装包。缓存被排除；不读取 `.env` 或存储密码。不证明 JAR 由当前源码构建；P00 构建/源码对应证据需独立引用。

当前配置是 `evals/skill-week1-inventory.yml`，scope 为 loading-only，不能用作正式模型测评的配置证明。`--config` 和 `--scope model-evaluation` 可记录后续独立配置，但 scope 只是声明，不能代替 P06 的预算验证或实际请求核对。当前未接入 runner 自动拦截，需要显式执行本检查。

历史 P00/P01、P02、P03 的文件继续保留为阶段证据。数据库身份/schema/夹具初始状态需在数据库运行前另检，本脚本不联网；模型实际请求、工具启用状态与最终效果也不由静态哈希证明。后续实验需引用 baseline_id 与相关 preflight 证据。
