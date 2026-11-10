#!/usr/bin/env python3
"""Disposable-file regression tests; never reads or writes a playable install."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location("pzopt_zb_install", Path(__file__).with_name("install-zombiebuddy.py"))
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def archive(path, entries):
    with zipfile.ZipFile(path, "w") as opened:
        for name, data in entries.items():
            opened.writestr(name, data)


class LauncherSetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="pzopt-zb-install-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "Game With Spaces"
        self.root.mkdir()
        self.game = self.root / "projectzomboid.jar"
        self.buddy = self.root / "ZombieBuddy.jar"
        self.mod = self.root / "PZ Optimization.jar"
        self.launcher = self.root / "ProjectZomboid64.json"
        archive(self.game, {"zombie/World.class": b"stock world", "zombie/gameStates/MainScreenState.class": b"native startup"})
        archive(self.buddy, {"META-INF/MANIFEST.MF": "Manifest-Version: 1.0\r\nPremain-Class: official.Agent\r\n\r\nName: first.class\r\nSHA-256-Digest: first\r\n\r\nName: second.class\r\nSHA-256-Digest: second\r\n\r\n"})
        self.game_sha = tool.digest(self.game)
        self.buddy_sha = tool.digest(self.buddy)
        self.entries = {
            "META-INF/MANIFEST.MF": f"Manifest-Version: 1.0\r\nPremain-Class: {tool.PREMAIN}\r\nZB-Preload: true\r\n\r\n",
            tool.PREMAIN.replace(".", "/") + ".class": b"fixture bootstrap",
            tool.PAYLOAD + "zombie/World.class": b"optimized world",
            tool.PROFILE: "\n".join(["schema=1", "game.sha256=" + self.game_sha, "zombiebuddy.sha256=" + self.buddy_sha,
                "stock.zombie/World=" + sha(b"stock world"), "payload.zombie/World=" + sha(b"optimized world"), "startup.stock=" + sha(b"native startup")]) + "\n",
        }
        archive(self.mod, self.entries)
        self.original = {"mainClass": "zombie/gameStates/MainScreenState", "classpath": [".", "lib/other.jar", "projectzomboid.jar"],
            "vmArgs": ["-Xmx6g", "-XX:+UseZGC", "-javaagent:other-agent.jar=keep", "-javaagent:" + self.buddy.as_posix() + "=debug=true", "-Dcustom=value"],
            "windows": {"10": {"vmArgs": ["-Dplatform=unchanged"]}}}
        self.launcher.write_bytes((json.dumps(self.original, indent=3) + "\r\n").encode())
        self.original_bytes = self.launcher.read_bytes()
        (self.root / "ProjectZomboid64.bat").write_bytes(b"Do not modify a BAT\r\n")
        (self.root / "user-settings.ini").write_bytes(b"keep=true\n")
        for name, value in [("GAME_SHA", self.game_sha), ("ZB_SHA", self.buddy_sha)]:
            patched = patch.object(tool, name, value)
            patched.start()
            self.addCleanup(patched.stop)
        closed = patch.object(tool, "assert_game_closed")
        self.closed = closed.start()
        self.addCleanup(closed.stop)

    def install(self):
        return tool.install(self.root, self.mod, self.buddy)

    def managed_aot_cycle(self):
        settings = tool.read_json(self.launcher)
        settings["classpath"] = [str(self.game), str(self.mod), "lib/other.jar"]
        settings["vmArgs"] = ["-XX:+UseG1GC" if value == "-XX:+UseZGC" else "-Xmx8g" if value == "-Xmx6g" else value for value in settings["vmArgs"]]
        settings["vmArgs"] += ["-Dpzopt.gc=g1", tool.AOT_MARKER,
            "-XX:+UnlockDiagnosticVMOptions", tool.UNLOCK_MARKER,
            "-XX:-AOTInvokeDynamicLinking", tool.INDY_MARKER,
            "-XX:AOTCache=" + str(self.root / "pzopt/aot-zombiebuddy/pzopt-zombiebuddy.aot"),
            "-Xlog:aot=info:file=" + str(self.root / "pzopt/aot-zombiebuddy/aot.log") + "::filecount=0"]
        settings["currentUserField"] = "preserve"
        self.launcher.write_text(json.dumps(settings))
        return settings

    def test_orders_agents_and_native_jar_preserves_every_other_setting_and_undoes_exactly(self):
        result = self.install()
        actual = tool.read_json(self.launcher)
        own = "-javaagent:" + self.mod.as_posix()
        self.assertEqual(actual["vmArgs"].index(own) + 1, actual["vmArgs"].index(self.original["vmArgs"][3]))
        actual["vmArgs"].remove(own)
        actual["classpath"].remove(self.mod.as_posix())
        self.assertEqual(actual["classpath"], ["projectzomboid.jar", ".", "lib/other.jar"])
        actual["classpath"] = self.original["classpath"]
        self.assertEqual(actual, self.original)
        self.assertEqual(Path(result["backup"]).read_bytes(), self.original_bytes)
        self.assertEqual((self.root / "ProjectZomboid64.bat").read_bytes(), b"Do not modify a BAT\r\n")
        self.assertEqual((self.root / "user-settings.ini").read_bytes(), b"keep=true\n")
        self.assertTrue(tool.undo(self.root)["changed"])
        self.assertEqual(self.launcher.read_bytes(), self.original_bytes)
        self.assertFalse(tool.undo(self.root)["changed"])

    def test_repeated_install_is_idempotent_and_keeps_original_backup(self):
        self.install()
        before = self.launcher.read_bytes()
        backups = list((self.root / "pzopt-zombiebuddy-backups").rglob("*"))
        self.assertFalse(self.install()["changed"])
        self.assertEqual(self.launcher.read_bytes(), before)
        self.assertEqual(list((self.root / "pzopt-zombiebuddy-backups").rglob("*")), backups)

    def test_missing_existing_zombiebuddy_setup_is_refused_before_writes(self):
        settings = dict(self.original, vmArgs=["-Xmx6g"])
        self.launcher.write_text(json.dumps(settings))
        before = self.launcher.read_bytes()
        with self.assertRaisesRegex(ValueError, "install official ZombieBuddy first"):
            self.install()
        self.assertEqual(self.launcher.read_bytes(), before)
        self.assertFalse((self.root / "pzopt-zombiebuddy-backups").exists())

    def test_foreign_loose_native_target_is_preserved_and_blocks_setup(self):
        path = self.root / "zombie/World.class"
        path.parent.mkdir()
        path.write_bytes(b"foreign mod")
        with self.assertRaisesRegex(ValueError, "Loose native payload"):
            self.install()
        self.assertEqual(path.read_bytes(), b"foreign mod")
        self.assertEqual(self.launcher.read_bytes(), self.original_bytes)

    def test_loose_protected_startup_family_is_preserved_and_blocks_setup(self):
        path = self.root / "zombie/gameStates/MainScreenState$Old.class"
        path.parent.mkdir(parents=True)
        path.write_bytes(b"old startup")
        with self.assertRaisesRegex(ValueError, "protected native"):
            self.install()
        self.assertEqual(path.read_bytes(), b"old startup")

    def test_native_classes_at_mod_jar_root_are_refused(self):
        archive(self.mod, {**self.entries, "zombie/World.class": b"unsafe loose definition"})
        with self.assertRaisesRegex(ValueError, "classpath root"):
            self.install()

    def test_edited_payload_and_wrong_game_or_loader_are_refused(self):
        archive(self.mod, {**self.entries, tool.PAYLOAD + "zombie/World.class": b"changed"})
        with self.assertRaisesRegex(ValueError, "hash differs"):
            self.install()
        archive(self.mod, self.entries)
        with patch.object(tool, "GAME_SHA", "0" * 64), self.assertRaisesRegex(ValueError, "Unreviewed game"):
            self.install()
        with patch.object(tool, "ZB_SHA", "0" * 64), self.assertRaisesRegex(ValueError, "official ZombieBuddy"):
            self.install()
        self.assertEqual(self.launcher.read_bytes(), self.original_bytes)

    def test_undo_preserves_launcher_edited_after_install(self):
        self.install()
        settings = tool.read_json(self.launcher)
        settings["vmArgs"].append("-Duser.added=preserve")
        self.launcher.write_text(json.dumps(settings))
        edited = self.launcher.read_bytes()
        with self.assertRaisesRegex(ValueError, "edited after setup"):
            tool.undo(self.root)
        self.assertEqual(self.launcher.read_bytes(), edited)

    def test_damaged_backup_blocks_undo(self):
        installed = self.install()
        Path(installed["backup"]).write_bytes(b"changed backup")
        before = self.launcher.read_bytes()
        with self.assertRaisesRegex(ValueError, "backup differs"):
            tool.undo(self.root)
        self.assertEqual(self.launcher.read_bytes(), before)

    def test_running_game_blocks_setup_before_launcher_or_backup_mutation(self):
        self.closed.side_effect = ValueError("Close Project Zomboid")
        with self.assertRaisesRegex(ValueError, "Close Project"):
            self.install()
        self.assertEqual(self.launcher.read_bytes(), self.original_bytes)
        self.assertFalse((self.root / "pzopt-zombiebuddy-backups").exists())

    def test_record_write_failure_restores_original_launcher(self):
        original_write = tool.atomic_write
        def fail_record(path, data):
            if path.name == tool.STATE:
                raise OSError("injected record write failure")
            original_write(path, data)
        with patch.object(tool, "atomic_write", side_effect=fail_record):
            with self.assertRaisesRegex(OSError, "injected"):
                self.install()
        self.assertEqual(self.launcher.read_bytes(), self.original_bytes)
        self.assertFalse((self.root / tool.STATE).exists())

    def test_owned_aot_cycle_undo_removes_only_our_flags_and_preserves_current_gc_heap_and_user_fields(self):
        self.install()
        self.managed_aot_cycle()
        self.assertTrue(tool.undo(self.root)["scopedAotCleanup"])
        actual = tool.read_json(self.launcher)
        self.assertIn("-XX:+UseG1GC", actual["vmArgs"])
        self.assertIn("-Xmx8g", actual["vmArgs"])
        self.assertIn("-Dpzopt.gc=g1", actual["vmArgs"])
        self.assertIn("-javaagent:other-agent.jar=keep", actual["vmArgs"])
        self.assertEqual(actual["currentUserField"], "preserve")
        self.assertNotIn(str(self.mod), actual["classpath"])
        self.assertFalse(any("AOTCache" in value or value == tool.AOT_MARKER or value == tool.INDY_MARKER or value == tool.UNLOCK_MARKER for value in actual["vmArgs"]))
        self.assertFalse(tool.undo(self.root)["changed"])

    def test_upgrade_clears_owned_old_cache_before_next_boot_and_keeps_current_gc_and_heap(self):
        self.install()
        self.managed_aot_cycle()
        archive(self.mod, {**self.entries, "pzopt/UpgradedHelper.class": b"new helper version"})
        self.assertTrue(self.install()["changed"])
        actual = tool.read_json(self.launcher)
        self.assertFalse(any(value.startswith("-XX:AOT") or "zombiebuddyAot" in value for value in actual["vmArgs"]))
        self.assertIn("-Xmx8g", actual["vmArgs"])
        self.assertIn("-XX:+UseG1GC", actual["vmArgs"])
        self.assertEqual(actual["currentUserField"], "preserve")
        self.assertFalse(self.install()["changed"])
        self.assertTrue(tool.undo(self.root)["scopedAotCleanup"])
        self.assertIn("-Xmx8g", tool.read_json(self.launcher)["vmArgs"])

    def test_foreign_aot_cache_is_never_removed_or_overwritten_even_with_our_marker(self):
        self.install()
        settings = self.managed_aot_cycle()
        settings["vmArgs"].append("-XX:AOTCache=/foreign/cache.aot")
        self.launcher.write_text(json.dumps(settings))
        original = self.launcher.read_bytes()
        for operation in [lambda: tool.undo(self.root), self.install]:
            with self.assertRaisesRegex(ValueError, "Foreign AOT"):
                operation()
            self.assertEqual(self.launcher.read_bytes(), original)

    def test_unmarked_user_no_indy_and_unlock_flags_survive_owned_cache_cleanup(self):
        settings = tool.read_json(self.launcher)
        settings["vmArgs"] += ["-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTInvokeDynamicLinking"]
        self.launcher.write_text(json.dumps(settings))
        self.install()
        current = self.managed_aot_cycle()
        current["vmArgs"].remove(tool.INDY_MARKER)
        current["vmArgs"].remove(tool.UNLOCK_MARKER)
        # A real plan does not add duplicate flags when the player already set them.
        current["vmArgs"].remove("-XX:+UnlockDiagnosticVMOptions")
        current["vmArgs"].remove("-XX:-AOTInvokeDynamicLinking")
        self.launcher.write_text(json.dumps(current))
        tool.undo(self.root)
        actual = tool.read_json(self.launcher)
        self.assertIn("-XX:+UnlockDiagnosticVMOptions", actual["vmArgs"])
        self.assertIn("-XX:-AOTInvokeDynamicLinking", actual["vmArgs"])

    def test_unowned_aot_configuration_refuses_initial_setup(self):
        settings = tool.read_json(self.launcher)
        settings["vmArgs"].append("-XX:AOTCache=/foreign/cache.aot")
        self.launcher.write_text(json.dumps(settings))
        original = self.launcher.read_bytes()
        with self.assertRaisesRegex(ValueError, "Foreign AOT"):
            self.install()
        self.assertEqual(self.launcher.read_bytes(), original)


if __name__ == "__main__":
    unittest.main()
