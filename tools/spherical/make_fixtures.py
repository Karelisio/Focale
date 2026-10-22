#!/usr/bin/env python3
"""
Vidéos 360° de test de la détection des métadonnées sphériques (app/src/test/resources/spherical).

Vrais fichiers MP4 : vidéo H.264 de 64 × 32 px encodée par FFmpeg (libx264), métadonnées
sphériques ajoutées comme le fait l'outil « Spatial Media » de Google, puis vérifiées par
ffprobe, qui les lit indépendamment de Focale. Le fichier V2 « ffmpeg » est réécrit par FFmpeg
lui-même (`-c copy -strict unofficial`) à partir des métadonnées injectées : ce sont alors les
boîtes de son propre muxeur.

  python3 tools/spherical/make_fixtures.py app/src/test/resources/spherical

- plain.mp4 : aucune métadonnée sphérique, `moov` avant `mdat` (faststart) ;
- v1-mono.mp4 : Spherical Video V1, boîte `uuid` (XMP GSpherical) dans la piste vidéo,
  équirectangulaire mono, cap initial de 90° et inclinaison de -10°, `moov` en fin de fichier ;
- v1-top-bottom.mp4 : V1 stéréoscopique haut-bas, recadrée (zone de 180° × 90° sur un panorama
  de 3840 × 1920) ;
- v2-equi.mp4 : Spherical Video V2, `st3d` (mono) et `sv3d` (`svhd`, `proj` : `prhd` cap 45°,
  `equi` bornée à ±60° de latitude) dans l'entrée `avc1`, `moov` avant `mdat` (décalages `stco`
  recalculés) ;
- v2-ffmpeg-left-right.mp4 : V2 stéréoscopique gauche-droite, sphère complète, réécrite par FFmpeg ;
- v2-cubemap.mp4 : V2 en projection cubique (`cbmp`), que la visionneuse ne propose pas ;
- v1-not-spherical.mp4 : boîte V1 dont `Spherical` vaut « false ».
"""
import json
import pathlib
import struct
import subprocess
import sys
import tempfile

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


def encode(path: pathlib.Path, faststart: bool) -> None:
    command = [
        "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=64x32:rate=10", "-t", "0.3",
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


def v2(source: bytes, stereo: int, projection: bytes, yaw: float = 0.0) -> bytes:
    mp4 = Mp4(source)
    prhd = full_box(b"prhd", fixed(yaw) + fixed(0) + fixed(0))
    sv3d = box(b"sv3d", full_box(b"svhd", b"Focale fixtures\0") + box(b"proj", prhd + projection))
    mp4.insert(mp4.sample_entry(mp4.video_track()), full_box(b"st3d", bytes([stereo])) + sv3d)
    return bytes(mp4.data)


def equi(top: float = 0.0, bottom: float = 0.0, left: float = 0.0, right: float = 0.0) -> bytes:
    return full_box(b"equi", bound(top) + bound(bottom) + bound(left) + bound(right))


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
        (target / "v2-cubemap.mp4").write_bytes(v2(fast, stereo=0, projection=full_box(b"cbmp", struct.pack(">II", 0, 0))))
        injected = work / "left-right.mp4"
        injected.write_bytes(v2(end, stereo=2, projection=equi()))
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(injected), "-c", "copy", "-strict", "unofficial", "-map_metadata", "-1",
             "-fflags", "+bitexact", str(target / "v2-ffmpeg-left-right.mp4")],
            check=True,
        )

    # Vérification par un lecteur indépendant : ffprobe voit les mêmes métadonnées (V2 seulement,
    # il ne lit pas les boîtes V1).
    checks = {
        "plain.mp4": lambda data: "Spherical Mapping" not in data,
        # Bornée : « tiled equirectangular » pour FFmpeg.
        "v2-equi.mp4": lambda data: data["Spherical Mapping"]["projection"] == "tiled equirectangular"
        and data["Spherical Mapping"]["yaw"] == 45 and data["Spherical Mapping"]["bound_top"] > 0,
        "v2-cubemap.mp4": lambda data: data["Spherical Mapping"]["projection"] == "cubemap",
        "v2-ffmpeg-left-right.mp4": lambda data: data["Spherical Mapping"]["projection"] == "equirectangular"
        and data["Stereo 3D"]["type"] == "side by side",
    }
    for name, check in checks.items():
        data = probe(target / name)
        if not check(data):
            raise SystemExit(f"{name} : métadonnées inattendues pour ffprobe : {data}")
        print(f"{name} : {sorted(data)}")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]))
