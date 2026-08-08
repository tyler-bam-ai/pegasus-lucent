import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = json.loads((ROOT / "engines" / "registry.json").read_text(encoding="utf-8"))
FIRMWARE = json.loads(
    (ROOT / "engines" / "firmware" / "manifest.json").read_text(encoding="utf-8")
)
OPT_IN = json.loads(
    (ROOT / "engines" / "qualification-opt-in.json").read_text(encoding="utf-8")
)
CATALOG = (
    ROOT / "unified-android" / "src" / "com" / "thorium" / "preview" /
    "game" / "InternalEngineCatalog.java"
).read_text(encoding="utf-8")
FETCHER = (ROOT / "engines" / "firmware" / "fetch_open_firmware.sh").read_text(
    encoding="utf-8"
)


def engine(engine_id: str) -> dict:
    return next(row for row in REGISTRY["engines"] if row["id"] == engine_id)


class OpenFirmwarePolicyTest(unittest.TestCase):
    def test_coleco_candidate_is_pinned_but_not_accepted_without_reproduction(self):
        self.assertEqual(1, FIRMWARE["schemaVersion"])
        self.assertEqual(1, len(FIRMWARE["firmware"]))
        firmware = FIRMWARE["firmware"][0]
        self.assertEqual("gearcoleco", firmware["engineId"])
        self.assertEqual("GPL-3.0-only", firmware["license"])
        self.assertEqual("https://github.com/sehugg/8bitworkshop", firmware["repository"])
        self.assertRegex(firmware["commit"], r"^[0-9a-f]{40}$")
        self.assertRegex(firmware["archiveSha256"], r"^[0-9a-f]{64}$")
        self.assertRegex(firmware["sha256"], r"^[0-9a-f]{64}$")
        self.assertIn("minbios.asm", firmware["sourcePath"])
        self.assertIn(firmware["commit"], FETCHER)
        self.assertIn(firmware["archiveSha256"], FETCHER)
        self.assertIn(firmware["sha256"], FETCHER)
        self.assertEqual(
            "blocked-unverified-source-to-binary-provenance",
            firmware["qualificationStatus"],
        )
        self.assertTrue(engine("gearcoleco")["firmware"]["required"])
        self.assertEqual([], engine("gearcoleco")["firmware"]["acceptedHashes"])
        self.assertNotIn("gearcoleco", {entry["id"] for entry in OPT_IN["engines"]})

    def test_intellivision_binary_only_substitutes_remain_fail_closed(self):
        row = engine("freeintv")
        self.assertTrue(row["firmware"]["required"])
        self.assertEqual([], row["firmware"]["acceptedHashes"])
        self.assertFalse(row["shipped"])
        self.assertEqual("experimental", row["status"])
        self.assertIn("corresponding source", row["firmware"]["notes"])
        self.assertIn("firmwareRequired && acceptedFirmwareHashes.isEmpty()", CATALOG)
        self.assertNotIn("freeintv", {entry["id"] for entry in OPT_IN["engines"]})

        forbidden = {"miniexec.bin", "minigrom.bin", "exec.bin", "grom.bin"}
        present = []
        for path in ROOT.rglob("*"):
            if not path.is_file() or path.name.lower() not in forbidden:
                continue
            relative = path.relative_to(ROOT)
            if "build" not in relative.parts and ".git" not in relative.parts:
                present.append(str(relative))
        self.assertEqual([], present)


if __name__ == "__main__":
    unittest.main()
