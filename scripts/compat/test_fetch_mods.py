import unittest

from fetch_mods import check, satisfied


class RequirementTests(unittest.TestCase):
    def test_maven_bounds(self):
        self.assertTrue(satisfied("[1.21,1.21.1)", "1.21"))
        self.assertFalse(satisfied("[1.21,1.21.1)", "1.21.1"))
        self.assertTrue(satisfied("[21.1.82,)", "21.1.265"))
        self.assertTrue(satisfied("(1.21,1.21.1]", "1.21.1"))
        self.assertTrue(satisfied("[1.21.1]", "1.21.1"))
        self.assertFalse(satisfied("[1.21.1]", "1.21"))

    def test_fabric_alternatives_and_unknown(self):
        self.assertTrue(satisfied(["1.21", "1.21.1"], "1.21.1"))
        self.assertIsNone(satisfied(["1.20", "~1.21"], "1.21.1"))
        self.assertTrue(satisfied(">=0.16.0", "0.16.10"))

    def test_iris_neoforge_interval_is_not_accepted(self):
        record = {"loader": "neoforge", "status": "fetched",
                  "declares": {"minecraft": "[1.21,1.21.1)"}}
        check(record, {"minecraft_version": "1.21.1", "neoforge_version": "21.1.265"})
        self.assertEqual(record["status"], "incompatible_with_dev_environment")


if __name__ == "__main__":
    unittest.main()
