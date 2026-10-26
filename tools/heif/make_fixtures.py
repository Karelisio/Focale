#!/usr/bin/env python3
"""
Fichiers HEIF / AVIF de test de la rotation sans perte (app/src/test/resources/heif).

Vrais fichiers, écrits par de vrais encodeurs : libheif (x265) par pillow-heif pour les HEIC,
FFmpeg (libaom, x265) pour l'AVIF et les vidéos. Image de 64 × 48 px en quatre quarts (rouge,
vert / bleu, jaune) : sa couleur aux coins dit dans quel sens elle s'affiche.

  pip install pillow-heif      # libheif ≥ 1.17, avec l'encodeur x265
  python3 tools/heif/make_fixtures.py app/src/test/resources/heif [fichier…]

Sans nom de fichier, tous sont écrits ; avec, seulement ceux-là (une autre version de libheif
écrirait autrement les fichiers déjà là, dont les tests connaissent des positions).

- orientation-N.heic (N = 1, 3, 6, 8, 7) : orientation EXIF N, que libheif écrit en `irot` /
  `imir` (rien pour 1) ; EXIF et XMP (`tiff:Orientation`) disent la même chose, miniature de
  16 px qui partage la propriété `irot` de l'image principale ;
- grid-6.heic : image principale en grille de 2 × 2 tuiles, orientation 6, miniature ;
- plain.heic : orientation 1, ni EXIF, ni XMP, ni miniature ;
- plain.avif : AVIF de FFmpeg, sans `irot`.

Structures des appareils photo, reproduites autour d'un HEIC de libheif (positions recalculées) :

- samsung-N.heic (N = 1, 6) : traîne Samsung après la dernière boîte, comme l'écrivent les Galaxy
  (blocs « Image_UTC_Data », « MCC_Data », « Camera_Capture_Mode_Info » et « MotionPhoto_Data »,
  vidéo HEVC d'une photo animée, puis répertoire `SEFH`, sa longueur et `SEFT`, entiers
  petit-boutistes, chaque bloc désigné par sa distance avant le répertoire) ;
- animated-1.heic : photo animée, image fixe et séquence (marque `msf1`) : `moov` d'une vidéo
  HEVC de FFmpeg, ses positions `stco` rapportées au fichier, et le `mdat` de ses images après
  celui de l'image fixe ;
- tmap-N.heic (N = 1, 6), tmap-gain-6.heic : carte de gain ISO 21496-1 des iPhone (iOS 18) et
  d'Android 16 : image de gain cachée (32 × 24, gris), image dérivée `tmap` (`dimg` vers l'image
  principale puis l'image de gain, métadonnées de gain dans `idat`), groupe `altr` (tmap, image
  principale), marque `tmap`. L'image `tmap` partage la taille et l'orientation (`irot`) de
  l'image principale ; dans tmap-gain-6.heic, l'image de gain aussi.
"""
import io
import pathlib
import struct
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


def heic_bytes(orientation: int, metadata: bool = True, extra: list | None = None, **options) -> bytes:
    exif = Image.Exif()
    exif[0x0112] = orientation
    exif[0x010F] = "Focale Cam"
    heif = pillow_heif.from_pillow(quadrants())
    for image in extra or []:
        heif.add_from_pillow(image)
    if metadata:
        options.update(exif=exif.tobytes(), xmp=XMP.format(orientation).encode(), thumbnails=[16])
    else:
        # Sans EXIF, l'orientation passe directement à l'encodeur.
        heif.info["exif"] = None
    output = io.BytesIO()
    heif.save(output, quality=90, **options)
    return output.getvalue()


def heic(path: pathlib.Path, orientation: int, metadata: bool = True, **options) -> None:
    path.write_bytes(heic_bytes(orientation, metadata, **options))


# ── Boîtes ISOBMFF ────────────────────────────────────────────────────────────────────────


def box(kind: str, payload: bytes) -> bytes:
    return struct.pack(">I", 8 + len(payload)) + kind.encode("latin-1") + payload


def full_box(kind: str, version: int, flags: int, payload: bytes) -> bytes:
    return box(kind, bytes([version]) + flags.to_bytes(3, "big") + payload)


def boxes(data: bytes, start: int = 0, end: int | None = None) -> list[tuple[str, int, int, int]]:
    """Boîtes (type, début, taille, en-tête) qui remplissent exactement [start, end[."""
    end = len(data) if end is None else end
    found, position = [], start
    while position < end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        header = 8
        if size == 1:
            size, header = struct.unpack(">Q", data[position + 8:position + 16])[0], 16
        elif size == 0:
            size = end - position
        assert size >= header and position + size <= end, "boîte mal formée"
        found.append((kind.decode("latin-1"), position, size, header))
        position += size
    assert position == end
    return found


