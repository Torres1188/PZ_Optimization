#!/usr/bin/env python3
"""Offline JSON launcher setup/undo for the exact reviewed ZombieBuddy edition.

The mod and official ZombieBuddy must already be installed. This changes no mod
selection, approval, BAT, game class, JAR, setting or save; it never starts Java.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import uuid
import zipfile

GAME_SHA = "3c4a7c5f149d784758c6fb5f8fa85f3a21d572e38bc081400e89035f8120d287"
ZB_SHA = "eb79b9876332010733a8e0d7ce4fe0377846059a9e28859029e2a0fb449f6cf2"
PROFILE = "META-INF/pzopt-zombiebuddy/profile.properties"
PAYLOAD = "META-INF/pzopt-zombiebuddy/payload/"
PREMAIN = "pzopt.zombiebuddy.Bootstrap"
STATE = "pzopt-zombiebuddy-launcher-state.json"
WRITER = "PZ_Optimization/ZombieBuddy"
AOT_MARKER = "-Dpzopt.zombiebuddyAot=true"
INDY_MARKER = "-Dpzopt.zombiebuddyAotAddedNoIndy=true"
UNLOCK_MARKER = "-Dpzopt.zombiebuddyAotAddedUnlock=true"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def redirected(path):
    return path.is_symlink() or getattr(path, "is_junction", lambda: False)()


def properties(raw, manifest=False):
    text = raw.decode("utf-8-sig")
    if manifest:
        text = re.sub(r"\r?\n ", "", text)
        # Signed JARs have repeated Name/digest attributes in per-entry sections.
        # Only the main section supplies the agent/preload attributes.
        text = text.replace("\r\n", "\n").split("\n\n", 1)[0]
    result = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith(("#", "!")):
            continue
        key, separator, value = line.partition(":" if manifest else "=")
        require(bool(separator) and key.strip() not in result, "Malformed/duplicate artifact metadata")
        result[key.strip()] = value.strip()
    return result


def unique_json(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_bytes().decode("utf-8-sig"), object_pairs_hook=unique_json)


def game_root(path):
    root = path.expanduser().resolve(strict=True)
    if not (root / "projectzomboid.jar").is_file() and (root / "projectzomboid/projectzomboid.jar").is_file():
        root = root / "projectzomboid"
    require(root.is_dir(), "--game-dir must identify the game directory")
    return root


def atomic_write(path, data):
    require(not redirected(path), "Refusing redirected output: " + str(path))
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    temporary = Path(temporary)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if path.exists():
            temporary.chmod(stat.S_IMODE(path.stat().st_mode))
        os.replace(temporary, path)
        require(path.read_bytes() == data, "Atomic output verification failed: " + str(path))
    finally:
        if temporary.exists():
            temporary.unlink()


def assert_game_closed():
    if os.name == "nt":
        script = r'''$items = @(Get-CimInstance Win32_Process | Where-Object {
          $_.Name -match '^ProjectZomboid.*\.exe$' -or
          ($_.Name -match '^javaw?\.exe$' -and ($_.CommandLine -match 'zombie[./](gameStates[./]MainScreenState|network[./](GameServer|ServerGUI)|GameWindow)' -or -not $_.CommandLine))
        } | Select-Object ProcessId,Name); ConvertTo-Json -InputObject $items -Compress'''
        result = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], capture_output=True, text=True, check=True, timeout=30)
        require(not json.loads(result.stdout.lstrip("\ufeff")), "Close Project Zomboid completely before launcher maintenance")
    else:
        result = subprocess.run(["ps", "-eo", "pid=,args="], capture_output=True, text=True, check=True, timeout=30)
        for line in result.stdout.splitlines():
            columns = line.strip().split(None, 1)
            if len(columns) != 2 or int(columns[0]) == os.getpid():
                continue
            command = columns[1]
            native = re.search(r"(?:^|[/\s])ProjectZomboid64(?:\s|$)", command)
            java = re.search(r"(?:^|[/\s])java(?:\s|$)", command) and re.search(r"zombie[./](?:gameStates[./]MainScreenState|network[./](?:GameServer|ServerGUI)|GameWindow)\b", command)
            harness = re.search(r"(?:^|[/\s])harness/run\.sh(?:\s|$)", command)
            require(not (native or java or harness), "Close Project Zomboid and its running harness before launcher maintenance (PID " + columns[0] + ")")


def checked_artifacts(root, mod, buddy):
    game = root / "projectzomboid.jar"
    require(game.is_file() and digest(game) == GAME_SHA, "Unreviewed game JAR; exact 42.22.2 bytes are required")
    require(digest(buddy) == ZB_SHA, "Only the reviewed official ZombieBuddy 2.3.4 JAR is supported")
    require("=" not in str(mod) and not any(character in str(mod) for character in '\r\n"'), "Unsupported mod-JAR path for -javaagent")
    with zipfile.ZipFile(buddy) as loader:
        require(bool(properties(loader.read("META-INF/MANIFEST.MF"), True).get("Premain-Class")), "ZombieBuddy agent manifest is missing")
    with zipfile.ZipFile(mod) as artifact, zipfile.ZipFile(game) as stock:
        names = artifact.namelist()
        require(len(names) == len(set(names)), "Duplicate mod JAR members")
        require(artifact.testzip() is None, "Mod JAR CRC verification failed")
        manifest = properties(artifact.read("META-INF/MANIFEST.MF"), True)
        require(manifest.get("Premain-Class") == PREMAIN and manifest.get("ZB-Preload", "").lower() == "true", "Use the built early-bootstrap ZombieBuddy artifact")
        require(PREMAIN.replace(".", "/") + ".class" in names, "Bootstrap class is absent")
        require(all(not name.endswith(".class") or name.startswith(("pzopt/", PAYLOAD)) for name in names), "Native/foreign class found at mod JAR classpath root")
        profile = properties(artifact.read(PROFILE))
        require(profile.get("schema") == "1" and profile.get("game.sha256") == GAME_SHA and profile.get("zombiebuddy.sha256") == ZB_SHA, "Artifact exact-build profile differs")
        targets = {key[8:] for key in profile if key.startswith("payload.")}
        require(bool(targets), "Empty native payload inventory")
        require({name[len(PAYLOAD):-6] for name in names if name.startswith(PAYLOAD) and name.endswith(".class")} == targets, "Native payload members differ from profile")
        for name in targets:
            require(re.fullmatch(r"[A-Za-z0-9_$]+(?:/[A-Za-z0-9_$]+)*", name) is not None, "Unsafe native target name")
            families = ["zombie/gameStates/MainScreenState", "zombie/core/Core", "zombie/core/IntegrityReport", "zombie/network/GameServer", "zombie/GitVersion"]
            require(not any(name == family or name.startswith(family + "$") for family in families)
                    and not name.startswith(("zombie/network/Integrity", "zombie/core/integrity/")), "Protected native payload target refused: " + name)
            for key, data in (("stock.", stock.read(name + ".class")), ("payload.", artifact.read(PAYLOAD + name + ".class"))):
                require(profile.get(key + name) == hashlib.sha256(data).hexdigest(), "Native target/profile hash differs: " + name)
        require(profile.get("startup.stock") == hashlib.sha256(stock.read("zombie/gameStates/MainScreenState.class")).hexdigest(), "Native startup profile differs")
    return targets


def classpath_path(root, text):
    path = Path(text)
    return (path if path.is_absolute() else root / path).resolve()


def agent_path(root, argument):
    if not argument.startswith("-javaagent:"):
        return None
    value = argument[len("-javaagent:"):].split("=", 1)[0]
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    require(bool(value), "Empty -javaagent argument")
    return classpath_path(root, value)


def reject_loose(root, classpath, targets):
    directories = {root}
    directories.update(path for entry in classpath if (path := classpath_path(root, entry)).is_dir())
    for directory in directories:
        for name in targets:
            require(not (directory / (name + ".class")).exists(), "Loose native payload target exists; use its original owner's removal process first: " + str(directory / (name + ".class")))
        for family in ["zombie/gameStates/MainScreenState", "zombie/core/Core", "zombie/core/IntegrityReport", "zombie/network/GameServer", "zombie/GitVersion"]:
            path = directory / family
            require(not list(path.parent.glob(path.name + "*.class")), "Loose protected native class family exists: " + str(path))
        for folder in [directory / "zombie/core/integrity", directory / "zombie/network/Integrity"]:
            require(not folder.exists() or not list(folder.rglob("*.class")), "Loose protected integrity classes exist: " + str(folder))
        require(not list((directory / "zombie/network").glob("Integrity*.class")), "Loose protected network integrity classes exist")


def encoded_json(value, previous):
    newline = "\r\n" if b"\r\n" in previous else "\n"
    return (json.dumps(value, ensure_ascii=False, indent=2).replace("\n", newline) + newline).encode("utf-8")


def argument_arrays(settings):
    yield settings["vmArgs"]
    for section in settings.get("windows", {}).values():
        if isinstance(section, dict) and isinstance(section.get("vmArgs"), list):
            yield section["vmArgs"]


def strip_owned_aot(settings, root):
    """Remove only marked, exact-path managed cache options; never GC/heap flags."""
    result = json.loads(json.dumps(settings))
    arrays = list(argument_arrays(result))
    if not any(AOT_MARKER in args for args in arrays):
        require(not any(INDY_MARKER in args or UNLOCK_MARKER in args for args in arrays), "Incomplete managed AOT ownership markers")
        return result, False
    caches, logs = set(), set()
    for text in {str(root / "pzopt/aot-zombiebuddy/pzopt-zombiebuddy.aot"), (root / "pzopt/aot-zombiebuddy/pzopt-zombiebuddy.aot").as_posix()}:
        caches.update(["-XX:AOTCache=" + text, "-XX:AOTCacheOutput=" + text])
    for text in {str(root / "pzopt/aot-zombiebuddy/aot.log"), (root / "pzopt/aot-zombiebuddy/aot.log").as_posix()}:
        logs.add("-Xlog:aot=info:file=" + text + "::filecount=0")
    all_args = [value for args in arrays for value in args]
    require(all(isinstance(value, str) for value in all_args), "Unsupported managed AOT arguments")
    require(all_args.count(AOT_MARKER) == 1 and sum(value in caches for value in all_args) == 1
            and sum(value in logs for value in all_args) == 1, "Managed AOT cache/log ownership is incomplete or duplicated")
    require(not any(value.startswith("-XX:AOT") and value not in caches for value in all_args), "Foreign AOT options are preserved; remove them through their owner first")
    for args in arrays:
        for marker, flag in [(INDY_MARKER, "-XX:-AOTInvokeDynamicLinking"), (UNLOCK_MARKER, "-XX:+UnlockDiagnosticVMOptions")]:
            require(args.count(marker) <= 1, "Duplicate managed AOT ownership marker")
            if marker in args:
                require(flag in args, "Managed AOT flag/ownership marker differs")
                reverse_index = args[::-1].index(flag)
                del args[len(args) - 1 - reverse_index]
        args[:] = [value for value in args if value not in {AOT_MARKER, INDY_MARKER, UNLOCK_MARKER} | caches | logs]
    return result, True


def managed_gc_settings(settings):
    """Recognize the original GcChoice markers without changing any GC flags."""
    for args in argument_arrays(settings):
        if any(value in args for value in ("-Dpzopt.gc=g1", "-Dpzopt.gc=g1,pause")) and "-XX:+UseG1GC" in args:
            return True
        if "-Dpzopt.jit=steady" in args and any(value in args for value in ("-XX:PerMethodTrapLimit=0", "-XX:PerBytecodeTrapLimit=0", "-XX:-UseTypeSpeculation")):
            return True
        for value in args:
            if value.startswith("-Dpzopt.gctune="):
                fields = value.split("=", 1)[1].split(",")
                if fields[0] == "g1" and len(fields) == len(set(fields)) and set(fields[1:]) <= {"unlock", "ignore"} and "-XX:+UseG1GC" in args:
                    return True
            if value.startswith("-Dpzopt.heap="):
                fields = value.split("=", 1)[1].split(",")
                if len(fields) in (3, 6) and fields[2] in ("0", "1") and any(flag.startswith("-Xmx") for flag in args):
                    old_heap = re.fullmatch(r"none|-Xmx[0-9]+[kKmMgG]?", fields[0])
                    old_initial = re.fullmatch(r"none|-Xms[0-9]+[kKmMgG]?", fields[1])
                    if old_heap and old_initial:
                        return True
    return False


def checked_state(root, launcher, state):
    require(state.get("schema") == 1 and state.get("writer") == WRITER and state.get("launcher") == str(launcher), "Unrecognized launcher ownership")
    backup = Path(state["backup"])
    require(backup.resolve().is_relative_to(root / "pzopt-zombiebuddy-backups") and digest(backup) == state["original.sha256"], "Original launcher backup differs")
    return backup


def remove_bootstrap(settings, root, state, only_added=True):
    result = json.loads(json.dumps(settings))
    own = Path(state["modJar"])
    buddy = Path(state["zombiebuddyJar"])
    args = result["vmArgs"]
    indices = [index for index, value in enumerate(args) if agent_path(root, value) == own]
    buddy_indices = [index for index, value in enumerate(args) if agent_path(root, value) == buddy]
    require(len(indices) == len(buddy_indices) == 1 and indices[0] < buddy_indices[0], "Owned early-agent ordering differs; launcher preserved")
    require(sum(classpath_path(root, entry) == own for entry in result["classpath"]) == 1, "Owned helper classpath differs; launcher preserved")
    require(classpath_path(root, result["classpath"][0]) == root / "projectzomboid.jar", "Native-first launcher ownership differs")
    if not only_added or state.get("agentAdded", True):
        del args[indices[0]]
    if not only_added or state.get("helperAdded", True):
        result["classpath"] = [entry for entry in result["classpath"] if classpath_path(root, entry) != own]
    return result


def install(game_dir, mod_jar, zombiebuddy_jar):
    root = game_root(game_dir)
    mod, buddy = (path.expanduser().resolve(strict=True) for path in (mod_jar, zombiebuddy_jar))
    launcher = root / "ProjectZomboid64.json"
    require(launcher.is_file() and not redirected(launcher), "A regular ProjectZomboid64.json launcher is required; BAT launchers are unchanged")
    targets = checked_artifacts(root, mod, buddy)
    original = launcher.read_bytes()
    settings = read_json(launcher)
    require(isinstance(settings.get("classpath"), list) and all(isinstance(entry, str) for entry in settings["classpath"]), "Unsupported launcher classpath")
    require(isinstance(settings.get("vmArgs"), list) and all(isinstance(entry, str) for entry in settings["vmArgs"]), "Unsupported launcher vmArgs")
    state_path = root / STATE
    require(not redirected(state_path), "Refusing redirected ownership record")
    state = read_json(state_path) if state_path.exists() else None
    baseline = settings
    cleaned, managed_aot = strip_owned_aot(settings, root)
    managed_runtime = managed_aot or managed_gc_settings(settings)
    upgrading = bool(state and state.get("status") == "installed")
    if state:
        checked_state(root, launcher, state)
        if upgrading:
            require(digest(launcher) == state["installed.sha256"] or managed_runtime, "Managed launcher was edited outside an owned AOT/GC cycle; preserved unchanged")
            if digest(launcher) != state["installed.sha256"] or managed_aot or state.get("mod.sha256") != digest(mod) or state.get("modJar") != str(mod):
                settings = remove_bootstrap(cleaned, root, state, only_added=False)
        else:
            require(state.get("status") == "undone" and digest(launcher) == state.get("undo.sha256", state["original.sha256"]), "Launcher changed after undo; preserved unchanged")
    else:
        require(not managed_aot, "Managed AOT markers have no verified launcher ownership record")
    require(not any(value.startswith("-XX:AOT") for args in argument_arrays(settings) for value in args),
            "Foreign AOT options are preserved; remove their configuration through its owner before bootstrap setup")
    cp = settings["classpath"]
    game_entries = [entry for entry in cp if classpath_path(root, entry) == root / "projectzomboid.jar"]
    require(len(game_entries) == 1, "Expected exactly one explicit reviewed game-JAR classpath entry")
    reject_loose(root, cp, targets)
    agents = [(index, agent_path(root, value)) for index, value in enumerate(settings["vmArgs"]) if value.startswith("-javaagent:")]
    buddy_agents = [index for index, path in agents if path == buddy]
    require(len(buddy_agents) == 1, "Existing launcher must already contain exactly one -javaagent for --zombiebuddy-jar; install official ZombieBuddy first")
    own_agents = [index for index, path in agents if path == mod]
    require(len(own_agents) <= 1, "Duplicate PZ Optimization early-agent arguments")
    for _, path in agents:
        if path != mod and path.is_file():
            try:
                with zipfile.ZipFile(path) as other:
                    require(properties(other.read("META-INF/MANIFEST.MF"), True).get("Premain-Class") != PREMAIN,
                            "Another PZ Optimization early-agent JAR is configured; undo its setup first")
            except (zipfile.BadZipFile, KeyError):
                pass
    updated = dict(settings)
    args = [value for index, value in enumerate(settings["vmArgs"]) if index not in own_agents]
    buddy_index = next(index for index, value in enumerate(args) if agent_path(root, value) == buddy)
    args.insert(buddy_index, "-javaagent:" + mod.as_posix())
    updated["vmArgs"] = args
    updated["classpath"] = [game_entries[0]] + [entry for entry in cp if entry != game_entries[0] and classpath_path(root, entry) != mod] + [mod.as_posix()]
    same_owned_setup = not state or (state.get("installed.sha256") == digest(launcher)
        and state.get("mod.sha256") == digest(mod) and state.get("modJar") == str(mod))
    if updated == baseline and not managed_aot and same_owned_setup:
        if state:
            require(state.get("writer") == WRITER and state.get("installed.sha256") == digest(launcher), "Existing launcher ownership record conflicts")
            require(digest(Path(state["backup"])) == state["original.sha256"], "Existing launcher backup differs")
            require(state.get("mod.sha256") == digest(mod), "Managed mod JAR changed; undo the owned setup before upgrading it")
        return {"ok": True, "operation": "install", "changed": False, "launcher": str(launcher), "fullRestartRequired": True}
    assert_game_closed()
    backup_dir = root / "pzopt-zombiebuddy-backups" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8])
    require(backup_dir.resolve().is_relative_to(root), "Redirected backup directory")
    require(not backup_dir.exists(), "Backup destination already exists")
    backup_dir.mkdir(parents=True)
    backup = backup_dir / launcher.name
    shutil.copy2(launcher, backup)
    require(backup.read_bytes() == original, "Launcher backup verification failed")
    if state_path.exists():
        shutil.copy2(state_path, backup_dir / "previous-state.json")
    encoded = encoded_json(updated, original)
    record = {"schema": 1, "writer": WRITER, "status": "installed", "launcher": str(launcher), "backup": str(backup),
        "original.sha256": hashlib.sha256(original).hexdigest(), "installed.sha256": hashlib.sha256(encoded).hexdigest(),
        "game.sha256": GAME_SHA, "modJar": str(mod), "mod.sha256": digest(mod), "zombiebuddyJar": str(buddy), "zombiebuddy.sha256": ZB_SHA,
        "agentAdded": not own_agents, "helperAdded": not any(classpath_path(root, entry) == mod for entry in cp),
        "scopedUndo": managed_aot or bool(upgrading and (state.get("scopedUndo") or managed_runtime and digest(launcher) != state["installed.sha256"]))}
    if upgrading:
        record.update(backup=state["backup"], **{"original.sha256": state["original.sha256"]})
        record["upgradeBackup"] = str(backup)
        record["agentAdded"] = state.get("agentAdded", True) or state.get("modJar") != str(mod)
        record["helperAdded"] = state.get("helperAdded", True) or state.get("modJar") != str(mod)
    assert_game_closed()
    require(launcher.read_bytes() == original, "Launcher changed during preparation")
    try:
        atomic_write(launcher, encoded)
        atomic_write(state_path, (json.dumps(record, indent=2) + "\n").encode())
    except Exception:
        atomic_write(launcher, original)
        raise
    return {"ok": True, "operation": "install", "changed": True, "launcher": str(launcher), "backup": str(backup),
        "fullRestartRequired": True, "guidance": "Enable PZ_Optimization and ZombieBuddy and complete normal preload approval. No selection/approval was changed. Undo this launcher setup before removing the early-agent JAR. BAT launchers were not modified."}


def undo(game_dir):
    root = game_root(game_dir)
    state_path = root / STATE
    require(state_path.is_file() and not redirected(state_path), "Owned launcher state is missing")
    state = read_json(state_path)
    launcher = root / "ProjectZomboid64.json"
    backup = checked_state(root, launcher, state)
    current = launcher.read_bytes()
    if state.get("status") == "undone":
        require(digest(launcher) == state.get("undo.sha256", state["original.sha256"]), "Launcher changed after undo")
        return {"ok": True, "operation": "undo", "changed": False, "fullRestartRequired": True}
    cleaned, managed_aot = strip_owned_aot(read_json(launcher), root)
    require(not any(value.startswith("-XX:AOT") for args in argument_arrays(cleaned) for value in args), "Foreign AOT options are preserved; use their owner's removal process")
    managed_runtime = managed_aot or managed_gc_settings(cleaned)
    unchanged = digest(launcher) == state["installed.sha256"]
    require(state.get("status") == "installed" and (unchanged or managed_runtime), "Launcher was edited after setup; preserved unchanged, restore its desired flags manually from the backup")
    assert_game_closed()
    scoped = managed_aot or not unchanged and managed_runtime or state.get("scopedUndo", False)
    restored = encoded_json(remove_bootstrap(cleaned, root, state), current) if scoped else backup.read_bytes()
    try:
        atomic_write(launcher, restored)
        state["status"] = "undone"
        state["undo.sha256"] = hashlib.sha256(restored).hexdigest()
        atomic_write(state_path, (json.dumps(state, indent=2) + "\n").encode())
    except Exception:
        atomic_write(launcher, current)
        raise
    return {"ok": True, "operation": "undo", "changed": True, "scopedAotCleanup": bool(scoped), "launcher": str(launcher), "backupRetained": str(backup), "fullRestartRequired": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="operation", required=True)
    setup = actions.add_parser("install", help="Back up and configure an existing official ZombieBuddy JSON launcher")
    setup.add_argument("--game-dir", required=True, type=Path)
    setup.add_argument("--mod-jar", required=True, type=Path)
    setup.add_argument("--zombiebuddy-jar", required=True, type=Path)
    remove = actions.add_parser("undo", help="Restore the verified original JSON only when the owned setup is unchanged")
    remove.add_argument("--game-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        result = install(args.game_dir, args.mod_jar, args.zombiebuddy_jar) if args.operation == "install" else undo(args.game_dir)
        print(json.dumps(result, indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(json.dumps({"ok": False, "operation": args.operation, "error": str(error)}))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
