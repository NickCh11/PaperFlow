from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class DependencyVerificationTest(unittest.TestCase):
    def test_uncached_linux_metadata_and_host_tools_are_pinned(self):
        root = Path(__file__).resolve().parents[2]
        ns = {"v": "https://schema.gradle.org/dependency-verification"}
        metadata = ET.parse(root / "gradle/verification-metadata.xml").getroot()
        components = {(c.get("group"), c.get("name"), c.get("version")): {a.get("name") for a in c.findall("v:artifact", ns)}
                      for c in metadata.findall("v:components/v:component", ns)}
        required = {("com.google.guava", "guava-parent", "33.4.0-jre"): {"guava-parent-33.4.0-jre.pom"},
                    ("com.android.tools.build", "aapt2", "9.4.1-15978811"): {f"aapt2-9.4.1-15978811-{host}.jar" for host in ("linux", "osx", "windows")}}
        required.update({("org.junit", "junit-bom", v): {f"junit-bom-{v}.pom", f"junit-bom-{v}.module"}
                         for v in ("5.10.2", "5.11.0-M2", "6.0.3")})
        for group, name, version in (("com.google.guava", "guava-parent", "33.3.1-android"),
                                     ("org.apache.groovy", "groovy-bom", "4.0.29"),
                                     ("org.jetbrains.kotlin", "kotlin-bom", "1.8.0"),
                                     ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.6.4"),
                                     ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.8.0"),
                                     ("org.jetbrains.kotlinx", "kotlinx-coroutines-bom", "1.8.1"),
                                     ("org.junit", "junit-bom", "5.13.3")):
            required[(group, name, version)] = {f"{name}-{version}.pom"}
        for component, artifacts in required.items():
            self.assertTrue(artifacts <= components.get(component, set()), component)

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
