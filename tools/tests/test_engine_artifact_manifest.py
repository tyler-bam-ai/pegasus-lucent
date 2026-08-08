import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "unified-android" / "tools" / "generate_engine_artifact_manifest.py"
SPEC = importlib.util.spec_from_file_location("artifact_manifest", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class EngineArtifactManifestTest(unittest.TestCase):
    def test_only_registered_core_artifacts_are_hashed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            libraries = root / "lib"
            libraries.mkdir()
            (libraries / "liblucent_core_test_core.so").write_bytes(b"qualified")
            (libraries / "libunrelated.so").write_bytes(b"ignored")
            registry = root / "registry.json"
            registry.write_text(json.dumps({"engines": [{
                "id": "test-core",
                "source": {"commit": "a" * 40},
            }]}), encoding="utf-8")
            result = MODULE.generate(registry, libraries)
            self.assertEqual(1, len(result["artifacts"]))
            self.assertEqual("test-core", result["artifacts"][0]["engineId"])
            self.assertEqual("a" * 40, result["artifacts"][0]["sourceCommit"])
            self.assertEqual(64, len(result["artifacts"][0]["sha256"]))


if __name__ == "__main__":
    unittest.main()
