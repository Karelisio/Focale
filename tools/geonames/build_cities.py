#!/usr/bin/env python3
"""
Construit assets/geo/cities.bin, la base de géocodage inverse hors ligne de Focale.

Lieux : « cities.json » (https://github.com/lutangar/cities.json), dérivé de GeoNames
(villes de plus de 1 000 habitants) — licence CC BY 4.0 (https://www.geonames.org/).

Noms français et anglais : noms alternatifs de GeoNames (alternateNamesV2, CC BY 4.0), lus dans
la base « database-v1.bin » d'Open-Meteo (https://github.com/open-meteo/geocoding-api, publication
0.5.0-alpha.1, décompressée par `zstd -d`), compilée depuis allCountries et alternateNamesV2 de
GeoNames : un nom par langue et par lieu, préféré et court d'abord, sans les noms historiques ni
familiers. Seuls les noms qui diffèrent du nom GeoNames sont gardés, et pas n'importe lesquels :
  - français : tout nom différent, sauf une forme qualifiée qui contient le nom GeoNames
    (« Manta Ecuador », « Kabupaten de Cianjur ») et une simple perte d'accents (« Lanús » →
    « Lanus ») ; un ajout d'accents ou de traits d'union est gardé (« Port-Louis », « Édéa ») ;
  - aucun nom déjà porté par un autre lieu du même pays (« Halle (Saale) » → « Halle ») ;
  - anglais : le nom GeoNames est déjà la forme internationale (« Lisbon », « Munich ») ; un nom
    anglais n'est affiché que s'il s'accorde avec le français (« Köln » → « Cologne ») ou si le nom
    GeoNames n'est pas en alphabet latin. Les autres noms anglais de GeoNames, trop souvent des
    variantes locales ou anciennes pour l'affichage (« Puerto Montt » → « Port Montt »), servent
    d'alias pour la recherche seulement (« Makkah » : « Mecca », « Hannover » : « Hanover »), sauf
    une forme qualifiée ou une simple différence d'accents.
Le lieu est apparié par pays, nom et position (à 0,02° près). Deux lieux d'un même pays et de
même nom (l'application ne connaît que le nom et le pays) prennent les noms du plus peuplé.

Format binaire (big-endian) :
    magic   4 octets  b"FCG2"
    names   int32     nombre de noms traduits
    length  int32     taille en octets de la table qui suit
    puis, pour chaque nom traduit (triés par pays puis nom) :
        country 2 octets ASCII (ISO 3166-1 alpha-2)
        len     uint8, nom GeoNames en UTF-8 (tel qu'il figure parmi les lieux)
        len     uint8, nom français (0 : pas de nom français distinct)
        len     uint8, nom anglais affiché (0 : pas de nom anglais distinct)
        len     uint8, alias anglais, cherché mais jamais affiché (0 : aucun)
    count   int32
    puis, pour chaque lieu (format FCG1 inchangé) :
        lat     int32  (degrés × 1e5)
        lon     int32  (degrés × 1e5)
        country 2 octets ASCII
        len     uint8  longueur du nom en octets
        name    UTF-8

Usage : python3 -I build_cities.py cities.json ../../app/src/main/assets/geo/cities.bin [--names database-v1.bin]
"""
import argparse
import collections
import json
import math
import mmap
import struct
import unicodedata


def normalize(text: str) -> str:
    """Comme LabelDictionary.normalize : minuscules, sans accents, mots séparés d'une espace."""
    text = unicodedata.normalize("NFD", text.lower()).replace("œ", "oe").replace("æ", "ae")
    text = "".join(c for c in text if not unicodedata.combining(c))
    return " ".join("".join(c if "a" <= c <= "z" or "0" <= c <= "9" else " " for c in text).split())


def marks(text: str) -> int:
    """Accents, lettres hors ASCII, traits d'union et apostrophes."""
    return sum(1 for c in text if ord(c) > 127 or c in "-'’")


def latin(text: str) -> bool:
    return any("a" <= c <= "z" for c in normalize(text))


def french_name(name: str, french: str) -> str | None:
    if not french or french == name:
        return None
    a, b = normalize(name), normalize(french)
    if a == b:
        return french if marks(french) > marks(name) else None
    if len(b) > len(a) and f" {a} " in f" {b} ":
        return None
    return french


def english_alias(name: str, english: str) -> str | None:
    """Nom anglais différent du nom GeoNames au-delà des accents, hors forme qualifiée."""
    if not english:
        return None
    a, b = normalize(name), normalize(english)
    if a == b or not b or (len(b) > len(a) and f" {a} " in f" {b} "):
        return None
    return english


def english_name(name: str, english: str, french: str | None) -> str | None:
    """Nom anglais affiché : d'accord avec le français, ou nom GeoNames hors alphabet latin."""
    if english_alias(name, english) is None:
        return None
    if french and normalize(english) == normalize(french):
        return english
    return english if not latin(name) and latin(english) else None


