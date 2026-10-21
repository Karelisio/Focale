#!/usr/bin/env python3
"""Calibre la détection des voyages (TripDetector) sur les photos géolocalisées de YFCC100M.

Données : YFCC100M (Yahoo Flickr Creative Commons 100M), métadonnées publiques de 99,2 millions
de photos et vidéos Flickr sous licence Creative Commons, sur AWS Open Data (Multimedia Commons) :
base SQLite de 65 Go, https://multimedia-commons.s3-us-west-2.amazonaws.com/tools/etc/yfcc100m_dataset.sql
Elle est lue en flux, par plages HTTP, sans être stockée : seuls l'utilisateur (pseudonyme Flickr),
la date de prise de vue (heure locale de l'appareil), la position et sa précision des médias
géolocalisés sont gardés (~800 Mo, hors du dépôt).

Étapes :
  extract  lit la base en flux (~1 h à 20 Mo/s, reprise possible) → records.bin, uids.txt ;
  select   garde les utilisateurs suivis assez longtemps → users.npz ;
  analyze  compare l'ancienne détection et la nouvelle (portage fidèle de TripDetector.kt),
           puis la sensibilité de chaque seuil, et mesure trous et distances des séjours.

Usage :
  python3 -I tools/trips/yfcc_trips.py extract --data /tmp/yfcc
  python3 -I tools/trips/yfcc_trips.py select --data /tmp/yfcc
  python3 -I tools/trips/yfcc_trips.py analyze --data /tmp/yfcc [--users 5000]

Dépendance : numpy.
"""
import argparse
import calendar
import collections
import os
import struct
import sys
import time
import urllib.request

import numpy as np

URL = "https://multimedia-commons.s3-us-west-2.amazonaws.com/tools/etc/yfcc100m_dataset.sql"
RECORD = np.dtype([("uid", "<u4"), ("date", "<i4"), ("lat", "<i4"), ("lon", "<i4"), ("acc", "u1")])
KM_PER_DEGREE = 111.195
EARTH_RADIUS_KM = 6371.0


# ── extract : base SQLite lue en flux ───────────────────────────────────────────

