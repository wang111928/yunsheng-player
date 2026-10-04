"""Extract portrait launch artwork from supplied theme-detail screenshots.

Usage: py tools/prepare-startup-art.py <directory-containing-theme-folders>
Only the central artwork is retained: app chrome, rounded white borders and the
preview button are outside the crop. Original screenshots are never bundled.
"""
import argparse
from pathlib import Path
from PIL import Image, ImageOps

SOURCES = {
    "starry_night": ("梵高《星空》", "Screenshot_20261003_121958.jpg"),
    "sunrise": ("莫奈《日出·印象》", "Screenshot_20261003_122255.jpg"),
    "landscape": ("王希孟《千里江山图》", "Screenshot_20261003_122438.jpg"),
    "dream": ("卢梭《梦》", "Screenshot_20261003_122726.jpg"),
}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source_dir", type=Path)
    args = parser.parse_args()
    output = Path(__file__).resolve().parents[1] / "app/src/main/res/drawable-nodpi"
    output.mkdir(parents=True, exist_ok=True)
    for key, (folder, name) in SOURCES.items():
        with Image.open(args.source_dir / folder / name) as original:
            if original.size != (1260, 2800):
                raise ValueError(f"Unexpected source dimensions for {key}: {original.size}")
            crop = (0, 150, 1260, 2420) if key == "starry_night" else (325, 528, 965, 1932)
            artwork = original.convert("RGB").crop(crop)
            artwork = ImageOps.fit(artwork, (720, 1600), method=Image.Resampling.LANCZOS)
            target = output / f"startup_{key}.webp"
            artwork.save(target, "WEBP", quality=83, method=6)
            print(f"{target.name}: {target.stat().st_size} bytes")