def payload(data: bytes, found: tuple[str, int, int, int]) -> bytes:
    _, start, size, header = found
    return data[start + header:start + size]


class Heif:
    """HEIC de libheif décomposé : `ftyp`, boîtes de `meta` lues ou à récrire, données qui suivent."""

    def __init__(self, data: bytes):
        top = boxes(data)
        assert [kind for kind, *_ in top[:2]] == ["ftyp", "meta"]
        self.brands = payload(data, top[0])
        self.data = data
        self.meta_end = top[1][1] + top[1][2]
        self.tail = data[self.meta_end:]
        meta = payload(data, top[1])
        self.children = [(kind, meta[start + header:start + size]) for kind, start, size, header in boxes(meta, 4)]
        self.items = self._items()
        self.references = self._references()
        self.locations = self._locations()
        self.properties, self.associations, self.ipma_version, self.ipma_flags = self._properties()
        self.primary = struct.unpack(">H", self.child("pitm")[4:6])[0]
        self.idat = self.child("idat")[0:] if self.has("idat") else b""
        self.groups: list[bytes] = []

    def has(self, kind: str) -> bool:
        return any(name == kind for name, _ in self.children)

    def child(self, kind: str) -> bytes:
        return next(body for name, body in self.children if name == kind)

    def _items(self) -> list[list]:
        body = self.child("iinf")
        start = 4 + (2 if body[0] == 0 else 4)
        items = []
        for kind, position, size, header in boxes(body, start):
            infe = body[position + header:position + size]
            assert infe[0] == 2
            flags = int.from_bytes(infe[1:4], "big")
            item, protection = struct.unpack(">HH", infe[4:8])
            items.append([item, infe[8:12].decode("latin-1"), flags, infe[12:]])
        return items

    def _references(self) -> list[tuple[str, int, list[int]]]:
        if not self.has("iref"):
            return []
        body = self.child("iref")
        assert body[0] == 0
        found = []
        for kind, position, size, header in boxes(body, 4):
            content = body[position + header:position + size]
            source, count = struct.unpack(">HH", content[:4])
            found.append((kind, source, list(struct.unpack(">" + "H" * count, content[4:4 + 2 * count]))))
        return found

    def _locations(self) -> list[list]:
        body = self.child("iloc")
        version = body[0]
        offset_size, length_size = body[4] >> 4, body[4] & 15
        base_size, index_size = body[5] >> 4, (body[5] & 15) if version else 0
        position = 6
        count_size = 2 if version < 2 else 4
        count = int.from_bytes(body[position:position + count_size], "big")
        position += count_size

        def value(size: int) -> int:
            nonlocal position
            result = int.from_bytes(body[position:position + size], "big") if size else 0
            position += size
            return result

        locations = []
        for _ in range(count):
            item = value(2 if version < 2 else 4)
            method = value(2) & 15 if version else 0
            reference = value(2)
            base = value(base_size)
            extents = []
            for _ in range(value(2)):
                value(index_size)
                offset = value(offset_size)
                extents.append([base + offset, value(length_size)])
            locations.append([item, method, reference, extents])
        return locations

    def _properties(self):
        iprp = self.child("iprp")
        inner = {kind: iprp[position + header:position + size] for kind, position, size, header in boxes(iprp)}
        ipco = inner["ipco"]
        properties = [ipco[position:position + size] for kind, position, size, header in boxes(ipco)]
        ipma = inner["ipma"]
        version, flags = ipma[0], int.from_bytes(ipma[1:4], "big")
        count = struct.unpack(">I", ipma[4:8])[0]
        position, associations = 8, {}
        for _ in range(count):
            item_size = 2 if version < 1 else 4
            item = int.from_bytes(ipma[position:position + item_size], "big")
            position += item_size
            entries = []
            for _ in range(ipma[position]):
                width = 2 if flags & 1 else 1
                raw = int.from_bytes(ipma[position + 1:position + 1 + width], "big")
                essential = raw >> (8 * width - 1)
                entries.append((raw & ((1 << (8 * width - 1)) - 1), bool(essential)))
                position += width
            position += 1
            associations[item] = entries
        return properties, associations, version, flags

    # ── Écriture ──

    def _iinf(self) -> bytes:
        entries = b"".join(
            full_box("infe", 2, flags, struct.pack(">HH", item, 0) + kind.encode("latin-1") + rest)
            for item, kind, flags, rest in self.items
        )
        return full_box("iinf", 0, 0, struct.pack(">H", len(self.items)) + entries)

    def _iref(self) -> bytes:
        children = b"".join(
            box(kind, struct.pack(">HH", source, len(targets)) + struct.pack(">" + "H" * len(targets), *targets))
            for kind, source, targets in self.references
        )
        return full_box("iref", 0, 0, children)

    def _iloc(self, delta: int) -> bytes:
        """Version 1, positions et longueurs sur 4 octets, sans base : [delta] ajouté aux données du fichier."""
        entries = b""
        for item, method, reference, extents in self.locations:
            entries += struct.pack(">HHHH", item, method, reference, len(extents))
            for offset, length in extents:
                entries += struct.pack(">II", offset + (delta if method == 0 else 0), length)
        return full_box("iloc", 1, 0, bytes([0x44, 0x00]) + struct.pack(">H", len(self.locations)) + entries)

    def _iprp(self) -> bytes:
        ipco = box("ipco", b"".join(self.properties))
        wide = self.ipma_flags & 1
        entries = b""
        for item in sorted(self.associations):
            entries += struct.pack(">H" if self.ipma_version < 1 else ">I", item)
            entries += bytes([len(self.associations[item])])
            for index, essential in self.associations[item]:
                if wide:
                    entries += struct.pack(">H", (0x8000 if essential else 0) | index)
                else:
                    entries += bytes([(0x80 if essential else 0) | index])
        ipma = full_box("ipma", self.ipma_version, self.ipma_flags, struct.pack(">I", len(self.associations)) + entries)
        return box("iprp", ipco + ipma)

    def _meta(self, delta: int) -> bytes:
        written = {
            "iinf": self._iinf(),
            "iref": self._iref(),
            "iloc": self._iloc(delta),
            "iprp": self._iprp(),
        }
        children = b""
        for kind, body in self.children:
            if kind in written:
                children += written.pop(kind)
            elif kind != "idat":
                children += box(kind, body)
        children += b"".join(written.values())
        if self.idat:
            children += box("idat", self.idat)
        if self.groups:
            children += box("grpl", b"".join(self.groups))
        return full_box("meta", 0, 0, children)

    def write(self, after: bytes = b"") -> bytes:
        """Fichier récrit : `ftyp`, `meta`, puis les données d'origine et [after], positions décalées."""
        ftyp = box("ftyp", self.brands)
        old = self.meta_end
        size = len(ftyp) + len(self._meta(0))
        return ftyp + self._meta(size - old) + self.tail + after


