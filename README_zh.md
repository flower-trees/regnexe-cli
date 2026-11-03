# Regnexe CLI

**在终端中使用可调用工具、复用技能并恢复任务的 AI 助手。**

[English](README.md) | [中文](README_zh.md)

基于 regnexe-agent 和 j-langchain，提供交互式 `rex` 命令。支持文件读取与编辑、Shell 工具、MCP、Skill、SQLite 会话记录，以及可选的 Opik 链路追踪。

## 运行要求

- Java 17+；从源码构建还需要 Maven。
- 可用的模型服务、API Key、网络和额度。下方示例使用 DeepSeek 官方接口。
- 安装脚本面向 macOS / Linux 的 Bash 环境；其他环境可直接运行 JAR。

## 构建与安装

```bash
git clone https://github.com/flower-trees/regnexe-cli.git
cd regnexe-cli
mvn clean verify
```

不使用 sudo，安装到当前用户目录：

```bash
INSTALL_DIR="$HOME/.local/opt/regnexe" BIN_DIR="$HOME/.local/bin" bash install.sh
export PATH="$HOME/.local/bin:$PATH"
rex --version
rex --help
```

将 PATH 设置加入你的 Shell 配置以便后续终端使用。安装脚本默认目录为 `/opt/regnexe` 和 `/usr/local/bin`，上面的覆盖配置不需要修改这些目录。

也可直接运行，无需安装 wrapper：

```bash
java -jar target/regnexe-cli.jar --session demo
```

发布压缩包解压后，将命令中的路径改为 `regnexe-cli.jar`；包内的 `install.sh` 会自动找到同目录 JAR。首次发布正在准备，以 GitHub Releases 中实际提供的文件为准。

## 配置模型

创建 `~/.rex/config.yml`：

```yaml
model:
  vendor: deepseek
  name: deepseek-v4-flash
  api_key: ${DEEPSEEK_KEY}
```

```bash
export DEEPSEEK_KEY="your-api-key"
cd /path/to/your/project
rex --session demo
```

输入自然语言任务，例如“列出当前项目的主要模块，先不要修改文件”。模型会按任务规划并调用工具。

| 环境变量 | 作用 |
|---|---|
| `REX_API_KEY` | 覆盖当前配置厂商的 API Key |
| `REX_MODEL` | 覆盖模型名，不改变厂商 |
| `JAVA_OPTS` | 通过已安装的 wrapper 传入 JVM 参数 |

多角色模型、自定义接口、工作目录和 Opik 配置见 [配置说明](docs/guide/configuration.md)。

## 常用交互命令

| 命令 | 作用 |
|---|---|
| `/help` | 查看全部交互命令 |
| `/sessions` | 列出已有会话 |
| `/resume <name>` | 切换会话；有未完成任务时，下一条消息作为补充指令继续该任务 |
| `/history [name]` | 查看会话历史 |
| `/clear` | 清空当前会话的对话历史 |
| `/dirs` / `/add-dir <path>` | 查看或增加工作目录 |
| `/skills` / `/<skill name> [args]` | 列出技能或直接调用技能 |
| `/plugin list` | 查看插件和启用状态 |
| `/mcp list` | 查看 MCP 服务和工具 |
| `/exit` | 退出 |

默认工作目录为启动时所在目录，可通过配置或 `/add-dir` 扩展。写文件和编辑文件会请求确认；Shell 工具默认也启用确认。

## 会话与任务恢复

会话历史和任务状态保存在 `~/.rex/rex.db`，输入历史位于 `~/.rex/history`，模型配置位于 `~/.rex/config.yml`。

`/resume` 会选择该会话最近更新的非 FINISHED 任务。恢复可能重试工具，不能保证外部副作用只执行一次。直接调用 Skill 的路径不建立可恢复任务账本。

使用旧开发版本数据升级前，先退出所有 rex 进程并备份整个 `~/.rex` 目录。本版本只自动迁移归属明确的旧哈希会话键；歧义或冲突记录保留原样。详情见 [0.1.0 发布说明](docs/release/v0.1.0.md)。

## 开发与验证

```bash
mvn clean verify
bash scripts/smoke-test.sh --skip-build
bash scripts/package-release.sh --skip-build
```

数据库回归不调用真实模型；冒烟检查使用临时安装目录。发布脚本仅生成本地压缩包与 SHA-256 校验值，不上传。交互终端、真实模型、MCP 和 Opik 验收见 [发布验证说明](docs/guide/release-validation.md)。

## 协议与反馈

[Apache License 2.0](LICENSE) · [Issues](https://github.com/flower-trees/regnexe-cli/issues) · [regnexe-agent](https://github.com/flower-trees/regnexe-agent) · [j-langchain](https://github.com/flower-trees/j-langchain)
