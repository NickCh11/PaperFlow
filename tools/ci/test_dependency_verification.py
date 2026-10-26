from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class DependencyVerificationTest(unittest.TestCase):
    def test_build_verifies_artifacts_and_metadata_without_wildcard_trust(self):
        root = Path(__file__).resolve().parents[2]
        ns = {"v": "https://schema.gradle.org/dependency-verification"}
        metadata = ET.parse(root / "gradle/verification-metadata.xml").getroot()
        self.assertEqual("true", metadata.findtext("v:configuration/v:verify-metadata", namespaces=ns))
        self.assertFalse(metadata.findall(".//v:trusted-artifacts/*", ns))
        self.assertFalse(metadata.findall(".//v:ignored-artifacts/*", ns))
        artifacts = metadata.findall("v:components/v:component/v:artifact", ns)
        self.assertTrue(artifacts)
        for artifact in artifacts:
            hashes = artifact.findall("v:sha256", ns)
            self.assertTrue(hashes, artifact.get("name"))
            for digest in hashes:
                self.assertRegex(digest.get("value", ""), r"^[0-9a-f]{64}$")
        settings = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text("utf-8").splitlines()
                        if "=" in line and not line.startswith("#"))
        self.assertEqual("strict", settings.get("org.gradle.dependency.verification"))