def add_brand(heif: Heif, brand: str) -> None:
    heif.brands += brand.encode("latin-1")


# ── Traîne Samsung ──────────────────────────────────────────────────────────────────────


def samsung_trailer(entries: list[tuple[int, str, bytes]]) -> bytes:
    """Blocs (marqueur 0, type, longueur du nom, nom, données), puis `SEFH`, sa longueur et `SEFT`."""
    blocks, placed = b"", []
    for kind, name, data in entries:
        block = struct.pack("<HHI", 0, kind, len(name)) + name.encode("ascii") + data
        placed.append((kind, len(blocks), len(block)))
        blocks += block
    directory = b"SEFH" + struct.pack("<II", 106, len(entries))
    for kind, start, size in placed:
        directory += struct.pack("<HHII", 0, kind, len(blocks) - start, size)
    return blocks + directory + struct.pack("<I", len(directory)) + b"SEFT"


def video(temp: pathlib.Path, frames: int = 4) -> bytes:
    """Petite vidéo HEVC de FFmpeg (64 × 48), `moov` en tête."""
    output = temp / f"video-{frames}.mp4"
    subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            f"testsrc=size=64x48:rate=10:duration={frames / 10}", "-c:v", "libx265", "-x265-params", "log-level=error",
            "-tag:v", "hvc1", "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(output),
        ],
        check=True,
    )
    return output.read_bytes()


def samsung(target: pathlib.Path, temp: pathlib.Path, orientation: int) -> None:
    trailer = samsung_trailer(
        [
            (0x0A01, "Image_UTC_Data", b"1729936800000"),
            (0x0AA1, "MCC_Data", b"208"),
            (0x0C61, "Camera_Capture_Mode_Info", b"1"),
            (0x0A30, "MotionPhoto_Data", video(temp)),
        ]
    )
    (target / f"samsung-{orientation}.heic").write_bytes(heic_bytes(orientation) + trailer)


# ── Photo animée : image fixe et séquence ────────────────────────────────────────────────


def rebase_chunks(moov: bytes, shift: int) -> bytes:
    """`moov` dont les positions `stco` / `co64` sont décalées de [shift]."""
    data = bytearray(moov)

    def walk(start: int, end: int) -> None:
        for kind, position, size, header in boxes(bytes(data), start, end):
            if kind in ("trak", "mdia", "minf", "stbl"):
                walk(position + header, position + size)
            elif kind in ("stco", "co64"):
                width = 4 if kind == "stco" else 8
                content = position + header
                count = struct.unpack(">I", data[content + 4:content + 8])[0]
                for index in range(count):
                    at = content + 8 + index * width
                    value = int.from_bytes(data[at:at + width], "big") + shift
                    data[at:at + width] = value.to_bytes(width, "big")

    walk(8, len(data))
    return bytes(data)


