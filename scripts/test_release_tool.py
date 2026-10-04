import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_tool


class ReleaseToolTests(unittest.TestCase):
    def test_parse_kotlin_gradle_version(self):
        version = release_tool.parse_version_text('''
        defaultConfig {
            versionCode = 7
            versionName = "0.4.0"
        }
        ''')
        self.assertEqual(version.code, 7)
        self.assertEqual(version.name, "0.4.0")

    def test_write_kotlin_gradle_version_preserves_indentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "build.gradle.kts"
            path.write_text('    versionCode = 7\n    versionName = "0.4.0"\n', encoding="utf-8")
            release_tool.write_version(release_tool.Version("0.5.0", 8), path)
            self.assertEqual(path.read_text(encoding="utf-8"), '    versionCode = 8\n    versionName = "0.5.0"\n')

    def test_next_version_requires_greater_explicit_version(self):
        with self.assertRaises(release_tool.ReleaseError):
            release_tool.next_version(release_tool.Version("0.4.0", 7), None, "0.4.0")
        self.assertEqual(release_tool.next_version(release_tool.Version("0.4.0", 7), "minor", None), release_tool.Version("0.5.0", 8))

    def test_discover_locales_and_changelog_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for locale in ("en-US", "ru-RU"):
                locale_dir = root / "fastlane" / "metadata" / "android" / locale
                locale_dir.mkdir(parents=True)
                (locale_dir / "title.txt").write_text("Voice Inbox", encoding="utf-8")
            config = {
                "metadataRoot": "fastlane/metadata/android",
                "requiredLocales": ["en-US", "ru-RU"],
                "ignoredLocales": [],
            }
            self.assertEqual(release_tool.discover_locales(config, root), ["en-US", "ru-RU"])
            paths = release_tool.changelog_paths(release_tool.Version("0.5.0", 8), config, root)
            self.assertEqual([p.name for p in paths], ["8.txt", "8.txt"])

    def test_validate_changelogs_rejects_placeholders(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for locale in ("en-US", "ru-RU"):
                locale_dir = root / "fastlane" / "metadata" / "android" / locale
                changelog_dir = locale_dir / "changelogs"
                changelog_dir.mkdir(parents=True)
                (locale_dir / "title.txt").write_text("Voice Inbox", encoding="utf-8")
                (changelog_dir / "8.txt").write_text("TODO: RELEASE NOTES", encoding="utf-8")
            config = {
                "metadataRoot": "fastlane/metadata/android",
                "requiredLocales": ["en-US", "ru-RU"],
                "ignoredLocales": [],
                "placeholderMarkers": ["TODO: RELEASE NOTES"],
            }
            with self.assertRaises(release_tool.ReleaseError):
                release_tool.validate_changelogs(release_tool.Version("0.5.0", 8), config, root)

    def test_validate_branch_requires_release_branch_name(self):
        config = {
            "branches": {
                "development": "develop",
                "stable": "master",
                "releasePrefix": "release/",
                "hotfixPrefix": "hotfix/",
            }
        }
        with mock.patch.object(release_tool, "current_branch", return_value="release/0.5.0"), \
             mock.patch.object(release_tool, "git_ref_exists", return_value=True), \
             mock.patch("subprocess.run") as subprocess_run:
            subprocess_run.return_value.returncode = 0
            self.assertEqual(release_tool.validate_branch(release_tool.Version("0.5.0", 8), config), "release")

    def test_resolve_candidate_rejects_non_matching_branch(self):
        config = {
            "branches": {
                "development": "develop",
                "stable": "master",
                "releasePrefix": "release/",
                "hotfixPrefix": "hotfix/",
            },
            "tagPrefix": "v",
        }
        with mock.patch.object(release_tool, "read_version_from_ref", return_value=release_tool.Version("0.5.0", 8)):
            with self.assertRaises(release_tool.ReleaseError):
                release_tool.resolve_release_candidate(
                    head_branch="feature/not-release",
                    base_branch="master",
                    merge_commit="abc123",
                    merged=True,
                    config=config,
                    stable_ref="master",
                )

    def test_verify_build_identity_detects_mismatch(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / "AndroidManifest.xml"
            metadata = root / "output-metadata.json"
            manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="8" android:versionName="0.5.0" />', encoding="utf-8")
            metadata.write_text(json.dumps({"elements": [{"versionCode": 9, "versionName": "0.5.0"}]}), encoding="utf-8")
            with self.assertRaises(release_tool.ReleaseError):
                release_tool.verify_build_identity(release_tool.Version("0.5.0", 8), manifest, metadata)


if __name__ == "__main__":
    unittest.main()
