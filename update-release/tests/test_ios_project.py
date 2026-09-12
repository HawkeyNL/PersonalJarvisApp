"""Catch object-ID collisions before the macOS compiler resolves file references."""
from pathlib import Path
import re
import unittest


class IOSProjectTests(unittest.TestCase):
    def test_xcode_object_definitions_are_unique(self):
        root = Path(__file__).resolve().parents[2]
        project = (root / "ios/Jarvis.xcodeproj/project.pbxproj").read_text()
        identifiers = re.findall(
            r"^\s*([A-Z0-9]{24})(?: /\*[^\n]*?\*/)? = \{\s*isa =", project, re.MULTILINE
        )
        self.assertGreater(len(identifiers), 50)
        self.assertEqual(len(identifiers), len(set(identifiers)),
                         "Duplicate Xcode object IDs can substitute a product for a Swift source")
