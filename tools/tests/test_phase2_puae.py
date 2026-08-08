import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "engines" / "puae-firmware-policy.json"
LOCK = ROOT / "engines" / "puae-source-lock.json"
AUDIT = ROOT / "engines" / "puae-dependency-audit.json"
CONTENT = ROOT / "engines" / "puae-test-content-lock.json"
OPT_IN = ROOT / "engines" / "phase2-qualification-opt-in.json"
TOOL = ROOT / "tools" / "verify_puae_firmware_policy.py"
FIXTURE_TOOL = (ROOT / "engines" / "qa" / "fixtures" / "puae-minimal" /
                "build_fixture.py")


def _module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


VERIFY = _module("verify_puae", TOOL)
FIXTURE = _module("build_puae_fixture", FIXTURE_TOOL)


class PhaseTwoPuaeTest(unittest.TestCase):
    def test_routes_and_firmware_profiles_are_exact_and_fail_closed(self):
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        opt_in = json.loads(OPT_IN.read_text(encoding="utf-8"))
        entry = next(row for row in opt_in["engines"] if row["id"] == "puae")
        profiles = {row["system"]: row for row in entry["firmwareProfiles"]}
        self.assertEqual(["amiga", "amigacd32"], entry["libraryRouteSystems"])
        self.assertEqual(
            "puae-aros-sha256-" +
            policy["amiga"]["decompressedPayload"]["sha256"],
            profiles["amiga"]["identity"],
        )
        self.assertEqual(policy["amigacd32"]["alternatives"],
                         profiles["amigacd32"]["alternatives"])
        self.assertFalse(policy["distribution"]["proprietaryFirmwareBundled"])
        self.assertTrue(all(value is False
                            for value in policy["releaseGates"].values()))

    def test_source_lock_binds_policy_audit_notices_and_open_content(self):
        lock = json.loads(LOCK.read_text(encoding="utf-8"))
        for key, path in (
                ("firmwarePolicy", POLICY),
                ("dependencyAudit", AUDIT),
                ("legalTestContent", CONTENT),
                ("notices", ROOT / "engines" / "PUAE-CORE-NOTICES.txt")):
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),
                             lock[key]["sha256"])
        self.assertFalse(lock["dependencyAudit"]["complete"])
        self.assertTrue(all(value is False
                            for value in lock["releaseGates"].values()))

    def test_open_fixture_is_deterministic(self):
        content = json.loads(CONTENT.read_text(encoding="utf-8"))
        payload = FIXTURE.build()
        self.assertEqual(content["artifact"]["size"], len(payload))
        self.assertEqual(content["artifact"]["sha256"],
                         hashlib.sha256(payload).hexdigest())
        self.assertFalse(content["artifact"]["bundledInApk"])
        self.assertFalse(content["runtimeQualification"])

    def test_verifier_accepts_proof_and_detects_aros_drift(self):
        core = ROOT / "engines" / "build" / "arm64-v8a" / "puae_libretro.so"
        source = (ROOT / "engines" / "build" / "sources" /
                  "puae-96ebfcfc2c66233ad37f6dc99ee991211dc719ad")
        if not core.is_file() or not source.is_dir():
            self.skipTest("ignored PUAE proof inputs are absent")
        self.assertEqual([], VERIFY.verify(core, source, POLICY))
        with tempfile.TemporaryDirectory() as directory:
            changed = Path(directory) / "policy.json"
            policy = json.loads(POLICY.read_text(encoding="utf-8"))
            policy["amiga"]["decompressedPayload"]["sha256"] = "0" * 64
            changed.write_text(json.dumps(policy), encoding="utf-8")
            self.assertTrue(any("differs" in error for error in
                                VERIFY.verify(core, source, changed)))


if __name__ == "__main__":
    unittest.main()
