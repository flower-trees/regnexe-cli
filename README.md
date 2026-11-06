# Regnexe CLI

**An AI assistant for your terminal, with tools, reusable skills, and task recovery.**

[English](README.md) | [中文](README_zh.md)

Built on regnexe-agent and j-langchain, `rex` combines an interactive terminal with file editing, shell tools, MCP, Skills, SQLite-backed sessions, and optional Opik tracing.

## Requirements

- Java 17+; Maven is also required when building from source.
- A model API, credentials, network access, and quota. The example below uses the official DeepSeek API.
- The Bash installer targets macOS / Linux. Other environments can run the JAR directly.

## Build and install

```bash
git clone https://github.com/flower-trees/regnexe-cli.git
cd regnexe-cli
mvn clean verify
```

Install into your user directory without sudo:

```bash
INSTALL_DIR="$HOME/.local/opt/regnexe" BIN_DIR="$HOME/.local/bin" bash install.sh
export PATH="$HOME/.local/bin:$PATH"
rex --version
rex --help
```

Persist the PATH setting in your shell configuration. The installer defaults to `/opt/regnexe` and `/usr/local/bin`; the overrides above use your own directories instead.

You can also run without installing the wrapper:

```bash
java -jar target/regnexe-cli.jar --session demo
```

In an extracted release archive, use `regnexe-cli.jar` instead. Its installer locates the JAR beside itself. The initial release is being prepared; use the files actually available in GitHub Releases.

## Configure a model

Create `~/.rex/config.yml`:

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

Enter a task such as “List the main modules in this project without changing files.” The model plans the task and calls tools as needed.

| Environment variable | Purpose |
|---|---|
| `REX_API_KEY` | Override the configured vendor’s API key |
| `REX_MODEL` | Override the model name without changing the vendor |
| `JAVA_OPTS` | Pass JVM options through the installed wrapper |

For per-role models, custom endpoints, workspaces, and Opik, see [configuration guide](docs/guide/configuration.md).

## Interactive commands

| Command | Purpose |
|---|---|
| `/help` | Show all interactive commands |
| `/resume [name]` | Open a named session, or use Up/Down and Enter to select one; unfinished tasks continue on your next message |
| `/history [name]` | Show conversation history |
| `/clear` | Clear conversation history for the current session |
| `/dirs` / `/add-dir <path>` | List or add workspace directories |
| `/skills` / `/<skill name> [args]` | List or directly invoke a skill |
| `/plugin list` | List plugins and enabled state |
| `/mcp list` | List MCP servers and tools |
| `/exit` | Exit |

The picker lists sessions saved for the current workspace directory. Directory aliases resolve to the same location; child directories remain separate workspaces. Use `/resume <name>` to open a session from another directory directly.

Interactive terminals use a full-screen history viewport: mouse wheel, PgUp/PgDn, and the application scrollbar scroll output while input stays fixed between two rules. An activity line shows running/approval/ready state; `model · session · workspace` stays on one line below the input. Set `ui.fullscreen: false` for native terminal scrollback compatibility; configure colors with `ui.color: auto|always|never`.

The starting directory is the default workspace; extend it through configuration or `/add-dir`. File writes and edits request confirmation; shell confirmation is also enabled by default.

## Sessions and task recovery

Conversation and task state live in `~/.rex/rex.db`, terminal history in `~/.rex/history`, and model configuration in `~/.rex/config.yml`.

`/resume` selects the session’s most recently updated non-FINISHED task. Recovery may retry tools and does not guarantee exactly-once external side effects. Direct Skill invocation does not create a resumable task ledger.

Before upgrading data from development builds, exit all rex processes and back up the entire `~/.rex` directory. Only unambiguous legacy hash keys are migrated automatically; ambiguous or conflicting records are preserved. See [0.1.0 release notes](docs/release/v0.1.0-en.md).

## Development and validation

```bash
mvn clean verify
bash scripts/smoke-test.sh --skip-build
bash scripts/package-release.sh --skip-build
```

Database regression tests do not call real models; the smoke test uses a temporary installation. The release script creates a local archive and SHA-256 checksums without uploading. For terminal, model, MCP, and Opik acceptance, see [release validation guide](docs/guide/release-validation.md).

## License and feedback

[Apache License 2.0](LICENSE) · [Issues](https://github.com/flower-trees/regnexe-cli/issues) · [regnexe-agent](https://github.com/flower-trees/regnexe-agent) · [j-langchain](https://github.com/flower-trees/j-langchain)
