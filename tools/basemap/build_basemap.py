#!/usr/bin/env python3
"""Construit le fond de carte hors ligne de Focale (assets/geo/basemap.bin).

Source : Natural Earth (domaine public), https://www.naturalearthdata.com
via https://github.com/nvkelso/natural-earth-vector (dossier geojson/).

Fichiers attendus dans le dossier d'entrée :
  ne_110m_land, ne_50m_land, ne_50m_lakes,
  ne_110m_admin_0_boundary_lines_land, ne_50m_admin_0_boundary_lines_land,
  ne_110m_admin_0_countries (étiquettes des pays)  — tous en .geojson

Format FBM1 (gros-boutiste) :
  "FBM1", u8 nombre de couches
  couche : u8 type (0 terre, 1 lac, 2 frontière), u8 détail (0 faible, 1 fort),
           u32 nombre d'anneaux ; anneau : u32 nombre de points, puis pour chaque
           point la latitude et la longitude en 1e-5 degré, codées en écarts zigzag
           varint par rapport au point précédent
  u32 nombre d'étiquettes ; étiquette : i32 latitude, i32 longitude (1e-5 degré),
           u8 zoom minimal, nom anglais et nom français (u16 longueur + UTF-8)

Usage : python3 build_basemap.py <dossier_geojson> <sortie.bin>
"""
import json
import os
import struct
import sys

LAND, LAKE, BORDER = 0, 1, 2
LOW, HIGH = 0, 1
SCALE = 100_000


def rings_of(geometry):
    if geometry is None:
        return []
    kind, coords = geometry["type"], geometry["coordinates"]
    if kind == "Polygon":
        return list(coords)
    if kind == "MultiPolygon":
        return [ring for polygon in coords for ring in polygon]
    if kind == "LineString":
        return [coords]
    if kind == "MultiLineString":
        return list(coords)
    return []


def simplify(points, tolerance):
    """Douglas-Peucker itératif (degrés) ; conserve toujours les extrémités."""
    if tolerance <= 0 or len(points) < 3:
        return points
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        start, end = stack.pop()
        (x1, y1), (x2, y2) = points[start][:2], points[end][:2]
        dx, dy = x2 - x1, y2 - y1
        norm = (dx * dx + dy * dy) ** 0.5
        best, index = -1.0, -1
        for i in range(start + 1, end):
            px, py = points[i][:2]
            if norm == 0:
                dist = ((px - x1) ** 2 + (py - y1) ** 2) ** 0.5
            else:
                dist = abs(dy * px - dx * py + x2 * y1 - y2 * x1) / norm
            if dist > best:
                best, index = dist, i
        if best > tolerance:
            keep[index] = True
            stack.append((start, index))
            stack.append((index, end))
    return [p for p, k in zip(points, keep) if k]


def load(folder, name):
    with open(os.path.join(folder, name + ".geojson"), encoding="utf-8") as handle:
        return json.load(handle)["features"]


def varint(value):
    zigzag = (value << 1) ^ (value >> 63)
    out = bytearray()
    while True:
        byte = zigzag & 0x7F
        zigzag >>= 7
        if zigzag:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def encode_ring(points):
    out = bytearray(struct.pack(">I", len(points)))
    last_lat = last_lon = 0
    for lon, lat in (p[:2] for p in points):
        lat_i, lon_i = round(lat * SCALE), round(lon * SCALE)
        out += varint(lat_i - last_lat) + varint(lon_i - last_lon)
        last_lat, last_lon = lat_i, lon_i
    return bytes(out)


def layer(features, kind, detail, tolerance, min_points):
    rings = []
    for feature in features:
        for ring in rings_of(feature["geometry"]):
            simplified = simplify(ring, tolerance)
            if len(simplified) >= min_points:
                rings.append(simplified)
    body = bytearray(struct.pack(">BBI", kind, detail, len(rings)))
    for ring in rings:
        body += encode_ring(ring)
    points = sum(len(r) for r in rings)
    print(f"couche type={kind} détail={detail} : {len(rings)} anneaux, {points} points", file=sys.stderr)
    return bytes(body)


def utf(text):
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def main(folder, output):
    layers = [
        layer(load(folder, "ne_110m_land"), LAND, LOW, 0.0, 4),
        layer(load(folder, "ne_110m_admin_0_boundary_lines_land"), BORDER, LOW, 0.0, 2),
        layer(load(folder, "ne_50m_land"), LAND, HIGH, 0.01, 4),
        layer(load(folder, "ne_50m_lakes"), LAKE, HIGH, 0.01, 4),
        layer(load(folder, "ne_50m_admin_0_boundary_lines_land"), BORDER, HIGH, 0.01, 2),
    ]
    labels = bytearray()
    countries = load(folder, "ne_110m_admin_0_countries")
    for country in countries:
        props = country["properties"]
        labels += struct.pack(
            ">iiB",
            round(props["LABEL_Y"] * SCALE),
            round(props["LABEL_X"] * SCALE),
            int(props.get("MIN_LABEL") or 3),
        )
        labels += utf(props.get("NAME_EN") or props["NAME"]) + utf(props.get("NAME_FR") or props["NAME"])
    with open(output, "wb") as handle:
        handle.write(b"FBM1" + struct.pack(">B", len(layers)))
        for body in layers:
            handle.write(body)
        handle.write(struct.pack(">I", len(countries)) + bytes(labels))
    print(f"{output} : {os.path.getsize(output)} octets, {len(countries)} étiquettes", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
