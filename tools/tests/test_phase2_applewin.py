import hashlib
import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "engines" / "applewin-firmware-policy.json"
LOCK = ROOT / "engines" / "applewin-source-lock.json"
OPT_IN = ROOT / "engines" / "phase2-qualification-opt-in.json"
PATCH = ROOT / "engines" / "patches" / "applewin-external-firmware.patch"
TOOL = ROOT / "tools" / "verify_applewin_firmware_exclusion.py"
SPEC = importlib.util.spec_from_file_location("verify_applewin", TOOL)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class PhaseTwoAppleWinTest(unittest.TestCase):
    def test_checked_in_policy_is_exact_and_release_gates_remain_open(self):
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        opt_in = json.loads(OPT_IN.read_text(encoding="utf-8"))
        entry = next(row for row in opt_in["engines"] if row["id"] == "applewin")
        profile = entry["firmwareProfiles"][0]
        self.assertEqual(["apple2"], entry["libraryRouteSystems"])
        self.assertEqual("user-files", profile["mode"])
        expected = [
            {key: row[key] for key in ("destination", "size", "md5", "sha256")}
            for row in policy["requiredFiles"]
        ]
        self.assertEqual([expected], profile["alternatives"])
        self.assertEqual(6, len(expected))
        self.assertFalse(policy["distribution"]["firmwareBundled"])
        self.assertTrue(policy["distribution"]["upstreamFirmwareResourcesRemoved"])
        self.assertTrue(all(value is False
                            for value in policy["releaseGates"].values()))
        self.assertEqual(
            hashlib.sha256(POLICY.read_bytes()).hexdigest(),
            lock["firmwarePolicy"]["sha256"],
        )
        self.assertEqual(
            hashlib.sha256(PATCH.read_bytes()).hexdigest(),
            lock["patches"][0]["sha256"],
        )

    def test_blob_scanner_accepts_external_and_rejects_embedded_firmware(self):
        names = [
            "Apple2e_Enhanced.rom", "Apple2e_Enhanced_Video.rom",
            "DISK2-13sector.rom", "DISK2.rom", "Parallel.rom", "SSC.rom",
        ] + [f"extra-{index}.rom" for index in range(14)]
        blobs = {name: (name + "-firmware").encode() for name in names}
        policy = {
            "requiredFiles": [{
                "destination": name,
                "size": len(blobs[name]),
                "sha256": hashlib.sha256(blobs[name]).hexdigest(),
            } for name in names[:6]]
        }
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "source.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                for name, payload in blobs.items():
                    info = tarfile.TarInfo(f"AppleWin-pinned/resource/{name}")
                    info.size = len(payload)
                    output.addfile(info, io.BytesIO(payload))
            policy_path = root / "policy.json"
            policy_path.write_text(json.dumps(policy), encoding="utf-8")
            clean = root / "clean.so"
            clean.write_bytes(b"ELF-with-external-loader-only")
            self.assertEqual([], MODULE.verify(clean, archive, policy_path))
            contaminated = root / "contaminated.so"
            contaminated.write_bytes(b"ELF" + blobs["DISK2.rom"] + b"tail")
            self.assertTrue(any("remain embedded" in error for error in
                                MODULE.verify(contaminated, archive, policy_path)))

    def test_built_proof_matches_lock_when_present(self):
        artifact = ROOT / "engines" / "build" / "arm64-v8a" / "applewin_libretro.so"
        if not artifact.is_file():
            self.skipTest("ignored local proof artifact is absent")
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        self.assertEqual(lock["artifact"]["sha256"],
                         hashlib.sha256(artifact.read_bytes()).hexdigest())


if __name__ == "__main__":
    unittest.main()
