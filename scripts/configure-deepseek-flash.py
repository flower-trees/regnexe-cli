#!/usr/bin/env python3
"""Preview/apply the local rex model block without printing or storing a resolved API key."""
import argparse
import datetime
import os
from pathlib import Path
import re
import tempfile

import yaml


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path.home() / ".rex/config.yml")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not os.environ.get("DEEPSEEK_KEY", "").strip():
        raise SystemExit("DEEPSEEK_KEY is missing or empty; no config changed.")
    raw = args.config.read_text()
    data = yaml.safe_load(raw)
    if not isinstance(data, dict) or not isinstance(data.get("model"), dict):
        raise SystemExit("Expected a YAML mapping with a model block; no config changed.")
    model = dict(data["model"])
    for key in ["planner_api_key", "reflector_api_key"]:
        model.pop(key, None)
    model.update(vendor="deepseek", name="deepseek-flash",
                 chat_url="https://api.deepseek.com/v1/chat/completions",
                 api_key="${DEEPSEEK_KEY}", planner_name="deepseek-flash",
                 reflector_name="deepseek-flash", planner_vendor="deepseek",
                 reflector_vendor="deepseek")
    block = yaml.safe_dump({"model": model}, sort_keys=False, allow_unicode=True)
    updated, count = re.subn(r"(?ms)^model:[^\n]*\n.*?(?=^[A-Za-z_][\w-]*:|\Z)",
                             lambda _: block + "\n", raw, count=1)
    if count != 1:
        raise SystemExit("Could not safely locate model block; no config changed.")
    expected = dict(data, model=model)
    if yaml.safe_load(updated) != expected:
        raise SystemExit("Config preservation check failed; no config changed.")
    print("Config:", args.config)
    print("Execute / Planner / Reflector: deepseek/deepseek-flash")
    print("Endpoint: https://api.deepseek.com/v1/chat/completions")
    print("API key: environment reference ${DEEPSEEK_KEY} (value never printed)")
    if not args.apply:
        print("Preview only. Add --apply to back up and replace the model block.")
        return
    if updated == raw:
        print("Already configured; no write needed.")
        return
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup = args.config.with_name(args.config.name + ".bak." + stamp)
    with backup.open("xb") as output:
        os.chmod(backup, 0o600)
        output.write(raw.encode())
    descriptor, temporary = tempfile.mkstemp(prefix=".rex-config-", dir=args.config.parent)
    try:
        with os.fdopen(descriptor, "w") as output:
            output.write(updated)
        os.replace(temporary, args.config)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print("Updated model block only. Private backup:", backup)


if __name__ == "__main__":
    main()
