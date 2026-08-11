"""Tests for the article screenshot compositor."""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


MODULE_PATH = Path(__file__).with_name("compose_screenshots.py")
SPEC = importlib.util.spec_from_file_location("compose_screenshots", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
compositor = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = compositor
SPEC.loader.exec_module(compositor)


class ComposeScreenshotsTests(unittest.TestCase):
    def make_image(self, path: Path, size: tuple[int, int], color: str) -> None:
        Image.new("RGB", size, color).save(path)

    def test_composes_sorted_images_and_ignores_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            group = root / "android"
            group.mkdir()
            self.make_image(group / "02-second.png", (10, 20), "blue")
            self.make_image(group / "01-first.png", (10, 20), "red")
            (group / ".DS_Store").write_text("metadata")
            (group / "notes.txt").write_text("not an image")

            output = root / "output"
            layout = compositor.Layout(screen_height=20, gap=4, padding=3, corner_radius=0, shadow_opacity=0)
            results = compositor.compose_all(root, output, layout)

            self.assertEqual(results[0].source_names, ("01-first.png", "02-second.png"))
            self.assertEqual(results[0].size, (30, 26))
            with Image.open(output / "android.png") as image:
                self.assertEqual(image.getpixel((3, 3)), (255, 0, 0))
                self.assertEqual(image.getpixel((17, 3)), (0, 0, 255))
                self.assertEqual(image.getpixel((0, 0)), (255, 255, 255))

    def test_rejects_landscape_images_without_creating_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            group = root / "android"
            group.mkdir()
            self.make_image(group / "wide.png", (20, 10), "red")
            output = root / "output"

            with self.assertRaisesRegex(compositor.CompositionError, "portrait screenshot"):
                compositor.compose_all(root, output, compositor.Layout())
            self.assertFalse((output / "android.png").exists())

    def test_applies_rounded_corners(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            group = root / "ios"
            group.mkdir()
            self.make_image(group / "screen.png", (10, 20), "red")
            output = root / "output"

            compositor.compose_all(root, output, compositor.Layout(screen_height=20, gap=0, padding=3, corner_radius=4, shadow_opacity=0))

            with Image.open(output / "ios.png") as image:
                self.assertEqual(image.getpixel((3, 3)), (255, 255, 255))
                self.assertEqual(image.getpixel((8, 8)), (255, 0, 0))

    def test_ignores_output_directory_when_it_is_inside_source_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            group = root / "android"
            group.mkdir()
            self.make_image(group / "screen.png", (10, 20), "red")
            output = root / "illustrations"

            first_results = compositor.compose_all(root, output, compositor.Layout(shadow_opacity=0))
            second_results = compositor.compose_all(root, output, compositor.Layout(shadow_opacity=0))

            self.assertEqual([result.group for result in first_results], ["android"])
            self.assertEqual([result.group for result in second_results], ["android"])
            self.assertFalse((output / "illustrations.png").exists())

    def test_adds_subtle_shadow_when_enabled(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            group = root / "android"
            group.mkdir()
            self.make_image(group / "screen.png", (10, 20), "red")
            output = root / "output"
            layout = compositor.Layout(
                screen_height=20,
                gap=0,
                padding=16,
                corner_radius=0,
                shadow_offset=4,
                shadow_blur=2,
                shadow_opacity=64,
            )

            compositor.compose_all(root, output, layout)

            with Image.open(output / "android.png") as image:
                self.assertLess(image.getpixel((21, 42))[0], 255)


if __name__ == "__main__":
    unittest.main()
