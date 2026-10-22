#!/usr/bin/env python3
"""
Fichiers HEIF / AVIF de test de la rotation sans perte (app/src/test/resources/heif).

Vrais fichiers, écrits par de vrais encodeurs : libheif (x265) par pillow-heif pour les HEIC,
FFmpeg (libaom) pour l'AVIF. Image de 64 × 48 px en quatre quarts (rouge, vert / bleu, jaune) :
sa couleur aux coins dit dans quel sens elle s'affiche.

  pip install pillow-heif      # libheif ≥ 1.17, avec l'encodeur x265
  python3 tools/heif/make_fixtures.py app/src/test/resources/heif

- orientation-N.heic (N = 1, 3, 6, 8, 7) : orientation EXIF N, que libheif écrit en `irot` /
  `imir` (rien pour 1) ; EXIF et XMP (`tiff:Orientation`) disent la même chose, miniature de
  16 px qui partage la propriété `irot` de l'image principale ;
- grid-6.heic : image principale en grille de 2 × 2 tuiles, orientation 6, miniature ;
- plain.heic : orientation 1, ni EXIF, ni XMP, ni miniature ;
- plain.avif : AVIF de FFmpeg, sans `irot`.
"""
import pathlib
import subprocess
import sys
import tempfile

from PIL import Image
import pillow_heif

XMP = (
    '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
    '<rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Orientation="{0}"/>'
    "</rdf:RDF></x:xmpmeta>"
)


def quadrants() -> Image.Image:
    image = Image.new("RGB", (64, 48))
    for x in range(64):
        for y in range(48):
            left, top = x < 32, y < 24
            if top:
                color = (220, 40, 40) if left else (40, 200, 60)
            else:
                color = (40, 70, 220) if left else (240, 220, 40)
            image.putpixel((x, y), color)
    return image


def heic(path: pathlib.Path, orientation: int, metadata: bool = True, **options) -> None:
    exif = Image.Exif()
    exif[0x0112] = orientation
    exif[0x010F] = "Focale Cam"
    heif = pillow_heif.from_pillow(quadrants())
    if metadata:
        options.update(exif=exif.tobytes(), xmp=XMP.format(orientation).encode(), thumbnails=[16])
    else:
        # Sans EXIF, l'orientation passe directement à l'encodeur.
        heif.info["exif"] = None
    heif.save(path, quality=90, **options)


def main(target: pathlib.Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    for orientation in (1, 3, 6, 8, 7):
        heic(target / f"orientation-{orientation}.heic", orientation)
    heic(target / "grid-6.heic", 6, tile_size=32)
    heic(target / "plain.heic", 1, metadata=False)
    with tempfile.TemporaryDirectory() as temp:
        png = pathlib.Path(temp) / "quadrants.png"
        quadrants().save(png)
        subprocess.run(
            [
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(png),
                "-c:v", "libaom-av1", "-still-picture", "1", "-crf", "20", "-pix_fmt", "yuv420p",
                str(target / "plain.avif"),
            ],
            check=True,
        )


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
