"""Verify donor definitions without game initialization, using a validation-only agent.

The signed game JAR stays first. This does not replace the separate test with
the real ZombieBuddy agent, its Advice, and its other transformers.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--game-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    payload, game = args.payload.resolve(strict=True), args.game_jar.resolve(strict=True)
    output = args.output.resolve()
    if not output.is_relative_to(repo / "build") or output.exists():
        raise ValueError("Output must be a new directory under this donor checkout's build/")
    output.mkdir(parents=True)
    classes = output / "tools"
    classes.mkdir()
    java = shutil.which("java")
    javac = shutil.which("javac")
    jar = shutil.which("jar")
    if not all((java, javac, jar)):
        raise RuntimeError("JDK 25 java, javac and jar must be on PATH")
    env = {key: value for key, value in os.environ.items()
           if key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}
           and not key.startswith("FJL_")}
    source = Path(__file__).with_name("PayloadJvmVerification.java")
    commands = []

    def run(command, name, timeout=120):
        commands.append(command)
        with (output / name).open("w", encoding="utf-8") as log:
            result = subprocess.run(command, env=env, cwd=output, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            print((output / name).read_text(errors="replace")[-18000:])
            raise RuntimeError(f"Command exited {result.returncode}: {name}")

    run([javac, "--release", "25", "-encoding", "UTF-8", "-d", str(classes), str(source)], "compile.log")
    manifest = output / "MANIFEST.MF"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: PayloadJvmVerification\n\n")
    agent = output / "verification-agent.jar"
    run([jar, "--create", "--file", str(agent), "--manifest", str(manifest), "-C", str(classes), "."], "jar.log")
    result = output / "result.json"
    run([java, "-Xverify:all", "-Xmx1024m", "-XX:ActiveProcessorCount=2", "-javaagent:" + str(agent),
         "-Xlog:class+init=info:file=class-init.log", "-Duser.home=" + str(output / "home"),
         "-Ddeployment.user.cachedir=" + str(output / "cache"),
         "-Dpzopt.verify.payload=" + str(payload), "-Dpzopt.verify.gameJar=" + str(game),
         "-cp", os.pathsep.join(map(str, [classes, game, payload])), "PayloadJvmVerification", str(result)], "verify.log")
    report = json.loads(result.read_text())
    init_log = (output / "class-init.log").read_text(errors="replace")
    payload_names = {path.relative_to(payload).as_posix()[:-6] for path in payload.rglob("*.class")}
    game_initializations = []
    for line in init_log.splitlines():
        initialized = re.search(r"Initializing '([^']+)'", line)
        if initialized and (initialized.group(1) in payload_names or initialized.group(1).startswith(
                ("zombie/", "pzopt/", "org/lwjglx/", "fmod/", "se/krka/"))):
            game_initializations.append(line)
    report["gameOrModInitializationLogLines"] = game_initializations
    report["passed"] = report["passed"] and not game_initializations
    report["commands"] = commands
    report["verificationSourceSha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
    result.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: report[key] for key in ("passed", "classCount", "checked", "signedNativeClasses",
          "transformedNativeClasses", "gameOrModInitializationLogLines")}, indent=2))
    if not report["passed"]:
        raise RuntimeError("Unexpected game/mod initialization recorded")


if __name__ == "__main__":
    main()
