#!/usr/bin/env python3
"""Validate or upload prepared test definitions to Opik. Never invokes a model or runs tests."""
import argparse
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

import yaml

ROOT = Path(__file__).resolve().parent
SUITES = {
    "regression": ("hardening-regression-2026-11.yaml", "regnexe-cli-hardening-2026-11-regression-v1"),
    "agent": ("hardening-agent-2026-11.yaml", "regnexe-cli-hardening-2026-11-agent-v1"),
    "interactive": ("hardening-interactive-2026-11.yaml", "regnexe-cli-hardening-2026-11-interactive-v1"),
}


def load_suite(kind):
    filename, _ = SUITES[kind]
    cases = yaml.safe_load((ROOT / "dataset" / filename).read_text())
    ids = set()
    for case in cases:
        case_id = case["id"]
        if case_id in ids:
            raise ValueError("Duplicate case ID: " + case_id)
        ids.add(case_id)
        if kind == "regression":
            source = ROOT.parent / case["source_file"]
            method = case["test_selector"].split("#", 1)[1]
            if not re.search(r"public void " + re.escape(method) + r"\(", source.read_text()):
                raise ValueError("Missing regression test: " + case_id)
        elif kind == "agent":
            if not case.get("turns") or not case.get("tool_evidence"):
                raise ValueError("Agent case needs turns and tool evidence: " + case_id)
            if case["check_type"] != "rule":
                raise ValueError("This suite uses deterministic scoring only: " + case_id)
            import scorer
            def validate_rule(rule):
                if rule["kind"] not in scorer.RULES:
                    raise ValueError("Unknown scoring rule: " + rule["kind"])
                if rule["kind"] == "output_regex":
                    re.compile(rule["pattern"])
                if rule["kind"] == "all_of":
                    if not rule.get("rules"):
                        raise ValueError("all_of needs nonempty rules")
                    for child in rule["rules"]:
                        validate_rule(child)
            validate_rule(case["rule"])
            # Fixture construction is validated offline as well as at execution time.
            import tempfile
            import rex_client
            with tempfile.TemporaryDirectory(prefix="rex-validate-") as temporary:
                rex_client.prepare_case_workspace(Path(temporary), case)
        elif not case.get("steps") or not case.get("expected"):
            raise ValueError("Interactive case needs steps and expectations: " + case_id)
    return cases


def main():
    sys.stdout.reconfigure(line_buffering=True)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upload", action="store_true", help="import definitions, not results")
    parser.add_argument("--suite", choices=SUITES, action="append")
    parser.add_argument("--host", default="http://localhost:28173/api/")
    parser.add_argument("--workspace", default="default")
    args = parser.parse_args()
    suites = {kind: load_suite(kind) for kind in (args.suite or SUITES)}
    for kind, cases in suites.items():
        print(f"Validated {kind}: {len(cases)} cases -> {SUITES[kind][1]}")
    if not args.upload:
        print("Preparation only: no model calls, no test execution, no Opik writes.")
        return
    if urlsplit(args.host).hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise SystemExit("This preparation script only uploads synthetic definitions to local Opik.")
    os.environ["OPIK_TRACK_DISABLE"] = "true"
    os.environ["NO_PROXY"] = ",".join(filter(None, [os.environ.get("NO_PROXY"), "localhost,127.0.0.1,::1"]))
    import opik
    client = opik.Opik(project_name="regnexe-cli", host=args.host, workspace=args.workspace)
    for kind, cases in suites.items():
        name = SUITES[kind][1]
        dataset = client.get_or_create_dataset(
            name=name, description=f"Prepared {kind} cases; definitions only, no execution results.")
        records = [{"case_id": case["id"], **{key: value for key, value in case.items() if key != "id"}}
                   for case in cases]
        existing = {item["case_id"]: item for item in dataset.get_items() if "case_id" in item}
        for record in records:
            prior = existing.get(record["case_id"])
            # SDK stores description as item metadata; get_items() returns data content
            # without that field. Compare all case-content fields, not SDK metadata.
            if prior and any(prior.get(key) != value for key, value in record.items()
                             if key != "description"):
                raise SystemExit("Existing definition changed; use a new dataset version: " + record["case_id"])
        missing = [record for record in records if record["case_id"] not in existing]
        if missing:
            dataset.insert(missing, num_threads=1)
        saved = dataset.get_items()
        actual = {item.get("case_id") for item in saved}
        if not {record["case_id"] for record in records}.issubset(actual):
            raise RuntimeError("Dataset read-back is missing expected items: " + name)
        if len(saved) != len(records):
            raise RuntimeError("Dataset contains unexpected or duplicate records: " + name)
        from opik.url_helpers import get_dataset_url_by_id
        info = client.rest_client.datasets.get_dataset_by_id(id=dataset.id)
        print(f"Imported {kind}: {len(records)} verified cases, {len(missing)} newly inserted")
        print(f"Dataset: {name}; id={dataset.id}")
        print("URL:", get_dataset_url_by_id(args.host, args.workspace, info.project_id, dataset.id))
    print("Definitions uploaded. No experiment or score was created; no model was invoked.")


if __name__ == "__main__":
    main()
