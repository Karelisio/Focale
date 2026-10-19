#!/usr/bin/env python3
"""
Construit assets/geo/cities.bin, la base de géocodage inverse hors ligne de Focale.

Source : « cities.json » (https://github.com/lutangar/cities.json), dérivé de GeoNames
(villes de plus de 1 000 habitants) — licence CC BY 4.0 (https://www.geonames.org/).

Format binaire (big-endian) :
    magic   4 octets  b"FCG1"
    count   int32
    puis, pour chaque lieu :
        lat     int32  (degrés × 1e5)
        lon     int32  (degrés × 1e5)
        country 2 octets ASCII (ISO 3166-1 alpha-2)
        len     uint8  longueur du nom en octets
        name    UTF-8

Usage : python3 build_cities.py cities.json ../../app/src/main/assets/geo/cities.bin
"""
import json
import struct
import sys


def main(source: str, target: str) -> None:
    with open(source, encoding="utf-8") as handle:
        places = json.load(handle)
    rows = []
    for place in places:
        name = place["name"].strip()
        country = place["country"].strip().upper()
        if not name or len(country) != 2:
            continue
        encoded = name.encode("utf-8")[:255]
        rows.append((round(float(place["lat"]) * 1e5), round(float(place["lng"]) * 1e5), country, encoded))
    rows.sort(key=lambda r: (r[0], r[1]))
    with open(target, "wb") as out:
        out.write(b"FCG1")
        out.write(struct.pack(">i", len(rows)))
        for lat, lon, country, encoded in rows:
            out.write(struct.pack(">ii", lat, lon))
            out.write(country.encode("ascii"))
            out.write(struct.pack(">B", len(encoded)))
            out.write(encoded)
    print(f"{len(rows)} lieux écrits dans {target}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
