"""Prepare reviewed generated artwork for the APK without shipping source PNGs."""
from pathlib import Path
import argparse
import json
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ASSETS = {
    "starry_night": "exec-65c79ff7-02d9-40b9-b305-c8cbcc38b10c.png",
    "sunrise": "exec-fd1fb988-c04f-45d4-959a-02a33c46faf1.png",
    "landscape": "exec-068dad60-df19-4978-94a3-16626bf7ddac.png",
    "dream": "exec-314abed6-847d-4730-b0e1-24f300099870.png",
}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Prepare generated artwork for Android drawable resources.")
    parser.add_argument("source_dir", type=Path, help="Directory containing the four source PNG files")
    source_dir = parser.parse_args().source_dir
    output = ROOT / "app/src/main/res/drawable-nodpi"
    output.mkdir(parents=True, exist_ok=True)
    manifest = []
    for key, name in ASSETS.items():
        with Image.open(source_dir / name) as original:
            painting = ImageOps.fit(original.convert("RGB"), (900, 2000), method=Image.Resampling.LANCZOS)
            target = output / f"skin_{key}.webp"
            painting.save(target, "WEBP", quality=78, method=6)
            manifest.append({"key": key, "file": target.name, "width": 900, "height": 2000,
                             "bytes": target.stat().st_size})
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