PAGE = 1024                              # taille de page de la base (en-tête SQLite)
LOCAL_MAX = PAGE - 35                    # charge utile maximale d'une cellule dans sa page
LOCAL_MIN = ((PAGE - 12) * 32 // 255) - 23
SERIAL_SIZES = (0, 1, 2, 3, 4, 6, 8, 8, 0, 0, 0, 0)


def varint(buf, pos):
    value = 0
    for i in range(8):
        byte = buf[pos + i]
        value = (value << 7) | (byte & 0x7F)
        if byte < 0x80:
            return value, pos + i + 1
    return (value << 8) | buf[pos + 8], pos + 9


def parse_pages(block, uids, stats, out):
    """Lignes des pages feuilles de la table : colonnes 1 (utilisateur), 3 (date), 10 à 12 (position, précision)."""
    view = memoryview(block)
    for base in range(0, len(block) - PAGE + 1, PAGE):
        if block[base] != 0x0D:  # feuille de B-arbre de table
            continue
        for c in range((block[base + 3] << 8) | block[base + 4]):
            ptr = base + ((block[base + 8 + 2 * c] << 8) | block[base + 9 + 2 * c])
            size, pos = varint(block, ptr)
            _, pos = varint(block, pos)
            if size <= LOCAL_MAX:
                local = size
            else:
                k = LOCAL_MIN + ((size - LOCAL_MIN) % (PAGE - 4))
                local = k if k <= LOCAL_MAX else LOCAL_MIN
            header, hp = varint(block, pos)
            serials = []
            while hp < pos + header and len(serials) < 13:
                t, hp = varint(block, hp)
                serials.append(t)
            if len(serials) < 13:
                continue
            columns, offset = [], pos + header
            for t in serials:
                n = (t - 12) >> 1 if t >= 12 else SERIAL_SIZES[t]
                columns.append((offset, n, t))
                offset += n
            o, n, _ = columns[1]
            if o + n > pos + local:
                continue
            uid = bytes(view[o:o + n])
            index = uids.setdefault(uid, len(uids))
            if index == len(stats):
                stats.append([0, 0, 0])
            counters = stats[index]
            counters[0] += 1
            o12, n12, t12 = columns[12]
            if o12 + n12 > pos + local:
                counters[1] += 1  # suite de la ligne dans une page de débordement : ignorée
                continue
            (o10, n10, _), (o11, n11, _), (o3, n3, _) = columns[10], columns[11], columns[3]
            if n10 == 0 or n11 == 0:
                continue
            try:
                lon, lat = float(bytes(view[o10:o10 + n10])), float(bytes(view[o11:o11 + n11]))
                text = bytes(view[o3:o3 + n3]).decode("ascii")
                date = calendar.timegm((int(text[0:4]), int(text[5:7]), int(text[8:10]), int(text[11:13]), int(text[14:16]), int(text[17:19]), 0, 0, 0))
            except (ValueError, IndexError, UnicodeDecodeError):
                continue
            if not 0 <= date < 2 ** 32:
                continue
            accuracy = int.from_bytes(view[o12:o12 + n12], "big") if 1 <= t12 <= 6 else 0
            counters[2] += 1
            out.write(struct.pack("<IiiiB", index, date - 2 ** 31, round(lat * 1e5), round(lon * 1e5), min(255, max(0, accuracy))))


def extract(folder, chunk=256 << 20):
    os.makedirs(folder, exist_ok=True)
    request = urllib.request.Request(URL, method="HEAD")
    total = int(urllib.request.urlopen(request, timeout=60).headers["Content-Length"])
    state = os.path.join(folder, "state.txt")
    uids, stats, offset = {}, [], 0
    if os.path.exists(state):
        offset = int(open(state).read().split()[0])
        with open(os.path.join(folder, "uids.txt"), "rb") as handle:
            for line in handle:
                uid, a, b, c = line.rstrip(b"\n").split(b"\t")
                uids[uid] = len(uids)
                stats.append([int(a), int(b), int(c)])
        with open(os.path.join(folder, "records.bin"), "r+b") as handle:
            handle.truncate(sum(s[2] for s in stats) * RECORD.itemsize)
    start, first = time.time(), offset
    with open(os.path.join(folder, "records.bin"), "ab") as out:
        while offset < total:
            end = min(total, offset + chunk) - 1
            for attempt in range(8):
                try:
                    request = urllib.request.Request(URL, headers={"Range": f"bytes={offset}-{end}"})
                    block = urllib.request.urlopen(request, timeout=120).read()
                    if len(block) == end - offset + 1:
                        break
                except OSError as error:
                    print("nouvel essai :", error, flush=True)
                time.sleep(5 * (attempt + 1))
            else:
                raise SystemExit("téléchargement impossible")
            if offset == 0:
                block = bytes(PAGE) + block[PAGE:]  # page 1 : en-tête et schéma
            parse_pages(block, uids, stats, out)
            offset = end + 1
            out.flush()
            with open(os.path.join(folder, "uids.txt"), "wb") as handle:
                handle.writelines(uid + f"\t{a}\t{b}\t{c}\n".encode() for uid, (a, b, c) in zip(uids, stats))
            with open(state, "w") as handle:
                handle.write(f"{offset} {total}\n")
            rate = (offset - first) / max(1e-6, time.time() - start)
            print(f"{offset / 1e9:.1f}/{total / 1e9:.1f} Go, {sum(s[2] for s in stats)} médias situés, {rate / 1e6:.0f} Mo/s", flush=True)


# ── select : utilisateurs suivis assez longtemps ────────────────────────────────

def select(folder):
    records = np.fromfile(os.path.join(folder, "records.bin"), dtype=RECORD)
    stats = np.array([[int(x) for x in line.split(b"\t")[1:]] for line in open(os.path.join(folder, "uids.txt"), "rb")])
    date = records["date"].astype(np.int64) + 2 ** 31
    # Précision de la ville ou mieux, dates plausibles (2000 à 2014), position non nulle.
    keep = (records["acc"] >= 11) & (date >= 946684800) & (date < 1420070400) & ~((records["lat"] == 0) & (records["lon"] == 0))
    records, date = records[keep], date[keep]
    order = np.lexsort((date, records["uid"]))
    records, date = records[order], date[order]
    uids, starts, counts = np.unique(records["uid"], return_index=True, return_counts=True)
    span = date[starts + counts - 1] - date[starts]
    days = np.array([len(np.unique(date[s:s + c] // 86400)) for s, c in zip(starts, counts)])
    overflow = stats[uids, 1] / np.maximum(1, stats[uids, 0])
    chosen = (counts >= 300) & (span >= 365 * 86400) & (days >= 60) & (overflow <= 0.05)
    print(f"{len(records)} médias situés à la ville près, {len(uids)} utilisateurs ; retenus : {chosen.sum()} "
          f"(au moins 300 médias sur un an et 60 jours), {counts[chosen].sum()} médias")
    mask = np.repeat(chosen, counts)
    np.savez_compressed(os.path.join(folder, "users.npz"), uid=records["uid"][mask], date=date[mask],
                        lat=records["lat"][mask], lon=records["lon"][mask])


# ── Détection : portage de TripDetector.kt (nouvelle) et de l'ancienne version ──

class Config:
    def __init__(self, name, window=True, familiar_months=4, span_days=90, night_km=60.0, day_km=150.0, gap=2,
                 min_photos=5, cell_km=25.0, best_only=False):
        self.name, self.window, self.familiar_months, self.span_days = name, window, familiar_months, span_days
        self.night_km, self.day_km, self.gap, self.min_photos, self.cell_km = night_km, day_km, gap, min_photos, cell_km
        self.best_only = best_only


NEW = Config("nouvelle détection")
OLD = Config("ancienne : chez-soi par année, 100 km, 1 jour sans média", window=False, familiar_months=3, span_days=0,
             night_km=100.0, day_km=0.0, gap=1, best_only=True)


def haversine(lat, lon, lat2, lon2):
    lat1, lon1, lat2, lon2 = map(np.radians, (lat, lon, lat2, lon2))
    h = np.sin((lat2 - lat1) / 2) ** 2 + np.cos(lat1) * np.cos(lat2) * np.sin((lon2 - lon1) / 2) ** 2
    return 2 * EARTH_RADIUS_KM * np.arcsin(np.sqrt(np.minimum(1.0, h)))


def cells(lat, lon, cell_km):
    deg = cell_km / KM_PER_DEGREE
    row = np.floor((lat + 90.0) / deg).astype(np.int64)
    width = deg / np.maximum(np.cos(np.radians(-90.0 + (row + 0.5) * deg)), 0.01)
    return (row << 32) | np.floor((lon + 180.0) / width).astype(np.int64)


def familiar(days, months, lat, lon, c):
    """Lieux familiers (chez-soi d'abord) de chaque mois : fenêtre de 12 mois, ou année civile pour l'ancienne version."""
    keys = cells(lat, lon, c.cell_km)
    per = {}
    for i in range(len(days)):
        k = (int(months[i]), int(keys[i]))
        a = per.get(k)
        if a is None:
            per[k] = [1, 1, int(days[i]), int(days[i]), 1, lat[i], lon[i]]  # mois, jours, premier, dernier, nb, Σlat, Σlon
        else:
            if days[i] != a[3]:
                a[1] += 1
            a[3] = int(days[i])
            a[4] += 1
            a[5] += lat[i]
            a[6] += lon[i]
    by_month = collections.defaultdict(list)
    for (m, k), a in per.items():
        by_month[m].append((k, a))

    def merge(month_range):
        merged = {}
        for mm in month_range:
            for k, a in by_month.get(mm, ()):
                s = merged.get(k)
                if s is None:
                    merged[k] = list(a)
                else:
                    s[0] += a[0]
                    s[1] += a[1]
                    s[2] = min(s[2], a[2])
                    s[3] = max(s[3], a[3])
                    s[4] += a[4]
                    s[5] += a[5]
                    s[6] += a[6]
        return sorted(merged.items(), key=lambda ka: (-ka[1][0], -ka[1][1], -ka[1][4], ka[0]))

    found = {}
    for m in by_month:
        if c.window:
            ranked = merge(range(m - 6, m + 6))
        else:
            ranked = merge(range(m - m % 12, m - m % 12 + 12))
        if c.best_only:
            ranked = ranked[:1]
        places = [(a[5] / a[4], a[6] / a[4]) for _, a in ranked if a[0] >= c.familiar_months and a[3] - a[2] >= c.span_days]
        if places:
            found[m] = places
    result = {}
    present = sorted(by_month)
    with_places = np.array(sorted(found))
    overall = None
    for m in present:
        if m in found:
            result[m] = found[m]
        elif len(with_places):
            j = int(np.argmin(np.abs(with_places - m) * 2 + (with_places > m)))
            result[m] = found[int(with_places[j])]
        else:
            if overall is None:
                _, a = merge(present)[0]
                overall = [(a[5] / a[4], a[6] / a[4])]
            result[m] = overall
    return result


def detect(dates, lat, lon, c):
    """Voyages : (premier jour, dernier jour, indices des médias lointains), et distance de chaque média aux lieux familiers."""
    days = dates // 86400
    months = days.astype("datetime64[D]").astype("datetime64[M]").astype(int)
    places = familiar(days, months, lat, lon, c)
    away = np.empty(len(days))
    for m in np.unique(months):
        sel = months == m
        away[sel] = np.min([haversine(lat[sel], lon[sel], p[0], p[1]) for p in places[int(m)]], axis=0)
    far = away > c.night_km
    unique_days, first = np.unique(days, return_index=True)
    bounds = np.r_[first, len(days)]
    trips, run = [], None

    def close(r):
        if r and len(r[2]) >= c.min_photos and (r[1] > r[0] or away[r[2]].max() > c.day_km):
            trips.append(r)

    for j, day in enumerate(unique_days):
        idx = np.arange(bounds[j], bounds[j + 1])
        far_idx = idx[far[idx]].tolist()
        if run and far_idx and day - run[1] <= c.gap + 1:
            run[1] = int(day)
            run[2].extend(far_idx)
        else:
            close(run)
            run = [int(day), int(day), far_idx] if far_idx else None
    close(run)
    return trips, away, days, months, places


# ── analyze ─────────────────────────────────────────────────────────────────────

def users(folder, limit):
    data = np.load(os.path.join(folder, "users.npz"))
    uid = data["uid"]
    starts = np.flatnonzero(np.r_[True, uid[1:] != uid[:-1]])
    bounds = list(zip(starts, np.r_[starts[1:], len(uid)]))
    if limit and limit < len(bounds):
        bounds = [bounds[i] for i in sorted(np.random.default_rng(0).choice(len(bounds), limit, replace=False))]
    return data["date"].astype(np.int64), data["lat"] / 1e5, data["lon"] / 1e5, bounds


def evaluate(data, c):
    dates, lat, lon, bounds = data
    total = single = long_trips = repeated = years = 0
    move_trips = to_new_home = 0
    durations = []
    for s, e in bounds:
        d, la, lo = dates[s:e], lat[s:e], lon[s:e]
        trips, away, days, months, places = detect(d, la, lo, c)
        years += len(set((months // 12).tolist()))
        visits = collections.Counter()
        homes = {}
        for m, p in places.items():
            homes.setdefault(m // 12, collections.Counter())[p[0]] += 1
        home_of_year = {y: counter.most_common(1)[0][0] for y, counter in homes.items()}
        for t in trips:
            idx = np.array(t[2])
            center = (float(np.median(la[idx])), float(np.median(lo[idx])))
            year = int(np.datetime64(t[0], "D").astype("datetime64[Y]").astype(int))  # années depuis 1970, comme m // 12
            t.append((year, int(cells(np.array([center[0]]), np.array([center[1]]), 25.0)[0])))
            t.append(center)
            visits[t[3]] += 1
        for t in trips:
            n = t[1] - t[0] + 1
            durations.append(n)
            total += 1
            single += n == 1
            long_trips += n >= 21
            repeated += visits[t[3]] >= 3
            year = t[3][0]
            if year in home_of_year and year + 1 in home_of_year:
                h0, h1 = home_of_year[year], home_of_year[year + 1]
                if haversine(h0[0], h0[1], h1[0], h1[1]) > 100:
                    move_trips += 1
                    to_new_home += haversine(t[4][0], t[4][1], h1[0], h1[1]) < 50
    return dict(total=total, per_year=total / max(1, years), single=single / max(1, total), long=long_trips / max(1, total),
                repeated=repeated / max(1, total), move=(to_new_home, move_trips), median=float(np.median(durations)) if durations else 0)


def gaps_and_distances(data, c):
    """Trous entre deux suites de jours lointains (sans retour à la maison) et séjours par distance."""
    dates, lat, lon, bounds = data
    gaps = collections.defaultdict(lambda: [0, 0])
    bands = collections.Counter()
    years = 0
    for s, e in bounds:
        d, la, lo = dates[s:e], lat[s:e], lon[s:e]
        _, away, days, months, _ = detect(d, la, lo, c)
        years += len(set((months // 12).tolist()))
        unique_days, first = np.unique(days, return_index=True)
        bounds_day = np.r_[first, len(days)]
        day_max = np.array([away[bounds_day[j]:bounds_day[j + 1]].max() for j in range(len(unique_days))])
        day_min = np.array([away[bounds_day[j]:bounds_day[j + 1]].min() for j in range(len(unique_days))])
        runs = []
        for j, day in enumerate(unique_days):
            if day_max[j] > 100:
                idx = np.arange(bounds_day[j], bounds_day[j + 1])
                idx = idx[away[idx] > 100]
                if runs and runs[-1][1] == day - 1 and not runs[-1][3]:
                    runs[-1][1] = day
                    runs[-1][2].extend(idx.tolist())
                else:
                    runs.append([day, day, idx.tolist(), False])
            elif runs:
                runs[-1][3] = True  # journée à la maison après cette suite
        for a, b in zip(runs, runs[1:]):
            gap = b[0] - a[1] - 1
            if a[3] or not 1 <= gap <= 7:
                continue
            ca = (np.mean(la[a[2]]), np.mean(lo[a[2]]))
            cb = (np.mean(la[b[2]]), np.mean(lo[b[2]]))
            gaps[gap][1] += 1
            gaps[gap][0] += haversine(ca[0], ca[1], cb[0], cb[1]) < 50
        j = 0
        while j < len(unique_days):
            if day_min[j] <= 30:
                j += 1
                continue
            k = j
            while k + 1 < len(unique_days) and unique_days[k + 1] == unique_days[k] + 1 and day_min[k + 1] > 30:
                k += 1
            if bounds_day[k + 1] - bounds_day[j] >= 5:
                band = next(limit for limit in (50, 60, 80, 100, 150, 300, 10 ** 9) if day_max[j:k + 1].max() <= limit)
                bands[(band, min(k - j + 1, 2))] += 1
            j = k + 1
    print("\n## Trous : deux suites de jours à plus de 100 km séparées par g jours sans média (sans retour)")
    for g in sorted(gaps):
        same, n = gaps[g]
        print(f"  g = {g} : {n:6d} cas, même endroit (< 50 km) {same / max(1, n) * 100:5.1f} %")
    print("\n## Séjours d'au moins 5 médias tous à plus de 30 km des lieux familiers, par utilisateur-année")
    print("  distance maximale    1 jour   ≥ 2 jours")
    previous = 30
    for limit in (50, 60, 80, 100, 150, 300, 10 ** 9):
        label = f"{previous}-{limit} km" if limit < 10 ** 9 else f"> {previous} km"
        print(f"  {label:>16}   {bands[(limit, 1)] / years:6.3f}   {bands[(limit, 2)] / years:6.3f}")
        previous = limit


def analyze(folder, limit):
    data = users(folder, limit)
    print(f"{len(data[3])} utilisateurs YFCC100M (≥ 300 médias situés sur au moins un an)")
    variants = [OLD, NEW,
                Config("  chez-soi seul (sans autres lieux familiers)", best_only=True),
                Config("  lieux familiers : 3 mois, 60 jours", familiar_months=3, span_days=60),
                Config("  1 jour sans média toléré", gap=1),
                Config("  3 jours sans média tolérés", gap=3),
                Config("  50 km", night_km=50.0),
                Config("  80 km", night_km=80.0),
                Config("  100 km", night_km=100.0),
                Config("  journée seule dès 100 km", day_km=100.0),
                Config("  journée seule dès 200 km", day_km=200.0)]
    print("\n## Voyages détectés")
    print("  variante : voyages par utilisateur-année ; d'un seul jour ; ≥ 21 jours ; vers une destination vue ≥ 3 fois "
          "dans l'année ; l'année d'un déménagement, vers la future adresse ; durée médiane")
    for c in variants:
        r = evaluate(data, c)
        print(f"  {c.name:58s} {r['per_year']:.2f} ; {r['single'] * 100:4.1f} % ; {r['long'] * 100:3.1f} % ; "
              f"{r['repeated'] * 100:4.1f} % ; {r['move'][0]}/{r['move'][1]} ; {r['median']:.0f} j", flush=True)
    gaps_and_distances(data, NEW)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("step", choices=("extract", "select", "analyze"))
    parser.add_argument("--data", required=True, help="dossier de travail, hors du dépôt")
    parser.add_argument("--users", type=int, default=5000, help="utilisateurs tirés au hasard pour l'analyse")
    args = parser.parse_args()
    if args.step == "extract":
        extract(args.data)
    elif args.step == "select":
        select(args.data)
    else:
        analyze(args.data, args.users)


if __name__ == "__main__":
    sys.exit(main())
