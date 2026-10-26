#!/usr/bin/env python3
"""
Photos et vidéos 360° de test (app/src/test/resources/spherical).

Vrais fichiers MP4 : vidéo H.264 encodée par FFmpeg (libx264), métadonnées sphériques ajoutées
comme le fait l'outil « Spatial Media » de Google, puis vérifiées par ffprobe, qui les lit
indépendamment de Focale. Les fichiers « ffmpeg » sont réécrits par FFmpeg lui-même
(`-c copy -strict unofficial`) à partir des métadonnées injectées : ce sont alors les boîtes de
son propre muxeur.

  python3 tools/spherical/make_fixtures.py app/src/test/resources/spherical

Les images des cubes viennent du filtre `v360` de FFmpeg, à partir d'une image équirectangulaire
dont chaque pixel a pour couleur sa direction (R, G, B = 128 + 127 × (x, y, z), x à droite, y
vers le zénith, -z devant) : un test retrouve la direction de n'importe quel point d'une face.

- plain.mp4 : aucune métadonnée sphérique, `moov` avant `mdat` (faststart) ;
- v1-mono.mp4 : Spherical Video V1, boîte `uuid` (XMP GSpherical) dans la piste vidéo,
  équirectangulaire mono, cap initial de 90° et inclinaison de -10°, `moov` en fin de fichier ;
- v1-top-bottom.mp4 : V1 stéréoscopique haut-bas, recadrée (zone de 180° × 90° sur un panorama
  de 3840 × 1920) ;
- v1-not-spherical.mp4 : boîte V1 dont `Spherical` vaut « false » ;
- v2-equi.mp4 : Spherical Video V2, `st3d` (mono) et `sv3d` (`svhd`, `proj` : `prhd` cap 45°,
  `equi` bornée à ±60° de latitude) dans l'entrée `avc1`, `moov` avant `mdat` (décalages `stco`
  recalculés) ;
- v2-ffmpeg-left-right.mp4 : V2 stéréoscopique gauche-droite, sphère complète, réécrite par FFmpeg ;
- v2-equi-pose-ffmpeg.mp4 : V2 équirectangulaire, pose cap -20°, inclinaison 12,5°, roulis
  7,25° (16.16), réécrite par FFmpeg ;
- v2-cubemap.mp4 : V2 en cube (`cbmp`, disposition 0, sans marge), images `v360` `c3x2` de
  96 × 64 px (cube.png) ;
- v2-cubemap-padded-ffmpeg.mp4 : cube avec 4 px de marge autour de chaque face (cube-pad.png),
  pose cap 30°, inclinaison 10°, roulis -5°, réécrite par FFmpeg ;
- v2-cubemap-layout1.mp4 : `cbmp` de disposition 1, inconnue (FFmpeg la refuse aussi) ;
- v2-mesh-eac.mp4 : V2 en maillage (`mshp`, `dfl8` : deflate brut) décrivant un cube
  équi-angulaire comme ceux de YouTube, sur des images `v360` `eac` (eac.png, marges de 2 px
  comprises) : faces en triangles et en bandes ; FFmpeg ne lit pas `mshp` ;
- v2-mesh-stereo.mp4 : V2 stéréoscopique « propre au fichier » (`st3d` 3), `mshp` non compressé
  (`raw `) à deux maillages équirectangulaires de rayon 2,5 (œil gauche en haut de l'image, œil
  droit en bas), calottes polaires en éventails, plus une liste d'une image fixe (texture 1) ;
- cube.png, cube-pad.png, eac.png : images (RVB sans perte) des vidéos précédentes ;
- photo-pose.jpg : photo 360° de 256 × 128 px (JPEG), GPano équirectangulaire, cap 68,8°,
  inclinaison 12°, roulis -7°, sans vue initiale (comme une RICOH THETA sans redressement).
"""
import json
import math
import pathlib
import struct
import subprocess
import sys
import tempfile
import zlib

V1_UUID = bytes.fromhex("ffcc8263f8554a938814587a02521fdd")

V1_XML = (
    '<?xml version="1.0"?><rdf:SphericalVideo xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" '
    'xmlns:GSpherical="http://ns.google.com/videos/1.0/spherical/">'
    "<GSpherical:Spherical>{spherical}</GSpherical:Spherical>"
    "<GSpherical:Stitched>true</GSpherical:Stitched>"
    "<GSpherical:StitchingSoftware>Focale fixtures</GSpherical:StitchingSoftware>"
    "<GSpherical:ProjectionType>equirectangular</GSpherical:ProjectionType>"
    "{extra}</rdf:SphericalVideo>"
)