def read_open_meteo(path: str):
    """Lieux habités (codes PPL*) : (pays, nom) → [(lat, lon, population, fr, en)]."""
    with open(path, "rb") as handle:
        data = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
    if data[:8] != b"GEOCDV1\0":
        raise SystemExit("base Open-Meteo inattendue")
    sections = {}
    for i in range(struct.unpack_from("<I", data, 48)[0]):
        kind, _, offset, length, count, stride, _, _ = struct.unpack_from("<IIQQQIIQ", data, 64 + 48 * i)
        sections[kind] = (offset, count, stride)

    def table(kind):
        offset, count, _ = sections[kind]
        values = []
        for _ in range(count):
            n = struct.unpack_from("<I", data, offset)[0]
            values.append(data[offset + 4:offset + 4 + n].decode("utf-8"))
            offset += 4 + n
        return values

    def string(kind, offset):
        base = sections[kind][0] + offset
        n = struct.unpack_from("<I", data, base)[0]
        return data[base + 4:base + 4 + n].decode("utf-8")

    features = table(26)
    languages = {code: i for i, code in enumerate(table(28))}
    fr, en = languages["fr"], languages["en"]
    populated = {i for i, code in enumerate(features) if code.startswith("PPL")}
    records, alternates = sections[2], sections[22][0]
    places = collections.defaultdict(list)
    for row in range(records[1]):
        base = records[0] + row * records[2]
        if data[base + 58] not in populated:
            continue
        _, lat, lon, population = struct.unpack_from("<IffI", data, base)
        name_offset, alt_start = struct.unpack_from("<II", data, base + 36)
        packed_country, alt_count = struct.unpack_from("<HH", data, base + 52)
        names = {}
        for k in range(alt_count):
            language, offset = struct.unpack_from("<HI", data, alternates + 6 * (alt_start + k))
            if language in (fr, en):
                names[language] = string(23, offset)
        country = chr(packed_country & 0xFF) + chr(packed_country >> 8) if packed_country else ""
        places[(country, string(21, name_offset))].append((lat, lon, population, names.get(fr, ""), names.get(en, "")))
    return places


def translations(rows, names_path):
    """(pays, nom) → (français, anglais affiché, alias anglais) pour les lieux de [rows] dont un nom diffère."""
    places = read_open_meteo(names_path)
    # Nom déjà porté par un autre lieu du pays : la traduction les confondrait (« Halle (Saale) »
    # → « Halle », « Brentwood Village » → « Brentwood »).
    taken = {(country, normalize(encoded.decode("utf-8", "replace"))) for _, _, country, encoded in rows}
    best = {}
    matched = 0
    for lat, lon, country, encoded in rows:
        name = encoded.decode("utf-8", "replace")
        candidates = []
        for c in places.get((country, name), ()):
            distance = math.hypot(c[0] - lat / 1e5, (c[1] - lon / 1e5) * math.cos(math.radians(lat / 1e5)))
            if distance < 0.02:
                candidates.append((distance, c))
        if not candidates:
            continue
        matched += 1
        _, (_, _, population, fr, en) = min(candidates)
        free = lambda t: t if t and (normalize(t) == normalize(name) or (country, normalize(t)) not in taken) else None
        french = free(french_name(name, fr))
        english = free(english_name(name, en, french))
        alias = None if english else free(english_alias(name, en))
        if alias and french and normalize(alias) == normalize(french):
            alias = None
        key = (country, name)
        if key not in best or population > best[key][0]:
            best[key] = (population, french, english, alias)
    result = {key: names for key, (_, *names) in best.items() if any(names)}
    result = {key: value for key, value in result.items() if all(len((s or "").encode("utf-8")) <= 255 for s in value)}
    print(f"{matched} lieux appariés sur {len(rows)} ; {sum(1 for v in result.values() if v[0])} noms français, "
          f"{sum(1 for v in result.values() if v[1])} noms anglais affichés, {sum(1 for v in result.values() if v[2])} alias anglais")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Construit assets/geo/cities.bin")
    parser.add_argument("source", help="cities.json de lutangar/cities.json")
    parser.add_argument("target", help="fichier cities.bin à écrire")
    parser.add_argument("--names", help="database-v1.bin d'Open-Meteo (noms français et anglais)")
    args = parser.parse_args()
    with open(args.source, encoding="utf-8") as handle:
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
    names = translations(rows, args.names) if args.names else {}

    table = bytearray()
    for (country, name), (fr, en, alias) in sorted(names.items()):
        table += country.encode("ascii")
        for text in (name, fr or "", en or "", alias or ""):
            encoded = text.encode("utf-8")
            table += struct.pack(">B", len(encoded)) + encoded
    with open(args.target, "wb") as out:
        out.write(b"FCG2")
        out.write(struct.pack(">ii", len(names), len(table)))
        out.write(table)
        out.write(struct.pack(">i", len(rows)))
        for lat, lon, country, encoded in rows:
            out.write(struct.pack(">ii", lat, lon))
            out.write(country.encode("ascii"))
            out.write(struct.pack(">B", len(encoded)))
            out.write(encoded)
    print(f"{len(rows)} lieux et {len(names)} noms traduits écrits dans {args.target}")


if __name__ == "__main__":
    main()
