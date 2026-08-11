# Article screenshot compositor

This utility turns every immediate screenshot group under `notes_recognition/screenshots/` into a single horizontal PNG for use in articles. It keeps source screenshots unchanged and writes generated files to `notes_recognition/screenshots/illustrations/` by default.

## Install dependency

```bash
python3 -m pip install -r notes_recognition/scripts/article/requirements.txt
```

## Generate illustrations

Run this command from the repository root:

```bash
python3 notes_recognition/scripts/article/compose_screenshots.py
```

The command creates `android.png`, `ios.png`, and `keyboard.png` when those source groups contain screenshots. Use `--source` and `--output` to change locations, or `--screen-height`, `--gap`, `--padding`, `--corner-radius`, `--shadow-offset`, `--shadow-blur`, and `--shadow-opacity` to tune the layout.

## Source convention

- Each immediate directory under `notes_recognition/screenshots/` is one illustration.
- `notes_recognition/screenshots/illustrations/` is reserved for generated files and is never used as an input group.
- Only direct `.jpg`, `.jpeg`, `.png`, and `.webp` files are used; hidden and non-image files are ignored.
- Screens appear in ascending filename order. Use prefixes such as `01-onboarding.png` to make narrative order explicit.
- The initial layout accepts portrait screenshots only. It preserves their aspect ratios, gives them equal height, rounded corners, and a subtle neutral shadow, and places them in one row on a white background.

## Run tests

```bash
python3 -m unittest notes_recognition/scripts/article/test_compose_screenshots.py
```