GPANO_XMP = (
    '<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">'
    '<rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" '
    'GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True" '
    'GPano:CroppedAreaImageWidthPixels="256" GPano:CroppedAreaImageHeightPixels="128" '
    'GPano:FullPanoWidthPixels="256" GPano:FullPanoHeightPixels="128" '
    'GPano:CroppedAreaLeftPixels="0" GPano:CroppedAreaTopPixels="0" '
    'GPano:PoseHeadingDegrees="68.8" GPano:PosePitchDegrees="12.0" GPano:PoseRollDegrees="-7.0"/>'
    "</rdf:RDF></x:xmpmeta>"
)

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


def box(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full_box(kind: bytes, payload: bytes, version: int = 0, flags: int = 0) -> bytes:
    return box(kind, struct.pack(">I", (version << 24) | flags) + payload)


def boxes(data: bytes, start: int, end: int):
    """(type, début, fin) des boîtes de data[start:end]."""
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        if size < 8:
            raise ValueError("boîte invalide")
        yield kind, position, position + size
        position += size


def encode(path: pathlib.Path, faststart: bool, source: pathlib.Path | None = None, size: str = "64x32") -> None:
    """Vidéo H.264 de 0,3 s : mire de FFmpeg, ou image [source] répétée."""
    inputs = ["-loop", "1", "-framerate", "10", "-i", str(source)] if source else ["-f", "lavfi", "-i", f"testsrc=size={size}:rate=10"]
    command = [
        "ffmpeg", "-v", "error", "-y", *inputs, "-t", "0.3",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-bitexact", "-map_metadata", "-1",
        "-fflags", "+bitexact", "-flags:v", "+bitexact",
    ]
    if faststart:
        command += ["-movflags", "+faststart"]
    subprocess.run(command + [str(path)], check=True)


class Mp4:
    """Fichier MP4 en mémoire, modifié boîte par boîte (tailles des parents mises à jour)."""

    def __init__(self, data: bytes):
        self.data = bytearray(data)

    def find(self, path: list[bytes], start: int = 0, end: int | None = None):
        end = len(self.data) if end is None else end
        for kind, box_start, box_end in boxes(self.data, start, end):
            if kind != path[0]:
                continue
            if len(path) == 1:
                return [(box_start, box_end)]
            found = self.find(path[1:], box_start + 8 + self.offset(kind), box_end)
            if found is not None:
                return [(box_start, box_end)] + found
        return None

    @staticmethod
    def offset(kind: bytes) -> int:
        # stsd : boîte pleine et nombre d'entrées ; avc1 : champs de VisualSampleEntry.
        return {b"stsd": 8, b"avc1": 78}.get(kind, 0)

    def video_track(self):
        moov = self.find([b"moov"])[0]
        for kind, start, end in boxes(self.data, moov[0] + 8, moov[1]):
            if kind != b"trak":
                continue
            handler = self.find([b"mdia", b"hdlr"], start + 8, end)
            if handler and self.data[handler[-1][0] + 16:handler[-1][0] + 20] == b"vide":
                return [moov, (start, end)]
        raise ValueError("pas de piste vidéo")

    def insert(self, parents: list[tuple[int, int]], payload: bytes) -> None:
        """Ajoute [payload] à la fin du dernier de [parents], tous agrandis d'autant."""
        at = parents[-1][1]
        self.data[at:at] = payload
        for start, _ in parents:
            size = struct.unpack(">I", self.data[start:start + 4])[0]
            self.data[start:start + 4] = struct.pack(">I", size + len(payload))
        self.shift_chunks(at, len(payload))

    def shift_chunks(self, at: int, delta: int) -> None:
        """Décalages `stco` / `co64` des données placées après l'insertion (moov avant mdat)."""
        moov = self.find([b"moov"])[0]
        for kind, start, end in boxes(self.data, moov[0] + 8, moov[1]):
            if kind != b"trak":
                continue
            for table in (b"stco", b"co64"):
                found = self.find([b"mdia", b"minf", b"stbl", table], start + 8, end)
                if not found:
                    continue
                table_start = found[-1][0]
                count = struct.unpack(">I", self.data[table_start + 12:table_start + 16])[0]
                width = 4 if table == b"stco" else 8
                for index in range(count):
                    position = table_start + 16 + index * width
                    value = int.from_bytes(self.data[position:position + width], "big")
                    if value >= at:
                        self.data[position:position + width] = (value + delta).to_bytes(width, "big")

    def sample_entry(self, track):
        stsd = self.find([b"mdia", b"minf", b"stbl", b"stsd"], track[-1][0] + 8, track[-1][1])
        entry_start = stsd[-1][0] + 16
        size = struct.unpack(">I", self.data[entry_start:entry_start + 4])[0]
        return track + [(stsd[-4][0], stsd[-4][1]), (stsd[-3][0], stsd[-3][1]), (stsd[-2][0], stsd[-2][1]), (stsd[-1][0], stsd[-1][1]), (entry_start, entry_start + size)]


def v1(source: bytes, extra: str = "", spherical: str = "true") -> bytes:
    mp4 = Mp4(source)
    xml = V1_XML.format(spherical=spherical, extra=extra).encode()
    mp4.insert(mp4.video_track(), box(b"uuid", V1_UUID + xml))
    return bytes(mp4.data)


def fixed(value: float) -> bytes:
    return struct.pack(">i", round(value * 65536))


def bound(fraction: float) -> bytes:
    return struct.pack(">I", min(round(fraction * 2 ** 32), 2 ** 32 - 1))


def v2(source: bytes, stereo: int, projection: bytes, yaw: float = 0.0, pitch: float = 0.0, roll: float = 0.0) -> bytes:
    mp4 = Mp4(source)
    prhd = full_box(b"prhd", fixed(yaw) + fixed(pitch) + fixed(roll))
    sv3d = box(b"sv3d", full_box(b"svhd", b"Focale fixtures\0") + box(b"proj", prhd + projection))
    mp4.insert(mp4.sample_entry(mp4.video_track()), full_box(b"st3d", bytes([stereo])) + sv3d)
    return bytes(mp4.data)


def equi(top: float = 0.0, bottom: float = 0.0, left: float = 0.0, right: float = 0.0) -> bytes:
    return full_box(b"equi", bound(top) + bound(bottom) + bound(left) + bound(right))


def cbmp(layout: int, padding: int) -> bytes:
    return full_box(b"cbmp", struct.pack(">II", layout, padding))


class Bits:
    """Écriture bit à bit, poids fort d'abord (boîte `mesh`)."""

    def __init__(self):
        self.data = bytearray()
        self.used = 0

    def write(self, value: int, count: int) -> None:
        for bit in range(count - 1, -1, -1):
            if self.used % 8 == 0:
                self.data.append(0)
            if (value >> bit) & 1:
                self.data[-1] |= 0x80 >> (self.used % 8)
            self.used += 1

    def align(self) -> None:
        self.used = (self.used + 7) // 8 * 8


def zigzag(delta: int) -> int:
    return delta * 2 if delta >= 0 else -delta * 2 - 1


def size_bits(count: int) -> int:
    return math.ceil(math.log2(count * 2))


def mesh_box(vertices: list[tuple[float, float, float, float, float]], lists: list[tuple[int, int, list[int]]]) -> bytes:
    """Boîte `mesh` : sommets (x, y, z, u, v ; u, v d'OpenGL, origine en bas à gauche) et listes (texture, type, index)."""
    coordinates = sorted({struct.unpack(">f", struct.pack(">f", c))[0] for vertex in vertices for c in vertex})
    position = {value: index for index, value in enumerate(coordinates)}
    bits = Bits()
    bits.write(len(coordinates), 32)
    for value in coordinates:
        bits.write(struct.unpack(">I", struct.pack(">f", value))[0], 32)
    bits.write(len(vertices), 32)
    ccsb = size_bits(len(coordinates))
    previous = [0] * 5
    for vertex in vertices:
        for component, value in enumerate(vertex):
            index = position[struct.unpack(">f", struct.pack(">f", value))[0]]
            bits.write(zigzag(index - previous[component]), ccsb)
            previous[component] = index
    bits.align()
    bits.write(len(lists), 32)
    vcsb = size_bits(len(vertices))
    for texture, kind, indices in lists:
        bits.write(texture, 8)
        bits.write(kind, 8)
        bits.write(len(indices), 32)
        last = 0
        for index in indices:
            bits.write(zigzag(index - last), vcsb)
            last = index
        bits.align()
    return box(b"mesh", bytes(bits.data))


def mshp(meshes: list[bytes], encoding: bytes) -> bytes:
    payload = b"".join(meshes)
    if encoding == b"dfl8":
        compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
        payload = compressor.compress(payload) + compressor.flush()
    body = encoding + payload
    return full_box(b"mshp", struct.pack(">I", zlib.crc32(body)) + body)


# Cube équi-angulaire de FFmpeg (`v360`, `eac`, comme YouTube), repère de FFmpeg : x à droite,
# y vers le bas, z devant. Faces : droite, gauche, haut, bas, avant, arrière.
RIGHT, LEFT, UP, DOWN, FRONT, BACK = range(6)
EAC_FACE = {RIGHT: 2, LEFT: 0, UP: 5, DOWN: 3, FRONT: 1, BACK: 4}
EAC_ROTATION = {0: 0, 1: 0, 2: 0, 3: 3, 4: 1, 5: 3}  # quarts de tour, par case (prepare_eac_in)


def cube_point(direction: int, uf: float, vf: float) -> tuple[float, float, float]:
    """cube_to_xyz de FFmpeg, sans rotation de face."""
    return {
        RIGHT: (1.0, vf, -uf), LEFT: (-1.0, vf, uf), UP: (uf, -1.0, vf),
        DOWN: (uf, 1.0, -vf), FRONT: (uf, vf, 1.0), BACK: (-uf, vf, -1.0),
    }[direction]


def rotate_face(uf: float, vf: float, rotation: int) -> tuple[float, float]:
    """rotate_cube_face de FFmpeg."""
    if rotation == 1:
        return -vf, uf
    if rotation == 2:
        return -uf, -vf
    if rotation == 3:
        return vf, -uf
    return uf, vf


def eac_mesh(width: int, height: int, steps: int) -> bytes:
    """Maillage d'une image `eac` de FFmpeg (xyz_to_eac, marges de 2 px) : [steps] × [steps] cases par face."""
    u_pad, v_pad = 2 / width, 2 / height
    vertices, lists = [], []
    for direction in range(6):
        face = EAC_FACE[direction]
        first = len(vertices)
        for row in range(steps + 1):
            for column in range(steps + 1):
                # Pas égaux en angle : c'est ce qui fait un cube équi-angulaire.
                uf = math.tan(math.pi / 4 * (2 * column / steps - 1))
                vf = math.tan(math.pi / 4 * (2 * row / steps - 1))
                x, y, z = cube_point(direction, uf, vf)
                ru, rv = rotate_face(uf, vf, EAC_ROTATION[face])
                u = (2 / math.pi * math.atan(ru) + 0.5 + face % 3) * (1 - 2 * u_pad) / 3 + u_pad
                v = (2 / math.pi * math.atan(rv) + 0.5) * (0.5 - 2 * v_pad) + v_pad + 0.5 * (face // 3)
                # Repère OpenGL (y en haut, -z devant), coordonnées de texture d'OpenGL.
                vertices.append((x, -y, -z, u, 1 - v))

        def at(row, column):
            return first + row * (steps + 1) + column

        if direction in (RIGHT, LEFT):
            # Une bande par rangée.
            for row in range(steps):
                strip = []
                for column in range(steps + 1):
                    strip += [at(row, column), at(row + 1, column)]
                lists.append((0, 1, strip))
        else:
            triangles = []
            for row in range(steps):
                for column in range(steps):
                    triangles += [at(row, column), at(row + 1, column), at(row, column + 1)]
                    triangles += [at(row, column + 1), at(row + 1, column), at(row + 1, column + 1)]
            lists.append((0, 0, triangles))
    return mesh_box(vertices, lists)


def sphere_mesh(eye: int, radius: float, columns: int = 12, rows: int = 6, cap: float = 80.0) -> bytes:
    """Sphère équirectangulaire de l'œil [eye] (0 : moitié haute de l'image, 1 : basse), calottes en éventails."""

    def texture(longitude: float, latitude: float) -> tuple[float, float]:
        v = 1 - (90 - latitude) / 180  # dans l'image d'un œil, origine en bas
        return (longitude + 180) / 360, 0.5 + v / 2 if eye == 0 else v / 2

    def point(longitude: float, latitude: float):
        lon, lat = math.radians(longitude), math.radians(latitude)
        return (radius * math.cos(lat) * math.sin(lon), radius * math.sin(lat), -radius * math.cos(lat) * math.cos(lon)) + texture(longitude, latitude)

    vertices, lists = [], []
    for row in range(rows + 1):
        for column in range(columns + 1):
            vertices.append(point(-180 + 360 * column / columns, cap - 2 * cap * row / rows))
    triangles = []
    for row in range(rows):
        for column in range(columns):
            top = row * (columns + 1) + column
            triangles += [top, top + columns + 1, top + 1, top + 1, top + columns + 1, top + columns + 2]
    lists.append((0, 0, triangles))
    for pole, row in ((90.0, 0), (-90.0, rows)):
        apex = len(vertices)
        vertices.append(point(0.0, pole))
        lists.append((0, 2, [apex] + [row * (columns + 1) + column for column in range(columns + 1)]))
    # Image fixe à la place d'une partie non filmée : ignorée par Focale.
    lists.append((1, 0, [0, 1, columns + 1]))
    return mesh_box(vertices, lists)


def direction_image(path: pathlib.Path, width: int = 256, height: int = 128) -> None:
    """Image équirectangulaire dont chaque pixel a pour couleur sa direction."""
    pixels = bytearray()
    for row in range(height):
        latitude = math.radians(90 - (row + 0.5) / height * 180)
        for column in range(width):
            longitude = math.radians((column + 0.5) / width * 360 - 180)
            direction = (math.cos(latitude) * math.sin(longitude), math.sin(latitude), -math.cos(latitude) * math.cos(longitude))
            pixels += bytes(round(128 + 127 * c) for c in direction)
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{width}x{height}", "-i", "-",
         "-frames:v", "1", str(path)],
        input=bytes(pixels), check=True,
    )