def animated(target: pathlib.Path, temp: pathlib.Path) -> None:
    mp4 = video(temp, frames=6)
    top = {kind: (position, size) for kind, position, size, _ in boxes(mp4)}
    moov_start, moov_size = top["moov"]
    mdat_start, mdat_size = top["mdat"]
    heif = Heif(heic_bytes(1))
    add_brand(heif, "msf1")
    still = heif.write()
    moov = mp4[moov_start:moov_start + moov_size]
    # Données de la vidéo juste après sa moov : leurs positions, rapportées à ce fichier.
    moov = rebase_chunks(moov, len(still) + moov_size - mdat_start)
    (target / "animated-1.heic").write_bytes(still + moov + mp4[mdat_start:mdat_start + mdat_size])


# ── Carte de gain ISO 21496-1 ────────────────────────────────────────────────────────────


def gain_map_metadata() -> bytes:
    """Métadonnées de gain (ISO 21496-1, version 0) : un canal, marge HDR de 1,3, gamma 1."""
    data = bytes([0]) + struct.pack(">HH", 0, 0) + bytes([0x40])
    data += struct.pack(">IIII", 0, 1, 13, 10)
    data += struct.pack(">iIiIIIiIiI", 0, 1, 13, 10, 1, 1, 1, 64, 1, 64)
    return data


def tone_mapped(target: pathlib.Path, name: str, orientation: int, gain_oriented: bool) -> None:
    gain = Image.new("L", (32, 24), 96)
    heif = Heif(heic_bytes(orientation, extra=[gain]))
    thumbnails = {source for kind, source, _ in heif.references if kind == "thmb"}
    gain_id = next(item for item, kind, *_ in heif.items if kind == "hvc1" and item != heif.primary and item not in thumbnails)
    tmap_id = max(item for item, *_ in heif.items) + 1
    for entry in heif.items:
        if entry[0] == gain_id:
            entry[2] = 1  # image de gain cachée
    heif.items.append([tmap_id, "tmap", 0, b"\x00"])
    heif.references.append(("dimg", tmap_id, [heif.primary, gain_id]))
    heif.locations.append([tmap_id, 1, 0, [[len(heif.idat), len(gain_map_metadata())]]])
    heif.idat += gain_map_metadata()
    # Couleurs, taille et orientation de l'image principale : partagées avec l'image tmap.
    shared = [(index, essential) for index, essential in heif.associations[heif.primary]
              if heif.properties[index - 1][4:8] in (b"colr", b"ispe", b"irot", b"imir")]
    heif.associations[tmap_id] = shared
    if gain_oriented:
        heif.associations[gain_id] += [(index, True) for index, _ in shared if heif.properties[index - 1][4:8] in (b"irot", b"imir")]
    group_id = tmap_id + 1
    heif.groups.append(full_box("altr", 0, 0, struct.pack(">III", group_id, 2, tmap_id) + struct.pack(">I", heif.primary)))
    add_brand(heif, "tmap")
    (target / name).write_bytes(heif.write())


def main(target: pathlib.Path, names: list[str]) -> None:
    target.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        temp = pathlib.Path(directory)
        writers = {f"orientation-{n}.heic": (lambda n=n: heic(target / f"orientation-{n}.heic", n)) for n in (1, 3, 6, 8, 7)}
        writers["grid-6.heic"] = lambda: heic(target / "grid-6.heic", 6, tile_size=32)
        writers["plain.heic"] = lambda: heic(target / "plain.heic", 1, metadata=False)
        writers["plain.avif"] = lambda: avif(target, temp)
        for n in (1, 6):
            writers[f"samsung-{n}.heic"] = lambda n=n: samsung(target, temp, n)
            writers[f"tmap-{n}.heic"] = lambda n=n: tone_mapped(target, f"tmap-{n}.heic", n, gain_oriented=False)
        writers["tmap-gain-6.heic"] = lambda: tone_mapped(target, "tmap-gain-6.heic", 6, gain_oriented=True)
        writers["animated-1.heic"] = lambda: animated(target, temp)
        unknown = set(names) - set(writers)
        if unknown:
            sys.exit(f"Fichiers inconnus : {', '.join(sorted(unknown))}")
        for name, write in writers.items():
            if not names or name in names:
                write()


def avif(target: pathlib.Path, temp: pathlib.Path) -> None:
    png = temp / "quadrants.png"
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
    main(pathlib.Path(sys.argv[1]), sys.argv[2:])
