#!/usr/bin/env python3
"""Compose portrait screenshot groups into horizontal article illustrations."""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps, UnidentifiedImageError


SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = PROJECT_ROOT / "screenshots"
DEFAULT_OUTPUT = DEFAULT_SOURCE / "illustrations"


class CompositionError(Exception):
    """Raised when a screenshot group cannot be rendered safely."""


@dataclass(frozen=True)
class Layout:
    screen_height: int = 1200
    gap: int = 64
    padding: int = 96
    corner_radius: int = 48
    shadow_offset: int = 8
    shadow_blur: int = 16
    shadow_opacity: int = 32


@dataclass(frozen=True)
class CompositionResult:
    group: str
    output_path: Path
    source_names: tuple[str, ...]
    size: tuple[int, int]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Create one horizontal article illustration for each screenshot group."
    )
    parser.add_argument(
        "--source",
        type=Path,
        default=DEFAULT_SOURCE,
        help=f"Screenshot groups directory (default: {DEFAULT_SOURCE})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT,
        help=f"Generated illustrations directory (default: {DEFAULT_OUTPUT})",
    )
    parser.add_argument(
        "--screen-height",
        type=int,
        default=Layout.screen_height,
        help="Height of each screen in pixels (default: 1200)",
    )
    parser.add_argument(
        "--gap",
        type=int,
        default=Layout.gap,
        help="Whitespace between screens in pixels (default: 64)",
    )
    parser.add_argument(
        "--padding",
        type=int,
        default=Layout.padding,
        help="Whitespace around the row in pixels (default: 96)",
    )
    parser.add_argument(
        "--corner-radius",
        type=int,
        default=Layout.corner_radius,
        help="Screen-corner radius in pixels (default: 48)",
    )
    parser.add_argument(
        "--shadow-offset",
        type=int,
        default=Layout.shadow_offset,
        help="Downward shadow offset in pixels (default: 8)",
    )
    parser.add_argument(
        "--shadow-blur",
        type=int,
        default=Layout.shadow_blur,
        help="Shadow blur radius in pixels (default: 16)",
    )
    parser.add_argument(
        "--shadow-opacity",
        type=int,
        default=Layout.shadow_opacity,
        help="Shadow opacity from 0 to 255 (default: 32)",
    )
    return parser.parse_args()


def validate_layout(layout: Layout) -> None:
    if layout.screen_height <= 0:
        raise CompositionError("--screen-height must be greater than zero")
    if min(layout.gap, layout.padding, layout.corner_radius, layout.shadow_offset, layout.shadow_blur) < 0:
        raise CompositionError("gap, padding, corner radius, and shadow values cannot be negative")
    if not 0 <= layout.shadow_opacity <= 255:
        raise CompositionError("--shadow-opacity must be between 0 and 255")


def image_files(group_path: Path) -> list[Path]:
    return sorted(
        (
            path
            for path in group_path.iterdir()
            if path.is_file()
            and not path.name.startswith(".")
            and path.suffix.lower() in SUPPORTED_EXTENSIONS
        ),
        key=lambda path: path.name,
    )


def load_portrait_image(path: Path) -> Image.Image:
    try:
        with Image.open(path) as image:
            normalized = ImageOps.exif_transpose(image).convert("RGBA")
    except (OSError, UnidentifiedImageError) as error:
        raise CompositionError(f"{path}: unable to read image ({error})") from error

    if normalized.width >= normalized.height:
        raise CompositionError(
            f"{path}: expected a portrait screenshot; the initial article layout accepts portrait images only"
        )
    return normalized


def resize_screen(image: Image.Image, layout: Layout) -> Image.Image:
    width = round(image.width * layout.screen_height / image.height)
    return image.resize((width, layout.screen_height), Image.Resampling.LANCZOS)


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255
    )
    return mask


def round_corners(image: Image.Image, radius: int) -> Image.Image:
    if radius == 0:
        return image
    image.putalpha(rounded_mask(image.size, radius))
    return image


def add_shadow(canvas: Image.Image, screen: Image.Image, x: int, y: int, layout: Layout) -> None:
    if layout.shadow_opacity == 0:
        return
    spread = layout.shadow_blur * 2
    shadow_size = (screen.width + spread * 2, screen.height + spread * 2)
    shadow_mask = Image.new("L", shadow_size, 0)
    shadow_mask.paste(rounded_mask(screen.size, layout.corner_radius), (spread, spread))
    shadow_mask = shadow_mask.point(lambda alpha: alpha * layout.shadow_opacity // 255)
    shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(layout.shadow_blur))
    shadow = Image.new("RGBA", shadow_size, (0, 0, 0, 0))
    shadow.putalpha(shadow_mask)
    canvas.alpha_composite(
        shadow,
        (x - spread, y - spread + layout.shadow_offset),
    )


def compose_group(group_path: Path, output_dir: Path, layout: Layout) -> CompositionResult | None:
    sources = image_files(group_path)
    if not sources:
        return None

    screens = [round_corners(resize_screen(load_portrait_image(path), layout), layout.corner_radius) for path in sources]
    width = (layout.padding * 2) + sum(screen.width for screen in screens) + layout.gap * (len(screens) - 1)
    height = layout.screen_height + (layout.padding * 2)
    canvas = Image.new("RGBA", (width, height), "white")

    x = layout.padding
    for screen in screens:
        add_shadow(canvas, screen, x, layout.padding, layout)
        canvas.alpha_composite(screen, (x, layout.padding))
        x += screen.width + layout.gap

    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / f"{group_path.name}.png"
    canvas.convert("RGB").save(output_path, "PNG", optimize=True)
    return CompositionResult(
        group=group_path.name,
        output_path=output_path,
        source_names=tuple(path.name for path in sources),
        size=canvas.size,
    )


def compose_all(source_dir: Path, output_dir: Path, layout: Layout) -> list[CompositionResult]:
    validate_layout(layout)
    if not source_dir.is_dir():
        raise CompositionError(f"Screenshot source directory does not exist: {source_dir}")

    results = []
    output_directory = output_dir.resolve()
    group_paths = (
        path
        for path in source_dir.iterdir()
        if path.is_dir() and path.resolve() != output_directory
    )
    for group_path in sorted(group_paths, key=lambda path: path.name):
        result = compose_group(group_path, output_dir, layout)
        if result is not None:
            results.append(result)
    if not results:
        raise CompositionError(f"No supported images were found in groups under: {source_dir}")
    return results


def main() -> int:
    args = parse_args()
    layout = Layout(
        screen_height=args.screen_height,
        gap=args.gap,
        padding=args.padding,
        corner_radius=args.corner_radius,
        shadow_offset=args.shadow_offset,
        shadow_blur=args.shadow_blur,
        shadow_opacity=args.shadow_opacity,
    )
    try:
        results = compose_all(args.source, args.output, layout)
    except CompositionError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1

    for result in results:
        names = ", ".join(result.source_names)
        print(f"Created {result.output_path} ({result.size[0]}x{result.size[1]}) from {names}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
