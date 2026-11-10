#!/usr/bin/env python3
"""Compile the complete donor payload for the exact reviewed B42.22.2 JAR.

Build-only: creates a new directory under build/, never installs or starts the
game. --validate adds static parity/linkage, focused CPU fixtures and a separate
JVM definition check. The real ZombieBuddy composition test remains separate.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
GAME_SHA = "3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287"
PROTECTED = {"zombie/gameStates/MainScreenState", "zombie/core/Core",
             "zombie/core/IntegrityReport", "zombie/network/GameServer"}
ASM_MODULES = ("asm", "asm-tree", "asm-analysis", "asm-util", "asm-commons")


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def quote_arg(value):
    value = str(value).replace("\\", "/")
    if any(character in value for character in "\r\n\0"):
        raise ValueError("Control characters are not allowed in javac argument paths")
    return '"' + value.replace('"', '\\"') + '"'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-jar", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--validate", action="store_true")
    parser.add_argument("--asm-dir", type=Path, help="Directory containing the five ASM 9.8 JARs; searched recursively")
    parser.add_argument("--baseline-payload", type=Path, help="Original donor classes for Config/default and NPC-body comparison")
    args = parser.parse_args()
    game = args.game_jar.resolve(strict=True)
    output = args.output.resolve()
    if sha(game) != GAME_SHA:
        raise ValueError("Game JAR is not the exact reviewed B42.22.2 artifact")
    if output.exists() or not output.is_relative_to(ROOT / "build"):
        raise ValueError("Select a new output directory under this donor checkout's build/")
    if args.validate and (not args.asm_dir or not args.baseline_payload):
        raise ValueError("--validate requires --asm-dir and --baseline-payload")
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise RuntimeError("JDK 25 or newer javac/java must be on PATH")
    sources = sorted(path for name in ("overrides", "shims", "pzopt", "vendor")
                     for path in (ROOT / "src" / name).rglob("*.java"))
    for name in ("overrides", "shims", "pzopt", "vendor"):
        if not any((ROOT / "src" / name).rglob("*.java")):
            raise ValueError("Missing source root: src/" + name)
    before = [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)} for path in sources]
    env = {key: value for key, value in os.environ.items()
           if key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}
           and not key.startswith("FJL_")}
    output.mkdir(parents=True)
    classes, stock = output / "classes", output / "stock"
    classes.mkdir()
    stock.mkdir()
    commands = []
    report = {"passed": False, "releaseQualified": False, "zombieBuddyCompositionTest": False,
              "gameJarSha256": GAME_SHA, "sourceFiles": len(sources), "sources": before,
              "commands": commands, "validations": {}}
    receipt = output / "compile-inventory.json"

    def run(command, log_name, timeout=180):
        command = list(map(str, command))
        commands.append(command)
        with (output / log_name).open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        if result.returncode:
            raise RuntimeError(f"{log_name}: exit {result.returncode}\n"
                               + (output / log_name).read_text(errors="replace")[-18000:])

    try:
        arguments = ["--release", "25", "-encoding", "UTF-8", "-nowarn", "-Xlint:-options", "-parameters", "-g",
                     "-Xmaxerrs", "1000", "-cp", quote_arg(game), "-d", quote_arg(classes)]
        arguments += [quote_arg(path) for path in sources]
        argument_file = output / "javac.args"
        argument_file.write_text("\n".join(arguments) + "\n", encoding="utf-8")
        run([javac, "-J-Xmx1536m", "-J-XX:ActiveProcessorCount=2", "@" + str(argument_file)], "javac.log")
        run([javac, "--version"], "javac-version.log")
        if before != [{"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)} for path in sources]:
            raise RuntimeError("Source files changed during compilation; use a new output after freezing them")
        class_rows = [{"path": path.relative_to(classes).as_posix(), "sha256": sha(path)}
                      for path in sorted(classes.rglob("*.class"))]
        for row in class_rows:
            header = (classes / row["path"]).read_bytes()[:8]
            if struct.unpack(">IHH", header) != (0xCAFEBABE, 0, 69):
                raise RuntimeError("Class is not ordinary Java 25 bytecode: " + row["path"])
        owners = {path.relative_to(ROOT / "src/overrides").as_posix()[:-5]
                  for path in (ROOT / "src/overrides").rglob("*.java")}
        excluded = []
        with zipfile.ZipFile(game) as jar:
            names = set(jar.namelist())
            new_native = [row["path"] for row in class_rows if row["path"] not in names
                          and not row["path"].startswith("pzopt/")]
            if new_native:
                raise RuntimeError("Unsigned helpers remain in native packages: " + ", ".join(new_native))
            stock_count = 0
            for name in sorted(names):
                if not name.endswith(".class") or name[:-6].split("$")[0] not in owners:
                    continue
                data = jar.read(name)
                if name[:-6].split("$")[0] in PROTECTED:
                    excluded.append({"path": name, "sha256": hashlib.sha256(data).hexdigest(),
                                     "reason": "Native integrity/version reporting; excluded by the ZombieBuddy profile"})
                    continue
                target = stock / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                stock_count += 1
        report.update(classFiles=len(class_rows), classes=class_rows, newNativePackageClasses=[],
                      stockClassCount=stock_count, protectedStockExclusions=excluded,
                      sourceInventorySha256=hashlib.sha256("".join(row["path"] + " " + row["sha256"] + "\n"
                              for row in before).encode()).hexdigest(),
                      classInventorySha256=hashlib.sha256("".join(row["path"] + " " + row["sha256"] + "\n"
                              for row in class_rows).encode()).hexdigest(),
                      parityAllowFileSha256=sha(ROOT / "scripts/bytecode-audit.allow"))
        report["parityCommand"] = [sys.executable, str(ROOT / "scripts/bytecode-audit.py"),
                                   "--classes", str(classes), "--stock", str(stock)]
        report["jvmCommand"] = [sys.executable, str(Path(__file__).with_name("verify-jvm.py")),
                                "--payload", str(classes), "--game-jar", str(game), "--output", str(output / "jvm")]
        if args.validate:
            baseline = args.baseline_payload.resolve(strict=True)
            jars = []
            for module in ASM_MODULES:
                matches = list(args.asm_dir.resolve(strict=True).rglob(module + "-9.8.jar"))
                if len(matches) != 1:
                    raise ValueError("Expected exactly one ASM 9.8 module: " + module)
                jars.append(matches[0])
            report["asm"] = [{"path": str(path), "sha256": sha(path)} for path in jars]
            tools = output / "tools"
            tools.mkdir()
            asm_cp = os.pathsep.join(map(str, jars))
            tool_cp = os.pathsep.join([str(tools), asm_cp])
            tool_sources = [Path(__file__).with_name(name) for name in
                            ("PayloadModel.java", "AuditPortPayload.java", "AuditNpcGuardTest.java")]
            run([javac, "--release", "25", "-cp", asm_cp, "-d", tools, *tool_sources], "audit-compile.log")
            run([java, "-Xmx768m", "-XX:ActiveProcessorCount=2", "-cp", tool_cp, "AuditNpcGuardTest"], "npc-guard.log")
            run([java, "-Xverify:all", "-Xmx1536m", "-XX:ActiveProcessorCount=2", "-cp", tool_cp,
                 "AuditPortPayload", classes, game, output / "strict-audit.json", "--baseline-payload=" + str(baseline)], "strict-audit.log")
            run(report["parityCommand"], "bytecode-audit-shipped.log")
            run(report["jvmCommand"], "jvm-verification.log")
            test_classes = output / "focused-tests"
            test_classes.mkdir()
            test_names = ("DrawScratchIsolationTest", "SignedHelperCompatibilityFixture", "ManagedAotCacheTest")
            test_sources = [ROOT / "tests/pzopt" / (name + ".java") for name in test_names]
            native_first = os.pathsep.join(map(str, [game, classes]))
            run([javac, "--release", "25", "-cp", native_first, "-d", test_classes, *test_sources], "focused-compile.log")
            temporary = output / "tmp"
            temporary.mkdir()
            for name in test_names:
                run([java, "-Xverify:all", "-Xmx768m", "-XX:ActiveProcessorCount=2",
                     "-Duser.home=" + str(output / "home"), "-Djava.io.tmpdir=" + str(temporary),
                     "-cp", os.pathsep.join([str(test_classes), native_first]), "pzopt." + name], name + ".log")
            report["validations"] = {"strictLinkage": True, "nativeMethodParity": True,
                                     "npcGuardAdversarial": True, "jvmDefinitionsAndNativeSigners": True,
                                     "focusedHelpersAndManagedAot": list(test_names)}
        report["passed"] = True
    except Exception as failure:
        report["error"] = str(failure)
        raise
    finally:
        receipt.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("passed", "releaseQualified", "sourceFiles", "classFiles",
          "sourceInventorySha256", "classInventorySha256", "validations")}, indent=2))


if __name__ == "__main__":
    main()