def v360(source: pathlib.Path, target: pathlib.Path, options: str) -> None:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(source), "-vf", f"v360=input=e:{options}", "-pix_fmt", "rgb24",
         "-frames:v", "1", "-bitexact", str(target)],
        check=True,
    )


def jpeg_with_xmp(source: pathlib.Path, target: pathlib.Path, xmp: str) -> None:
    """JPEG de [source], paquet XMP dans un segment APP1 juste après SOI."""
    plain = target.with_suffix(".plain.jpg")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(source), "-q:v", "3", "-bitexact", str(plain)], check=True)
    data = plain.read_bytes()
    plain.unlink()
    payload = b"http://ns.adobe.com/xap/1.0/\0" + xmp.encode()
    target.write_bytes(data[:2] + b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload + data[2:])


def remux(source: pathlib.Path, target: pathlib.Path) -> None:
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(source), "-c", "copy", "-strict", "unofficial", "-map_metadata", "-1",
         "-fflags", "+bitexact", str(target)],
        check=True,
    )


def probe(path: pathlib.Path) -> dict:
    output = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream_side_data", "-of", "json", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout
    streams = json.loads(output).get("streams", [])
    return {item.get("side_data_type"): item for item in (streams[0].get("side_data_list", []) if streams else [])}


def main(target: pathlib.Path) -> None:
    target.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        work = pathlib.Path(work)
        encode(work / "end.mp4", faststart=False)
        encode(work / "fast.mp4", faststart=True)
        end = (work / "end.mp4").read_bytes()
        fast = (work / "fast.mp4").read_bytes()

        (target / "plain.mp4").write_bytes(fast)
        (target / "v1-mono.mp4").write_bytes(
            v1(end, "<GSpherical:StereoMode>mono</GSpherical:StereoMode>"
                    "<GSpherical:InitialViewHeadingDegrees>90</GSpherical:InitialViewHeadingDegrees>"
                    "<GSpherical:InitialViewPitchDegrees>-10</GSpherical:InitialViewPitchDegrees>"),
        )
        (target / "v1-top-bottom.mp4").write_bytes(
            v1(end, "<GSpherical:StereoMode>top-bottom</GSpherical:StereoMode>"
                    "<GSpherical:FullPanoWidthPixels>3840</GSpherical:FullPanoWidthPixels>"
                    "<GSpherical:FullPanoHeightPixels>1920</GSpherical:FullPanoHeightPixels>"
                    "<GSpherical:CroppedAreaImageWidthPixels>1920</GSpherical:CroppedAreaImageWidthPixels>"
                    "<GSpherical:CroppedAreaImageHeightPixels>960</GSpherical:CroppedAreaImageHeightPixels>"
                    "<GSpherical:CroppedAreaLeftPixels>960</GSpherical:CroppedAreaLeftPixels>"
                    "<GSpherical:CroppedAreaTopPixels>480</GSpherical:CroppedAreaTopPixels>"),
        )
        (target / "v1-not-spherical.mp4").write_bytes(v1(end, spherical="false"))
        # Bornes de 1/6 en haut et en bas : de +60° à -60° de latitude.
        (target / "v2-equi.mp4").write_bytes(v2(fast, stereo=0, projection=equi(top=1 / 6, bottom=1 / 6), yaw=45))
        injected = work / "left-right.mp4"
        injected.write_bytes(v2(end, stereo=2, projection=equi()))
        remux(injected, target / "v2-ffmpeg-left-right.mp4")
        posed = work / "posed.mp4"
        posed.write_bytes(v2(fast, stereo=0, projection=equi(), yaw=-20, pitch=12.5, roll=7.25))
        remux(posed, target / "v2-equi-pose-ffmpeg.mp4")

        # Cubes et maillages : images v360 de FFmpeg, puis vidéos de ces images.
        directions = work / "directions.png"
        direction_image(directions)
        v360(directions, target / "cube.png", "output=c3x2:w=96:h=64")
        # Marge fixe de FFmpeg (fout_pad) : la moitié de chaque côté, 4 px par bord de face.
        v360(directions, target / "cube-pad.png", "output=c3x2:w=96:h=64:fout_pad=8")
        v360(directions, target / "eac.png", "output=eac:w=96:h=64")
        for name in ("cube", "cube-pad", "eac"):
            encode(work / f"{name}.mp4", faststart=True, source=target / f"{name}.png")
        cube = (work / "cube.mp4").read_bytes()
        (target / "v2-cubemap.mp4").write_bytes(v2(cube, stereo=0, projection=cbmp(0, 0)))
        (target / "v2-cubemap-layout1.mp4").write_bytes(v2(cube, stereo=0, projection=cbmp(1, 0)))
        padded = work / "padded.mp4"
        padded.write_bytes(v2((work / "cube-pad.mp4").read_bytes(), stereo=0, projection=cbmp(0, 4), yaw=30, pitch=10, roll=-5))
        remux(padded, target / "v2-cubemap-padded-ffmpeg.mp4")
        (target / "v2-mesh-eac.mp4").write_bytes(
            v2((work / "eac.mp4").read_bytes(), stereo=0, projection=mshp([eac_mesh(96, 64, steps=8)], b"dfl8")),
        )
        encode(work / "stereo.mp4", faststart=True, size="64x64")
        (target / "v2-mesh-stereo.mp4").write_bytes(
            v2((work / "stereo.mp4").read_bytes(), stereo=3, projection=mshp([sphere_mesh(0, 2.5), sphere_mesh(1, 2.5)], b"raw ")),
        )
        jpeg_with_xmp(directions, target / "photo-pose.jpg", GPANO_XMP)

    # Vérification par un lecteur indépendant : ffprobe voit les mêmes métadonnées (V2 seulement,
    # il ne lit ni les boîtes V1 ni `mshp`). Il donne la pose en degrés entiers.
    def mapping(data: dict) -> dict:
        return data.get("Spherical Mapping", {})

    checks = {
        "plain.mp4": lambda data: "Spherical Mapping" not in data,
        # Bornée : « tiled equirectangular » pour FFmpeg.
        "v2-equi.mp4": lambda data: mapping(data).get("projection") == "tiled equirectangular"
        and mapping(data)["yaw"] == 45 and mapping(data)["bound_top"] > 0,
        "v2-ffmpeg-left-right.mp4": lambda data: mapping(data).get("projection") == "equirectangular"
        and data["Stereo 3D"]["type"] == "side by side",
        "v2-equi-pose-ffmpeg.mp4": lambda data: mapping(data).get("projection") == "equirectangular"
        and (mapping(data)["yaw"], mapping(data)["pitch"], mapping(data)["roll"]) == (-20, 12, 7),
        "v2-cubemap.mp4": lambda data: mapping(data).get("projection") == "cubemap" and mapping(data)["padding"] == 0,
        "v2-cubemap-padded-ffmpeg.mp4": lambda data: mapping(data).get("projection") == "cubemap"
        and mapping(data)["padding"] == 4 and (mapping(data)["yaw"], mapping(data)["pitch"], mapping(data)["roll"]) == (30, 10, -5),
        "v2-cubemap-layout1.mp4": lambda data: "Spherical Mapping" not in data,
        "v2-mesh-eac.mp4": lambda data: "Spherical Mapping" not in data,
    }
    for name, check in checks.items():
        data = probe(target / name)
        if not check(data):
            raise SystemExit(f"{name} : métadonnées inattendues pour ffprobe : {data}")
        print(f"{name} : {sorted(data)}")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
