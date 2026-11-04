# regnexe-cli eval harness

Feeds `dataset/cases.yaml` into `rex`, scores each run (rule-based or LLM-judge), and logs
everything as an Opik Experiment so iterations can be compared in the UI.

## Setup (one-time)

```bash
cd evals
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Prerequisites (every run)

- `llm-router` running on `:4000` (`salt-tools/llm-router/start.sh`)
- Local Opik running on `:28173` (`~/Documents/develop/my/opik/opik.sh`; moved off the old
  default 5173, which collided with other local dev servers — see `opik.sh`'s `NGINX_PORT`)
- `rex` on PATH (reinstall after any regnexe-cli/regnexe-agent change: from the repo root,
  `INSTALL_DIR="$HOME/.local/opt/regnexe" BIN_DIR="$HOME/.local/bin" bash install.sh`)
- `LITELLM_MASTER_KEY` exported (same key rex and the LLM-judge both use)

```bash
source .venv/bin/activate
export OPIK_URL_OVERRIDE="http://localhost:28173/api/"
export OPIK_WORKSPACE="default"
export OPIK_TRACK_DISABLE=true   # skip the SDK's own anonymous usage ping; this network is slow to foreign hosts
export http_proxy= https_proxy=  # if your shell has a proxy set, localhost traffic must bypass it
```

## Running

```bash
python runner.py --tag baseline-v1              # full 27-case suite
python runner.py --tag iter2 --only tool_use,file_edit   # just some categories
python runner.py --tag debug --case-id edit-001-create-file --keep-workdirs  # one case, inspect scratch dir after
```

Each case runs in its own throwaway temp dir (seeded with `fixture_files`), via
`rex --session eval-<tag>-<case_id>-<rand>`, with `REX_CONFIG` pointed at `rex-eval-config.yml`
(bash/write/edit confirmation prompts off — there's no human to answer them non-interactively;
see that file's header comment for why this is safe to do only here, never in `~/.rex/config.yml`).

The runner prints a per-category + overall score summary and an Opik Experiment URL. Open it to
compare against previous tags.

## Why rex_client.py reads the Opik trace instead of parsing CLI stdout

`rex` has no non-interactive single-shot flag and no success/failure exit code (see
`CliMain.java`) — the real interface is the piped-stdin REPL. Its stdout also has the same status
chrome you see interactively (`⟳ thinking`, `tokens 123 -> 456 · ...`). Rather than scrape that,
`rex_client.py` correlates the run to its Opik trace via `metadata.session_name` (added to
`OpikEventListener` for exactly this) and reads the structured `output.value` field — the same
"Status: FINISHED | ... \n\n SUCCESS | ..." string the agent itself produces. Raw stdout is kept
only as a fallback if the trace never shows up in time.

## Adding cases

Edit `dataset/cases.yaml`. See its header comment for the schema. Use `check_type: rule` for
anything with one objectively correct answer (exact file content, a safety refusal, a format
constraint); use `check_type: llm_judge` with a `rubric` for anything needing interpretive
judgment. Add a new rule `kind` to `RULES` in `scorer.py` if an existing one doesn't fit.

## The optimization loop

1. Run a baseline tag, read the per-category breakdown and the "cases below 1.0" list.
2. For a real failure (not a harness bug — check the scratch dir / trace first), look at
   `regnexe-agent`'s `TaskPlanner.java` / `CapabilityExecutor.java` / `Reflector.java` (the
   Plan/Execute/Reflect prompts) or `~/.rex/config.yml` (model tier per role, `max_rounds`,
   `context_window_size`) for what to change.
3. Rebuild + reinstall rex, re-run the same categories under a new tag, compare against the
   previous tag's experiment in the Opik UI.
4. Repeat. Keep tags meaningful (`baseline-v1`, `iter2-tighter-reflect-prompt`, ...) since that's
   the only label distinguishing experiments in the UI.
