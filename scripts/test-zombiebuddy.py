#!/usr/bin/env python3
"""Isolated signed-JAR/schema/composition checks; never calls PZ game main or native code."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import sys
import uuid
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def write(root: pathlib.Path, name: str, body: str) -> pathlib.Path:
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body, encoding="utf-8")
    return target


def run(command: list[str], log: pathlib.Path, *, expect: int = 0) -> str:
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, encoding="utf-8", errors="replace")
    log.write_text(result.stdout, encoding="utf-8")
    if result.returncode != expect:
        raise RuntimeError(f"exit {result.returncode}, expected {expect}: {command[0]}; see {log}\n{result.stdout[-8000:]}")
    return result.stdout


def jar(path: pathlib.Path, classes: pathlib.Path, manifest: str | None = None) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        if manifest:
            archive.writestr("META-INF/MANIFEST.MF", manifest)
        for member in sorted(classes.rglob("*.class")):
            archive.write(member, member.relative_to(classes).as_posix())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--zombiebuddy-jar", type=pathlib.Path, required=True)
    parser.add_argument("--game-jar", type=pathlib.Path, required=True, help="Read-only dependency for the real ZombieBuddy classes, not executed")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    output = (args.output or ROOT / "build" / ("zombiebuddy-fixture-" + uuid.uuid4().hex[:10])).resolve()
    if not output.is_relative_to((ROOT / "build").resolve()) or output.exists():
        raise ValueError("select a new output directory under this checkout's build/")
    output.mkdir(parents=True)
    loader = args.zombiebuddy_jar.resolve(strict=True)
    game = args.game_jar.resolve(strict=True)
    spec = importlib.util.spec_from_file_location("pzopt_zb_builder", ROOT / "scripts/build-zombiebuddy.py")
    builder = importlib.util.module_from_spec(spec)
    assert spec.loader
    spec.loader.exec_module(builder)
    if hashlib.sha256(loader.read_bytes()).hexdigest() != builder.LOADER_SHA:
        raise ValueError("fixture requires the pinned real official ZombieBuddy 2.3.4 artifact")
    javac = shutil.which("javac") or "javac"
    java = shutil.which("java") or "java"
    keytool = shutil.which("keytool") or "keytool"
    jarsigner = shutil.which("jarsigner") or "jarsigner"

    native_src = output / "native-src"
    write(native_src, "zombie/fixture/Target.java", "package zombie.fixture; public class Target { public static int value() { return 7; } }")
    write(native_src, "zombie/fixture/Sibling.java", "package zombie.fixture; public class Sibling { public static int value() { return 8; } }")
    native_classes = output / "native-classes"
    native_classes.mkdir()
    run([javac, "--release", "25", "-d", str(native_classes), *map(str, native_src.rglob("*.java"))], output / "native-compile.log")
    native = output / "native-signed.jar"
    jar(native, native_classes, "Manifest-Version: 1.0\r\n\r\n")
    # This is a disposable self-signed test JAR, not a signature applied to game bytes.
    key = output / "fixture.p12"
    run([keytool, "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
         "-dname", "CN=PZ Optimization isolated fixture", "-storetype", "PKCS12", "-keystore", str(key),
         "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt"], output / "keytool.log")
    run([jarsigner, "-keystore", str(key), "-storepass", "fixture-only", str(native), "fixture"], output / "sign.log")

    optimized_src = output / "optimized-src"
    write(optimized_src, "zombie/fixture/Target.java", "package zombie.fixture; public class Target { public static int added = 42; public static int value() { return pzopt.fixture.Helper.value(); } }")
    write(optimized_src, "pzopt/fixture/Helper.java", "package pzopt.fixture; public final class Helper { public static int value() { return 17; } }")
    write(optimized_src, "pzopt/Config.java", "package pzopt; public final class Config { public static boolean NO_LOAD_FADE = true; }")
    write(optimized_src, "pzopt/Overrides.java", "package pzopt; public final class Overrides { public static boolean ENABLED = true; public static boolean enabled() { return ENABLED; } }")
    prepared = output / "prepared"
    prepared.mkdir()
    run([javac, "--release", "25", "-g", "-d", str(prepared), *map(str, optimized_src.rglob("*.java"))], output / "optimized-compile.log")
    write(prepared, "pzopt/build-info.properties", "revision=fixture\n")
    write(prepared, "pzopt/override-methods.properties", "zombie/fixture/Target#value=fixture\n")
    write(prepared, "media/lua/client/pzopt/fixture.lua", "-- inert fixture resource\n")
    first = builder.pack(prepared, native, loader, output / "package-a", javac, "fixture")
    second = builder.pack(prepared, native, loader, output / "package-b", javac, "fixture")
    if first["jar_sha256"] != second["jar_sha256"]:
        raise AssertionError("adapter package is not reproducible")
    adapter = pathlib.Path(first["jar"])
    with zipfile.ZipFile(adapter) as archive:
        assert "zombie/fixture/Target.class" not in archive.namelist()
        assert builder.PAYLOAD_PREFIX + "zombie/fixture/Target.class" in archive.namelist()
        assert archive.read("pzopt/fixture/Helper.class") == (prepared / "pzopt/fixture/Helper.class").read_bytes()
    assert (output / "package-a/PZ_Optimization/42/media/lua/client/pzopt/fixture.lua").read_bytes() == (prepared / "media/lua/client/pzopt/fixture.lua").read_bytes()

    probe_src = output / "probe-src"
    write(probe_src, "probe/Capture.java", "package probe; public class Capture { public static java.lang.instrument.Instrumentation instrumentation; public static void premain(String args, java.lang.instrument.Instrumentation value) { instrumentation = value; } }")
    write(probe_src, "fixture/patches/TargetExit.java", r'''
package fixture.patches;
import me.zed_0xff.zombie_buddy.Patch;
@Patch(className="zombie.fixture.Target", methodName="value")
public class TargetExit {
    @Patch.OnExit public static void exit(@Patch.Return(readOnly=false) int result) { result += 100; }
}
''')
    write(probe_src, "pzopt/zombiebuddy/AdapterFixture.java", r'''
package pzopt.zombiebuddy;
import java.nio.file.Path;
import java.util.Arrays;
public class AdapterFixture {
    public static final class Screen {
        private float fadeAlpha;
        public int calls;
        public void render() { calls++; }
        public float alpha() { return fadeAlpha; }
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path adapter = Path.of(args[1]), nativeJar = Path.of(args[2]), loaderJar = Path.of(args[3]);
        var instrumentation = probe.Capture.instrumentation;
        var loader = ClassLoader.getSystemClassLoader();
        if (mode.equals("late")) Class.forName("zombie.fixture.Target", false, loader);
        Class<?> buddy = Class.forName("me.zed_0xff.zombie_buddy.Loader");
        var field = buddy.getDeclaredField("g_instrumentation"); field.setAccessible(true); field.set(null, instrumentation);
        if (mode.equals("positive")) {
            // Install the real official loader's Advice FIRST. Its capable transformer must
            // still execute after our later-registered non-capable native replacement.
            me.zed_0xff.zombie_buddy.PatchEngine.applyPatches("fixture.patches", loader);
        }
        boolean rejected = false;
        try { PayloadRuntime.install(instrumentation, adapter, nativeJar, loaderJar, loader); }
        catch (java.io.IOException expected) { rejected = true; System.out.println("REFUSED " + expected.getMessage()); }
        if (!mode.equals("positive")) {
            if (!rejected || PayloadRuntime.active()) throw new AssertionError("unsafe admission: " + mode);
            System.out.println("PASS " + mode); return;
        }
        if (rejected || !PayloadRuntime.active()) throw new AssertionError(PayloadRuntime.status());
        Class<?> target = Class.forName("zombie.fixture.Target", true, loader);
        Class<?> sibling = Class.forName("zombie.fixture.Sibling", false, loader);
        if ((int)target.getField("added").get(null) != 42) throw new AssertionError("added field lost");
        if ((int)target.getMethod("value").invoke(null) != 117) throw new AssertionError("optimization or ZombieBuddy advice lost");
        var targetSource = target.getProtectionDomain().getCodeSource();
        var siblingSource = sibling.getProtectionDomain().getCodeSource();
        if (targetSource.getCertificates() == null || targetSource.getCertificates().length == 0) throw new AssertionError("native signers lost");
        if (!Arrays.equals(targetSource.getCertificates(), siblingSource.getCertificates())) throw new AssertionError("native signer mismatch");
        if (!Path.of(targetSource.getLocation().toURI()).toRealPath().equals(nativeJar.toRealPath())) throw new AssertionError("native provenance lost");
        instrumentation.retransformClasses(target);
        if ((int)target.getMethod("value").invoke(null) != 117) throw new AssertionError("retransformation lost or duplicated advice");
        if ((int)target.getField("added").get(null) != 42) throw new AssertionError("retransformation changed schema");
        Screen screen = new Screen();
        if (StartupHooks.beforeFade(1000L, screen) != 750L || screen.calls != 3 || screen.alpha() != 1.0f) {
            throw new AssertionError("noLoadFade timing/render drain not retained");
        }
        Class.forName("pzopt.Config").getField("NO_LOAD_FADE").set(null, false);
        Screen stock = new Screen();
        if (StartupHooks.beforeFade(1000L, stock) != 1000L || stock.calls != 0 || stock.alpha() != 0.0f) {
            throw new AssertionError("noLoadFade off changed native state");
        }
        System.out.println("PASS positive: signed native provenance, added schema, helper call, official ZombieBuddy Advice, retransformation");
    }
}
''')
    probes = output / "probe-classes"
    probes.mkdir()
    compile_cp = os.pathsep.join(map(str, (adapter, loader, game)))
    run([javac, "--release", "25", "-encoding", "UTF-8", "-cp", compile_cp, "-d", str(probes), *map(str, probe_src.rglob("*.java"))], output / "probe-compile.log")
    capture = output / "capture-agent.jar"
    jar(capture, probes, "Manifest-Version: 1.0\r\nPremain-Class: probe.Capture\r\nCan-Retransform-Classes: true\r\nCan-Redefine-Classes: true\r\n\r\n")
    wrong = output / "native-changed.jar"
    shutil.copyfile(native, wrong)
    with zipfile.ZipFile(wrong, "a") as archive:
        archive.writestr("fixture-change.txt", "changed identity")
    outcomes = {}
    for mode in ("positive", "late", "changed-game", "foreign-origin", "changed-loader"):
        selected_native = wrong if mode == "changed-game" else native
        selected_loader = native if mode == "changed-loader" else loader
        cp = [selected_native, adapter, probes, loader, game]
        if mode == "foreign-origin":
            cp.insert(0, native_classes)
        home = output / ("home-" + mode)
        home.mkdir()
        command = [java, "-Xverify:all", "-javaagent:" + str(capture), "-Djava.awt.headless=true",
                   "-Duser.home=" + str(home), "-cp", os.pathsep.join(map(str, cp)),
                   "pzopt.zombiebuddy.AdapterFixture", mode, str(adapter), str(selected_native), str(selected_loader)]
        text = run(command, output / (mode + ".log"))
        if "PASS " + mode not in text:
            raise AssertionError(f"fixture did not reach the expected assertion: {mode}")
        outcomes[mode] = "PASS"
    # The default donor AOT_NO_INDY pair must work with real official Advice,
    # native signatures, schema additions, helper calls and retransformation.
    # Only JARs occur in this classpath; the JVM refuses AOT archives of loose classes.
    aot_cache = output / "managed-default.aot"
    aot_command = [java, "-Xverify:all", "-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTInvokeDynamicLinking",
                   "-javaagent:" + str(capture), "-Djava.awt.headless=true", "-Duser.home=" + str(output / "home-aot"),
                   "-cp", os.pathsep.join(map(str, [native, adapter, capture, loader, game])),
                   "pzopt.zombiebuddy.AdapterFixture", "positive", str(adapter), str(native), str(loader)]
    record = run(aot_command[:1] + ["-XX:AOTCacheOutput=" + str(aot_cache)] + aot_command[1:], output / "aot-default-record.log")
    if not aot_cache.is_file() or aot_cache.stat().st_size == 0:
        raise AssertionError("default managed AOT fixture did not create its cache")
    use = run(aot_command[:1] + ["-XX:AOTMode=on", "-Xlog:aot", "-XX:AOTCache=" + str(aot_cache)] + aot_command[1:], output / "aot-default-use.log")
    if "PASS positive" not in record or "PASS positive" not in use or "Using AOT-linked classes: true" not in use:
        raise AssertionError("default managed AOT fixture did not reuse linked classes with advice/schema retained")
    receipt = {
        "target_execution": "Only authored fixture classes and official ZombieBuddy patch machinery; no PZ main, gameplay, graphics or native methods.",
        "zombiebuddy_jar": str(loader), "zombiebuddy_sha256": builder.LOADER_SHA,
        "adapter_sha256": first["jar_sha256"], "reproducible_package": True, "cases": outcomes,
        "defaultAot": {"flags": ["-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTInvokeDynamicLinking"],
                       "recordAndUse": "PASS", "usingAotLinkedClasses": True, "bytes": aot_cache.stat().st_size,
                       "sha256": hashlib.sha256(aot_cache.read_bytes()).hexdigest(),
                       "scope": "Eligible fixture/JDK classes; transformed native classes are not claimed archived"},
        "limitations": ["Does not prove a full official ZombieBuddy game startup", "Does not measure FPS, native graphics or map-loading speed"],
    }
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), **receipt}, indent=2))


if __name__ == "__main__":
    main()
