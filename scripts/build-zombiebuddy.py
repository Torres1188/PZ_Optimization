#!/usr/bin/env python3
"""Package an already reviewed complete payload for official ZombieBuddy 2.3.4.

No game install, launch, download, updater or JVM-option changes. The input must be
the fully prepared classes/resource tree, not merely javac output. The package
keeps every pzopt helper/resource and relocates native definitions as data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOADER_SHA = "eb79b9876332010733a8e0d7ce4fe0377846059a9e28859029e2a0fb449f6cf2"
PAYLOAD_PREFIX = "META-INF/pzopt-zombiebuddy/payload/"
PROFILE = "META-INF/pzopt-zombiebuddy/profile.properties"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def protected(name: str) -> bool:
    families = ("zombie/gameStates/MainScreenState", "zombie/core/Core", "zombie/core/IntegrityReport", "zombie/network/GameServer")
    return (any(name == family or name.startswith(family + "$") for family in families)
            or name == "zombie/GitVersion" or name.startswith("zombie/network/Integrity") or name.startswith("zombie/core/integrity/"))


def pack(payload_root: pathlib.Path, game_jar: pathlib.Path, loader_jar: pathlib.Path,
         output: pathlib.Path, javac: str, version: str) -> dict:
    payload_root, game_jar, loader_jar = (p.resolve(strict=True) for p in (payload_root, game_jar, loader_jar))
    output = output.resolve()
    build_root = ROOT / "build"
    if not output.is_relative_to(build_root.resolve()) or output == build_root.resolve():
        raise ValueError("output must be a new directory below this checkout's build/")
    if output.exists():
        raise ValueError(f"output already exists; select a new review directory: {output}")
    if sha(loader_jar.read_bytes()) != LOADER_SHA:
        raise ValueError("only the reviewed official ZombieBuddy 2.3.4 release is supported")
    if not (payload_root / "pzopt/build-info.properties").is_file():
        raise ValueError("payload-root must include generated pzopt/build-info.properties")
    if not (payload_root / "pzopt/override-methods.properties").is_file():
        # The upstream generator currently names this file differently; accept its exact published names.
        candidates = list((payload_root / "pzopt").glob("*override*")) + list((payload_root / "pzopt").glob("*methods*"))
        if not candidates:
            raise ValueError("payload-root is missing the generated mod-compatibility method inventory")
    game_hash = sha(game_jar.read_bytes())
    entries: dict[str, bytes] = {}
    native: dict[str, tuple[str, str]] = {}
    excluded: list[str] = []
    helpers: list[str] = []
    with zipfile.ZipFile(game_jar) as game:
        game_names = set(game.namelist())
        for path in sorted(payload_root.rglob("*")):
            if not path.is_file():
                continue
            if path.is_symlink():
                raise ValueError(f"symlink in prepared payload: {path}")
            name = path.relative_to(payload_root).as_posix()
            data = path.read_bytes()
            if name == "media/lua/client/ISUI/ISVersionWaterMark.lua":
                raise ValueError("protected native version watermark must not be bundled")
            if name.endswith(".class"):
                binary = name[:-6]
                if protected(binary):
                    excluded.append(name)
                    continue
                if name.startswith("pzopt/"):
                    entries[name] = data
                    helpers.append(name)
                elif name in game_names:
                    native[binary] = (sha(game.read(name)), sha(data))
                    entries[PAYLOAD_PREFIX + name] = data
                else:
                    raise ValueError(f"new class in a native package must be source-relocated to pzopt: {name}")
            elif name.startswith("META-INF/"):
                raise ValueError(f"unreviewed prepared-payload metadata: {name}")
            else:
                entries[name] = data
    if not native:
        raise ValueError("no native override classes were supplied")
    adapter_sources = sorted((ROOT / "src/zombiebuddy").rglob("*.java"))
    if not adapter_sources:
        raise ValueError("adapter source is absent")
    with tempfile.TemporaryDirectory(prefix="pzopt-zb-compile-") as temporary:
        classes = pathlib.Path(temporary) / "classes"
        classes.mkdir()
        command = [javac, "--release", "25", "-encoding", "UTF-8", "-g", "-d", str(classes), *map(str, adapter_sources)]
        subprocess.run(command, check=True)
        for path in sorted(classes.rglob("*.class")):
            name = path.relative_to(classes).as_posix()
            if name in entries:
                raise ValueError(f"adapter/helper collision: {name}")
            entries[name] = path.read_bytes()
    profile = {"schema": "1", "game.sha256": game_hash, "zombiebuddy.sha256": LOADER_SHA}
    with zipfile.ZipFile(game_jar) as game:
        startup = "zombie/gameStates/MainScreenState.class"
        if startup in game.namelist():
            profile["startup.stock"] = sha(game.read(startup))
    for binary, (stock_hash, payload_hash) in native.items():
        profile["stock." + binary] = stock_hash
        profile["payload." + binary] = payload_hash
    entries[PROFILE] = ("\n".join(f"{key}={value}" for key, value in sorted(profile.items())) + "\n").encode("ascii")
    entries["META-INF/MANIFEST.MF"] = ("Manifest-Version: 1.0\r\nZB-Preload: true\r\nPremain-Class: pzopt.zombiebuddy.Bootstrap\r\n"
                                          f"Implementation-Version: {version}\r\n\r\n").encode("ascii")
    output.mkdir(parents=True)
    mod = output / "PZ_Optimization/42"
    jar = mod / "media/java/PZOptimization-ZombieBuddy.jar"
    jar.parent.mkdir(parents=True)
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    # Lua and ordinary mod media must be real mod files; retain their exact prepared bytes.
    media_files = []
    for name, data in entries.items():
        if name.startswith("media/"):
            target = mod / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            media_files.append(name)
    (mod / "mod.info").write_text("\n".join([
        "name=PZ Optimization", "id=PZ_Optimization", f"modversion={version}", "versionMin=42.22.0",
        "require=\\ZombieBuddy", "javaJarFile=media/java/PZOptimization-ZombieBuddy.jar",
        "javaPkgName=pzopt.zombiebuddy", "javaPreload=true", "ZBVersionMin=2.3.4", "ZBVersionMax=2.3.4",
        "description=Exact-build optimization payload through approved ZombieBuddy preload. Requires a full restart.",
        "incompatible=PZOptimized,PZOptPreloader,PZOptPreloader_WIP", "",
    ]), encoding="utf-8", newline="\n")
    (output / "PZ_Optimization/common").mkdir()
    receipt = {
        "schema": 1, "version": version, "game_jar": str(game_jar), "game_sha256": game_hash,
        "zombiebuddy_jar": str(loader_jar), "zombiebuddy_sha256": LOADER_SHA,
        "payload_root": str(payload_root), "native_classes": len(native), "native_inventory": native,
        "helper_classes": len(helpers), "helper_inventory": helpers,
        "excluded_protected_native_classes": excluded, "media_files": sorted(media_files),
        "native_main_screen_hooks": ["main: UserOptions.checkCacheDir before printSpecs", "update: TileDefPreload.bindSome", "exit: noLoadFade native fade timing"] if "startup.stock" in profile else [],
        "packaged_file_inventory": {name: sha(data) for name, data in sorted(entries.items())},
        "jar": str(jar), "jar_sha256": sha(jar.read_bytes()),
        "validation": "Packaging only; source/JVM/native/graphics/performance acceptance recorded separately.",
        "signing": "Unsigned review artifact. ZombieBuddy approval/signing policy remains unchanged.",
    }
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload-root", type=pathlib.Path, required=True)
    parser.add_argument("--game-jar", type=pathlib.Path, required=True)
    parser.add_argument("--zombiebuddy-jar", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--javac", default=shutil.which("javac") or "javac")
    parser.add_argument("--version", default="42.22.2-review.1")
    args = parser.parse_args()
    receipt = pack(args.payload_root, args.game_jar, args.zombiebuddy_jar, args.output, args.javac, args.version)
    print(json.dumps({key: receipt[key] for key in ("jar", "jar_sha256", "native_classes", "helper_classes")}, indent=2))


if __name__ == "__main__":
    main()
