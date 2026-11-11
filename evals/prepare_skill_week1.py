#!/usr/bin/env python3
"""P00/P01 only: baseline + packaged Java Skill registration, without running an Agent.

Build the CLI first, then run with evals/.venv/bin/python. Runtime files stay under
target/skill-eval-week1; neither global settings nor the source Skill tree is changed.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

import yaml

ROOT = Path(__file__).resolve().parent.parent
TARGET = "robot-description-style"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(repo), *args], text=True).strip()


def repository(repo: Path) -> dict:
    return {"path": str(repo), "commit": git(repo, "rev-parse", "HEAD"),
            "branch": git(repo, "branch", "--show-current"),
            "status": git(repo, "status", "--porcelain").splitlines()}


def inspect_files(skills: Path) -> list[dict]:
    result = []
    for file in sorted(skills.glob("*/SKILL.md")):
        text = file.read_text()
        row = {"directory_id": file.parent.name, "path": str(file),
               "file_sha256": digest(file.read_bytes()), "file_chars": len(text)}
        parts = re.split(r"(?m)^---\s*$", text, maxsplit=2)
        if not text.startswith("---") or len(parts) < 3:
            row["metadata_status"] = "missing_frontmatter"
        else:
            try:
                front = yaml.safe_load(parts[1])
                row["metadata_status"] = ("valid" if isinstance(front, dict)
                    and front.get("name") and front.get("description") else "missing_name_or_description")
            except yaml.YAMLError as error:
                row["metadata_status"] = "yaml_error"
                row["metadata_error"] = str(error)
        result.append(row)
    return result


def dependency(data: bytes, artifact: str, agent_source: Path | None) -> dict:
    with zipfile.ZipFile(io.BytesIO(data)) as nested:
        entry = next(n for n in nested.namelist() if n.endswith(f"/{artifact}/pom.properties"))
        properties = dict(line.split("=", 1) for line in nested.read(entry).decode().splitlines()
                          if "=" in line and not line.startswith("#"))
    cache = Path.home() / ".m2/repository" / properties["groupId"].replace(".", "/") / artifact / properties["version"]
    jar = cache / f"{artifact}-{properties['version']}.jar"
    source = cache / f"{artifact}-{properties['version']}-sources.jar"
    result = {"coordinates": properties, "embedded_sha256": digest(data), "cached_path": str(jar),
              "cached_sha256": digest(jar.read_bytes()) if jar.exists() else None}
    result["embedded_matches_cache"] = result["embedded_sha256"] == result["cached_sha256"]
    if agent_source is not None and source.exists():
        with zipfile.ZipFile(source) as archive:
            archived = {n: archive.read(n) for n in archive.namelist() if n.endswith(".java")}
        source_root = agent_source / "src/main/java"
        files = {str(p.relative_to(source_root)): p.read_bytes() for p in source_root.rglob("*.java")}
        result["sources_jar_sha256"] = digest(source.read_bytes())
        result["source_files"] = len(files)
        result["source_mismatches"] = [n for n in sorted(set(files) | set(archived)) if files.get(n) != archived.get(n)]
    return result


def command(args: list[str], *, env: dict, cwd: Path, log: Path) -> None:
    with log.open("w") as output:
        result = subprocess.run(args, cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT,
                                text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"Command exited {result.returncode}; inspect {log}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skills-root", type=Path, default=ROOT.parent / "salt-robot-skills")
    parser.add_argument("--jar", type=Path, default=ROOT / "target/regnexe-cli.jar")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    skills_repo = args.skills_root.resolve()
    source = skills_repo / ".claude/skills"
    files = inspect_files(source)
    if not files or not args.jar.is_file():
        raise RuntimeError("Need the source Skill directory and a built CLI JAR")
    output = (args.output or ROOT / "target/skill-eval-week1" /
              datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")).resolve()
    if output.exists() and any(output.iterdir()):
        raise RuntimeError("Output must be new or empty; never reuse an earlier probe home")
    output.mkdir(parents=True, exist_ok=True)
    home, workspace = output / "home", output / "workspace"
    for directory in [home / ".rex", workspace / ".rex", output / "fixtures", output / "results"]:
        directory.mkdir(parents=True, exist_ok=True)
    for directory in [home / ".rex", workspace / ".rex"]:
        (directory / "enabled.yml").write_text("{}\n")
    config = output / "config.yml"
    config.write_bytes((ROOT / "evals/skill-week1-inventory.yml").read_bytes())
    # No credentials, proxies, model overrides, or business-service variables enter Java.
    env = {key: value for key, value in os.environ.items()
           if key in {"PATH", "JAVA_HOME", "LANG", "LC_ALL", "TMPDIR", "SYSTEMROOT"}}
    env.update(REX_CONFIG=str(config), SKILL_EVAL_SOURCE_ROOT=str(skills_repo))
    baseline = {"created_at": datetime.now(timezone.utc).isoformat(),
                "repositories": {"cli": repository(ROOT), "agent": repository(ROOT.parent / "regnexe-agent"),
                                 "skills": repository(skills_repo)},
                "jar": {"path": str(args.jar.resolve()), "sha256": digest(args.jar.read_bytes())},
                "config_sha256": digest(config.read_bytes()),
                "probe_source_sha256": digest((ROOT / "evals/java/SkillInventoryProbe.java").read_bytes()),
                "preparer_source_sha256": digest(Path(__file__).read_bytes()),
                "skill_files": files, "isolated_home": str(home), "workspace": str(workspace),
                "environment_keys": sorted(env), "dependencies": {}}
    runtime = output / "runtime"
    with zipfile.ZipFile(args.jar) as jar:
        for artifact in ["regnexe-agent", "j-langchain"]:
            entry = next(n for n in jar.namelist() if n.startswith(f"BOOT-INF/lib/{artifact}-") and n.endswith(".jar"))
            baseline["dependencies"][artifact] = dependency(jar.read(entry), artifact,
                ROOT.parent / "regnexe-agent" if artifact == "regnexe-agent" else None)
        for entry in jar.namelist():
            if not entry.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")) or entry.endswith("/"):
                continue
            relative = Path(entry).relative_to("BOOT-INF")
            if ".." in relative.parts:
                raise RuntimeError("Unsafe path in runtime JAR")
            destination = runtime / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(jar.read(entry))
    for name, info in baseline["dependencies"].items():
        if not info["embedded_matches_cache"] or info.get("source_mismatches"):
            (output / "baseline.json").write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + "\n")
            raise RuntimeError(f"Dependency baseline mismatch: {name}; stopped before loading")
    command(["java", "-version"], env=env, cwd=workspace, log=output / "java-version.log")
    classpath = os.pathsep.join([str(runtime / "classes"), str(runtime / "lib/*")])
    probe_classes = output / "probe-classes"
    probe_classes.mkdir()
    command(["javac", "--release", "17", "-encoding", "UTF-8", "-cp", classpath, "-d", str(probe_classes),
             str(ROOT / "evals/java/SkillInventoryProbe.java")], env=env, cwd=workspace, log=output / "javac.log")
    command(["java", "-Duser.home=" + str(home), "-cp", str(probe_classes) + os.pathsep + classpath,
             "SkillInventoryProbe", str(workspace), str(output / "inventory.json")],
            env=env, cwd=workspace, log=output / "loader.log")
    inventory = json.loads((output / "inventory.json").read_text())
    by_id = {row["capability_id"]: row for row in inventory["inventory"]}
    by_file = {row["directory_id"]: row for row in files}
    target_file = source / TARGET / "SKILL.md"
    expected_body = re.split(r"(?m)^---\s*$", target_file.read_text(), maxsplit=2)[2].strip()
    target = by_id[TARGET]
    assert target["body_sha256"] == digest(expected_body.encode()), "Target body differs from the source file"
    assert target["name"] == TARGET and target["description"], "Target metadata is not usable"
    assert inventory["skill_directories"] == [str(source)], "Unexpected user or project Skill sources"
    assert all(inventory["enabled_control"].values()), "Enable/disable control did not pass"
    assert inspect_files(source) == files, "Source skills changed during the probe"
    assert baseline["repositories"]["skills"] == repository(skills_repo), "Source repository changed during the probe"
    baseline["java_runtime"] = (output / "java-version.log").read_text()
    (output / "baseline.json").write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + "\n")
    fields = ["directory_id", "metadata_status", "registered", "enabled", "candidate", "name",
              "description_present", "body_chars", "body_sha256", "file_sha256"]
    with (output / "inventory.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fields); writer.writeheader()
        for row in files:
            cap = by_id.get(row["directory_id"], {})
            writer.writerow({"directory_id": row["directory_id"], "metadata_status": row["metadata_status"],
                "registered": bool(cap), "enabled": cap.get("enabled", False),
                "candidate": row["directory_id"] in inventory["initial_candidate_ids"],
                "name": cap.get("name", ""), "description_present": bool(cap.get("description")),
                "body_chars": cap.get("body_chars", 0), "body_sha256": cap.get("body_sha256", ""),
                "file_sha256": row["file_sha256"]})
    summary = {"output": str(output), "file_count": len(files), "registered_count": len(by_id),
        "enabled_count": sum(row["enabled"] for row in by_id.values()),
        "candidate_count": len(inventory["initial_candidate_ids"]),
        "missing_registration": sorted(set(by_file) - set(by_id)),
        "extra_registration": sorted(set(by_id) - set(by_file)),
        "registration_failures": [x for x in inventory["registration_attempts"] if not x["registered"]],
        "empty_descriptions": sorted(key for key, row in by_id.items() if not row.get("description")),
        "duplicate_short_names": inventory["duplicate_short_names"],
        "metadata_counts": {status: sum(f["metadata_status"] == status for f in files)
                            for status in sorted({f["metadata_status"] for f in files})},
        "target": target, "enabled_control": inventory["enabled_control"],
        "model_calls": 0, "business_tool_calls": 0, "mcp_connections": 0}
    (output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in summary.items() if key not in {"empty_descriptions", "target"}},
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
