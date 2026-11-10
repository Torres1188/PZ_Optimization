#!/usr/bin/env python3
"""Verify all definitions after our early agent and the official ZB agent, without PZ main."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mod-jar", required=True, type=Path)
    parser.add_argument("--game-jar", required=True, type=Path)
    parser.add_argument("--zombiebuddy-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--aot", action="store_true", help="Also record/use an isolated cache using the production launcher plan")
    args = parser.parse_args()
    mod, game, buddy = (path.resolve(strict=True) for path in (args.mod_jar, args.game_jar, args.zombiebuddy_jar))
    output = args.output.resolve()
    if output.exists() or not output.is_relative_to(ROOT / "build"):
        raise ValueError("output must be a new directory under this checkout's build/")
    output.mkdir(parents=True)
    classes = output / "tools"
    classes.mkdir()
    cp = os.pathsep.join(map(str, [game, mod, buddy]))
    env = {key: value for key, value in os.environ.items() if key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"} and not key.startswith("FJL_")}
    commands = []

    def run(command, log, timeout=120):
        commands.append(command)
        with (output / log).open("w", encoding="utf-8") as file:
            result = subprocess.run(command, env=env, cwd=output, stdout=file, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            print((output / log).read_text(errors="replace")[-15000:])
            raise RuntimeError(f"{log}: exit {result.returncode}")

    sources = sorted((ROOT / "scripts/port-validation/zombiebuddy-fixtures").rglob("*.java"))
    sources.append(ROOT / "tests/pzopt/ZombieBuddyLifecycleFixture.java")
    run([shutil.which("javac"), "--release", "25", "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), *map(str, sources)], "compile.log")
    tools = output / "fixture-tools.jar"
    with zipfile.ZipFile(tools, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())
    def launch(early, label, fixture_args):
        command = [shutil.which("java"), "-Xverify:all", "-Xmx1536m", "-XX:ActiveProcessorCount=2"]
        if early is True:
            command.append("-javaagent:" + str(mod))
        command.append("-javaagent:" + str(buddy) + "=policy=deny-new,frontend=console,config_dir=" + str(output / label / "zb-config"))
        if early == "late":
            command.append("-javaagent:" + str(mod))
        command.extend([
                        "-Xlog:class+init=info:file=" + label + "-init.log", "-Djava.awt.headless=true",
                        "-Duser.home=" + str(output / label / "home"), "-Ddeployment.user.cachedir=" + str(output / label / "cache"),
                        "-cp", os.pathsep.join(map(str, [game, mod, tools, buddy])),
                        "pzopt.zombiebuddy.FullPayloadFixture", *fixture_args])
        run(command, label + ".log")

    result_path = output / "result.json"
    fixture_cp = os.pathsep.join(map(str, [game, mod, tools, buddy]))
    run([shutil.which("java"), "-cp", fixture_cp, "pzopt.zombiebuddy.StartupTransformTest",
         str(game), str(output / "native-main-screen-with-hooks.class")], "startup-hooks.log")
    run([shutil.which("java"), "-cp", fixture_cp, "pzopt.zombiebuddy.HelperArchiveBarrierFixture",
         str(mod), str(output / "helper-archive-barrier.json")], "helper-archive-barrier.log")
    run([shutil.which("java"), "-cp", fixture_cp, "pzopt.ZombieBuddyLifecycleFixture",
         str(output / "legacy-guard-target"), str(tools)], "legacy-guard.log")
    run([shutil.which("java"), "-cp", fixture_cp, "pzopt.LoaderConflictPolicyFixture",
         str(buddy), str(output / "loader-conflict-policy.txt")], "loader-conflict-policy.log")
    launch(False, "baseline", ["--baseline", str(output / "baseline.json")])
    launch("late", "order-refusal", ["--expect-order-refusal", str(output / "order-refusal.json")])
    launch(True, "verify", [str(mod), str(game), str(result_path)])
    report = json.loads(result_path.read_text())
    def initialized(label):
        names = set()
        for line in (output / (label + "-init.log")).read_text(errors="replace").splitlines():
            match = re.search(r"Initializing '([^']+)'", line)
            if match and (match.group(1).startswith(("zombie/", "org/lwjglx/", "fmod/", "se/krka/"))
                          or match.group(1).startswith("pzopt/") and not match.group(1).startswith("pzopt/zombiebuddy/")):
                # Hidden lambda suffixes are per-JVM identities, not different classes/initializers.
                names.add(re.sub(r"\+0x[0-9a-fA-F]+$", "+<hidden-id>", match.group(1)))
        return names
    baseline_initializations = initialized("baseline")
    initializations = initialized("verify")
    extra_initializations = sorted(initializations - baseline_initializations)
    verify_log = (output / "verify.log").read_text(errors="replace")
    loader_errors = [line for line in verify_log.splitlines() if "ERROR" in line or "Failed to" in line or "IGNORED" in line]
    report.update({"passed": not report["errors"] and not extra_initializations and not loader_errors,
                   "officialAgentBaselineInitializations": sorted(baseline_initializations),
                   "gameOrOptimizationInitializations": sorted(initializations),
                   "additionalGameOrOptimizationInitializations": extra_initializations, "loaderErrors": loader_errors,
                   "nativeStartupHooks": "PASS exactly 3 methods; other 22 code arrays unchanged",
                   "helperArchiveBarrier": json.loads((output / "helper-archive-barrier.json").read_text()),
                   "legacyLifecycleAndConflictScanner": "PASS no legacy target writes; only exact own JAR ignored",
                   "officialAgentFirstOrder": "PASS refused before payload registration",
                   "reviewedLoaderConflictPolicy": "PASS exact ordinary/renamed loader retains features; altered/foreign and user overrides retained",
                   "commands": commands, "modSha256": hashlib.sha256(mod.read_bytes()).hexdigest(),
                   "zombiebuddySha256": hashlib.sha256(buddy.read_bytes()).hexdigest(),
                   "limitations": ["Actual official Agent.premain with isolated empty configuration; author signing/saved mod selection require full game acceptance", "No game main, native graphics, gameplay or performance measurement"]})
    result_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.aot and report["passed"]:
        # The production plan deliberately scopes files to its supplied game root.
        # Use a private copy of the exact native JAR so neither output nor the
        # child cache-assembly JVM can write into the user's installed game.
        isolated_game = output / "aot-game"
        isolated_game.mkdir()
        copied_game = isolated_game / "projectzomboid.jar"
        shutil.copyfile(game, copied_game)
        if hashlib.sha256(copied_game.read_bytes()).digest() != hashlib.sha256(game.read_bytes()).digest():
            raise AssertionError("isolated native JAR differs")
        (isolated_game / "pzopt/aot-zombiebuddy").mkdir(parents=True)
        aot_base = {"classpath": list(map(str, [copied_game, mod, tools, buddy])), "vmArgs": [
            "-Xverify:all", "-Xmx1536m", "-XX:ActiveProcessorCount=2", "-javaagent:" + str(mod),
            "-javaagent:" + str(buddy) + "=policy=deny-new,frontend=console,config_dir=" + str(output / "aot-zb-config"),
            "-Djava.awt.headless=true", "-Duser.home=" + str(output / "aot-home")]}
        plan_input = output / "aot-plan-input.json"
        plan_input.write_text(json.dumps(aot_base), encoding="utf-8")
        for use in (False, True):
            label = "aot-use" if use else "aot-record"
            plan_path = output / (label + "-plan.json")
            run([shutil.which("java"), "-cp", os.pathsep.join(map(str, [game, mod, tools, buddy])),
                 "pzopt.ManagedAotPlanFixture", str(plan_input), str(isolated_game), str(mod), str(use).lower(), str(plan_path)], label + "-plan.log")
            plan = json.loads(plan_path.read_text())
            if plan["classpath"][0] != str(copied_game) or plan["classpath"][1] != str(mod):
                raise AssertionError("production plan lost native/helper ordering")
            phase_result = output / (label + "-result.json")
            run([shutil.which("java"), *plan["vmArgs"], "-cp", os.pathsep.join(plan["classpath"]),
                 "pzopt.zombiebuddy.FullPayloadFixture", str(mod), str(copied_game), str(phase_result)], label + ".log", timeout=240)
            phase = json.loads(phase_result.read_text())
            if phase["errors"] or phase["checked"] != phase["inventory"]:
                raise AssertionError(label + " lost a payload definition")
            phase_errors = [line for line in (output / (label + ".log")).read_text(errors="replace").splitlines()
                            if "ERROR" in line or "Failed to" in line or "IGNORED" in line]
            if phase_errors:
                raise AssertionError(label + " loader/cache error: " + "\n".join(phase_errors))
        cache = isolated_game / "pzopt/aot-zombiebuddy/pzopt-zombiebuddy.aot"
        aot_log = isolated_game / "pzopt/aot-zombiebuddy/aot.log"
        if not cache.is_file() or "Using AOT-linked classes: true" not in aot_log.read_text(errors="replace"):
            raise AssertionError("production planned cache was not actually reused")
        report["productionPlannedAot"] = {"recordAndUse": "PASS", "usingAotLinkedClasses": True,
            "bytes": cache.stat().st_size, "sha256": hashlib.sha256(cache.read_bytes()).hexdigest(),
            "defaultNoIndy": True, "gameCopySha256": hashlib.sha256(copied_game.read_bytes()).hexdigest(),
            "cachePath": str(cache), "scope": "Eligible JVM/helper classes; no claim that transformed native targets are archived"}
    result_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("passed", "checked", "inventory", "signedNativeClasses", "additionalGameOrOptimizationInitializations", "loaderErrors")}, indent=2))
    if not report["passed"]:
        raise RuntimeError("unexpected game initialization or official-loader patch error")


if __name__ == "__main__":
    main()
