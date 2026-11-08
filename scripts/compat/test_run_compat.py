import json
import tempfile
import unittest
from pathlib import Path

from run_compat import launch_compatible, parse


class ParseTests(unittest.TestCase):
    def test_l1_needs_world_evidence_for_both_roles_and_solo(self):
        data = {"bridgesInitialized": ["fabric", "neoforge"], "worldReady": ["neoforge"], "crashReports": []}
        self.assertFalse(launch_compatible(0, ["fabric", "neoforge"], data))
        self.assertFalse(launch_compatible(0, ["fabric"], data))
        self.assertFalse(launch_compatible(0, ["fabric"], data, solo=True))
        data["sceneReady"] = ["fabric"]
        self.assertTrue(launch_compatible(0, ["fabric"], data, solo=True))
        data["worldReady"].append("fabric")
        self.assertTrue(launch_compatible(0, ["fabric", "neoforge"], data))
        self.assertFalse(launch_compatible(1, ["fabric"], data))

    def test_mesh_hook_evidence_is_not_inferred_from_mod_loading(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            console = root / "console.log"
            console.write_text("sodium loaded\n", encoding="utf-8")
            (root / "logs").mkdir()
            log = root / "logs" / "fabric.jsonl"
            record = {"event": "mesh_hook_observed", "runtime": "fabric",
                      "fields": {"path": "sodium", "role": "far", "accepted": False}}
            log.write_text(json.dumps(record) + "\n", encoding="utf-8")
            self.assertEqual(parse(console, root)["meshHookEvidence"],
                             [{"runtime": "fabric", **record["fields"]}])
            log.write_text("", encoding="utf-8")
            self.assertEqual(parse(console, root)["meshHookEvidence"], [])


if __name__ == "__main__":
    unittest.main()
