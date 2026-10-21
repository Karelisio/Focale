#!/usr/bin/env python3
"""Calibre les seuils du regroupement des visages et des suggestions de fusion sur de vraies photos.

Données : LFW (Labeled Faces in the Wild, University of Massachusetts), dans la version alignée
112 × 112 d'insightface (lfw.bin : les 6 000 paires du protocole, alignées sur le gabarit ArcFace,
celui de FaceAlignment) et la liste officielle des paires (pairs.txt), qui donne le nom de chaque
image. Les fichiers sont téléchargés (empreintes SHA-256 vérifiées) dans le dossier --data, hors du
dépôt : aucune photo n'est versionnée.

Étapes :
  1. empreintes SFace des 7 701 images distinctes (modèle embarqué, ONNX Runtime, entrée RVB 0..255) ;
     contrôle : exactitude du protocole LFW (OpenCV Zoo annonce 99,42 % pour SFace int8) ;
  2. paires de visages, visage contre centroïde (rattachement), centroïde contre centroïde (fusion) :
     groupes tirés au hasard de k visages, scissions « adverses » (une identité coupée en deux par
     2-moyennes, au moins 3 visages par moitié) et estimation pour de très grandes personnes ;
  3. simulation du regroupement (portage fidèle de FaceClusterer) sur des bibliothèques composées de
     proches et d'inconnus, pour plusieurs couples de seuils, et suggestions de fusion qui en résultent.

Usage :
  python3 -I tools/faces/calibrate.py --data /tmp/lfw --download
  python3 -I tools/faces/calibrate.py --data /tmp/lfw [--model app/src/main/assets/models/sface_int8.onnx]

Dépendances : numpy, onnxruntime, pillow. Durée : ~8 min d'empreintes (mises en cache), puis ~2 min.
"""
import argparse
import collections
import hashlib
import io
import os
import pickle
import sys
import time
import urllib.request

import numpy as np

SOURCES = {
    # Paquet d'évaluation d'insightface (Git LFS), copie publique sur GitHub.
    "lfw.bin": ("https://media.githubusercontent.com/media/dmitriykim/oz_forensics_test/master/datasets/lfw.bin",
                "9b711dca71a47fc9f295a28514abe8cc4e457632a8d634264691398c99569593"),
    # Liste officielle des paires du protocole « View 2 » de LFW.
    "pairs.txt": ("https://raw.githubusercontent.com/happynear/FaceDatasets/master/LFW/pairs.txt",
                  "640a739ef29267fdb4e9100c568ac0b5cad7d877b13e941109c0986969d66098"),
}

# Seuils de l'application.
APP_ATTACH, APP_SEED, APP_MERGE = 0.50, 0.50, 0.55
# Critères de choix.
MAX_PAIR_FAR = 0.0002       # fusion : ≤ 1 fausse suggestion pour 5 000 paires de personnes distinctes
MAX_ATTACH_FAR = 0.0001     # rattachement : ≤ 1 % d'inconnus rattachés à tort avec 100 personnes
MIN_SPLIT_RECALL = 0.99     # fusion : scissions adverses retrouvées


# ── Données ─────────────────────────────────────────────────────────────────────

def download(folder):
    os.makedirs(folder, exist_ok=True)
    for name, (url, digest) in SOURCES.items():
        path = os.path.join(folder, name)
        if not os.path.exists(path):
            print(f"téléchargement de {name}…", flush=True)
            urllib.request.urlretrieve(url, path + ".part")
            os.replace(path + ".part", path)
        check(path, digest)


def check(path, digest):
    sha = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            sha.update(block)
    if sha.hexdigest() != digest:
        raise SystemExit(f"{path} : empreinte SHA-256 inattendue")


class NoGlobals(pickle.Unpickler):
    """lfw.bin est un pickle : seuls octets, listes et booléens sont acceptés (aucun objet, aucun code)."""

    def find_class(self, module, name):
        raise pickle.UnpicklingError(f"objet interdit dans lfw.bin : {module}.{name}")


def load_lfw(folder):
    """Images distinctes (nom, numéro) → JPEG, et paires du protocole (indices, même personne)."""
    with open(os.path.join(folder, "lfw.bin"), "rb") as handle:
        bins, issame = NoGlobals(handle, encoding="bytes").load()
    keys = []
    with open(os.path.join(folder, "pairs.txt")) as handle:
        handle.readline()
        for line in handle:
            p = line.split()
            keys += [(p[0], int(p[1])), (p[0], int(p[2]))] if len(p) == 3 else [(p[0], int(p[1])), (p[2], int(p[3]))]
    if len(keys) != len(bins) or any((k[0] == keys[2 * i + 1][0]) != bool(s) for i, (k, s) in enumerate(zip(keys[::2], issame))):
        raise SystemExit("lfw.bin et pairs.txt ne correspondent pas")
    unique = {}
    for index, key in enumerate(keys):
        if key in unique:
            if bins[unique[key]] != bins[index]:
                raise SystemExit(f"image {key} en deux versions différentes")
        else:
            unique[key] = index
    names = list(unique)
    images = [bins[unique[k]] for k in names]
    lookup = {k: i for i, k in enumerate(names)}
    pairs = [(lookup[keys[2 * i]], lookup[keys[2 * i + 1]], bool(s)) for i, s in enumerate(issame)]
    return names, images, pairs


def embeddings(folder, model_path, images, threads):
    """Empreintes SFace normalisées, en cache dans le dossier des données (clé : empreinte du modèle)."""
    with open(model_path, "rb") as handle:
        model_digest = hashlib.sha256(handle.read()).hexdigest()[:16]
    cache = os.path.join(folder, f"sface_{model_digest}.npy")
    if os.path.exists(cache):
        return np.load(cache)
    import onnxruntime as ort
    from PIL import Image

    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.log_severity_level = 3
    session = ort.InferenceSession(model_path, options, providers=["CPUExecutionProvider"])
    vectors = np.empty((len(images), 128), np.float32)
    start = time.time()
    for i, jpeg in enumerate(images):
        image = Image.open(io.BytesIO(jpeg)).convert("RGB")
        if image.size != (112, 112):
            raise SystemExit("image non alignée en 112 × 112")
        tensor = np.asarray(image, dtype=np.float32).transpose(2, 0, 1)[None].copy()  # RVB, 0..255 (FaceAlignment.tensor)
        vector = session.run(None, {"data": tensor})[0][0]
        vectors[i] = vector / np.linalg.norm(vector)
        if i % 1000 == 999:
            print(f"  {i + 1} empreintes, {time.time() - start:.0f} s", flush=True)
    np.save(cache, vectors)
    return vectors


# ── Mesures ─────────────────────────────────────────────────────────────────────

def unit(x):
    return x / np.linalg.norm(x)


def rates(positives, negatives, thresholds):
    return {t: (float(np.mean(positives >= t)), float(np.mean(negatives >= t))) for t in thresholds}


def protocol_accuracy(V, pairs):
    sims = np.array([V[a] @ V[b] for a, b, _ in pairs])
    same = np.array([s for _, _, s in pairs])
    accuracies = []
    for fold in range(10):
        test = np.zeros(len(pairs), bool)
        test[fold * 600:(fold + 1) * 600] = True
        best = max(np.arange(0, 1, 0.005), key=lambda t: np.mean((sims[~test] >= t) == same[~test]))
        accuracies.append(np.mean((sims[test] >= best) == same[test]))
    return float(np.mean(accuracies)), float(np.std(accuracies))


def two_means(X, rng, restarts=10):
    """Coupe la plus nette d'une identité (2-moyennes sphériques), au moins 3 visages par moitié."""
    best = None
    for _ in range(restarts):
        a, b = rng.choice(len(X), 2, replace=False)
        ca, cb = X[a], X[b]
        for _ in range(20):
            side = (X @ ca) >= (X @ cb)
            if side.all() or not side.any():
                break
            ca, cb = unit(X[side].sum(0)), unit(X[~side].sum(0))
        if min(side.sum(), (~side).sum()) < 3:
            continue
        score = float((X[side] @ ca).sum() + (X[~side] @ cb).sum())
        if best is None or score > best[0]:
            best = (score, float(ca @ cb))
    return None if best is None else best[1]


def report_distributions(V, labels, by_id, rng):
    thresholds = (0.363, 0.40, 0.42, 0.45, 0.48, 0.50, 0.52, 0.55, 0.60)
    S = V @ V.T
    iu = np.triu_indices(len(V), 1)
    same = (labels[:, None] == labels[None, :])[iu]
    s = S[iu]
    print("\n## Paires de visages (toutes les paires d'images distinctes)")
    print(f"{int(same.sum())} paires même identité, {int((~same).sum())} identités différentes")
    for t, (tar, far) in rates(s[same], s[~same], thresholds).items():
        print(f"  {t:.3f} : rappel {tar * 100:6.2f} %, faux {far * 100:.4f} %")

    print("\n## Visage contre centroïde de k visages (rattachement)")
    for k in (2, 3, 5, 8):
        positives, negatives = [], []
        for label, members in by_id.items():
            if len(members) < k + 1:
                continue
            members = rng.permutation(members)
            c = unit(V[members[:k]].sum(0))
            positives.extend(V[members[k:]] @ c)
            others = np.flatnonzero(labels != label)
            negatives.extend(V[rng.choice(others, 400, replace=False)] @ c)
        line = "  ".join(f"{t:.2f} : {tar * 100:5.1f} % / {far * 100:.3f} %" for t, (tar, far) in
                         rates(np.array(positives), np.array(negatives), (0.42, 0.45, 0.48, 0.50, 0.55)).items())
        print(f"  k = {k} (rappel / faux) {line}")

    print("\n## Centroïde contre centroïde (fusion) : groupes de k1 et k2 visages tirés au hasard")
    pooled = []
    for k1, k2 in ((3, 3), (3, 5), (5, 5), (4, 7), (7, 7)):
        positives = []
        for _ in range(20):
            for members in by_id.values():
                if len(members) >= k1 + k2:
                    m = rng.permutation(members)
                    positives.append(unit(V[m[:k1]].sum(0)) @ unit(V[m[k1:k1 + k2]].sum(0)))
        negatives = []
        for _ in range(4):
            e1 = [l for l, m in by_id.items() if len(m) >= k1]
            e2 = [l for l, m in by_id.items() if len(m) >= k2]
            C1 = np.array([unit(V[rng.permutation(by_id[l])[:k1]].sum(0)) for l in e1])
            C2 = np.array([unit(V[rng.permutation(by_id[l])[:k2]].sum(0)) for l in e2])
            negatives.append((C1 @ C2.T)[np.array(e1)[:, None] != np.array(e2)[None, :]])
        negatives = np.concatenate(negatives)
        pooled.append(negatives)
        line = "  ".join(f"{t:.2f} : {far * 100:.4f} %" for t, (_, far) in rates(np.array(positives), negatives, (0.38, 0.42, 0.45, 0.50, 0.55)).items())
        print(f"  {k1}/{k2} : même identité ≥ {np.quantile(positives, 0.01):.2f} (1er centile) ; faux {line}")

    print("\n## Très grandes personnes : similarité des identités moyennes (k → ∞, débruitée)")
    # Somme S_i de k_i visages = k_i·m_i + bruit ; |m_i|² ≈ similarité moyenne de deux visages de i.
    eligible = [l for l, m in by_id.items() if len(m) >= 3]
    sums = np.array([V[by_id[l]].sum(0) for l in eligible])
    counts = np.array([len(by_id[l]) for l in eligible], float)
    within = np.array([(np.sum(V[by_id[l]] @ V[by_id[l]].T) - len(by_id[l])) / (len(by_id[l]) * (len(by_id[l]) - 1)) for l in eligible])
    means = sums / counts[:, None]
    infinite = (means @ means.T) / np.sqrt(np.outer(within, within))
    infinite = infinite[np.triu_indices(len(eligible), 1)]
    line = "  ".join(f"{t:.2f} : {np.mean(infinite >= t) * 100:.4f} %" for t in (0.45, 0.50, 0.55, 0.60))
    print(f"  {len(infinite)} paires d'identités (≥ 3 images) : faux {line}")

    print("\n## Scissions adverses : identité coupée en deux par 2-moyennes (≥ 3 visages par moitié)")
    splits = np.array([s for s in (two_means(V[m], rng) for m in by_id.values() if len(m) >= 6) if s is not None])
    print(f"  {len(splits)} identités ; minimum {splits.min():.3f}, 1er centile {np.quantile(splits, 0.01):.3f}, "
          f"5e {np.quantile(splits, 0.05):.3f}, médiane {np.median(splits):.3f}")

    print("\n## Choix du seuil de fusion")
    pooled = np.concatenate(pooled)
    for t in np.arange(0.40, 0.62, 0.02):
        far = float(np.mean(pooled >= t))
        far_inf = float(np.mean(infinite >= t))
        recall = float(np.mean(splits >= t))
        mark = " ←" if far <= MAX_PAIR_FAR and recall >= MIN_SPLIT_RECALL else ""
        print(f"  {t:.2f} : faux (k = 3 à 7) {far * 100:.4f} %, faux (k → ∞) {far_inf * 100:.4f} %, scissions retrouvées {recall * 100:.1f} %{mark}")


# ── Simulation du regroupement (portage de FaceClusterer.kt) ────────────────────

class Clusterer:
    """Même algorithme que FaceClusterer : rattachement au centroïde, création autour d'un visage en attente."""

    def __init__(self, attach, seed, min_faces=3):
        self.attach, self.seed, self.min_faces = attach, seed, min_faces
        self.persons = {}    # id → [somme, médias, centroïde]
        self.pending = {}    # visage → (média, empreinte)
        self.next_id = -1

    def centroid(self, person):
        p = self.persons[person]
        if p[2] is None:
            norm = np.linalg.norm(p[0])
            p[2] = p[0] / norm if norm > 0 else np.zeros_like(p[0])
        return p[2]

    def add(self, person, media, embedding):
        p = self.persons[person]
        p[0] = p[0] + embedding
        p[1].add(media)
        p[2] = None

    def best_matches(self, group):
        options = []
        if self.persons:
            ids = list(self.persons)
            C = np.array([self.centroid(p) for p in ids])
            for face, media, embedding in group:
                sims = C @ embedding
                for j in np.flatnonzero(sims >= self.attach):
                    if media not in self.persons[ids[j]][1]:
                        options.append((face, media, embedding, ids[j], float(sims[j])))
        options.sort(key=lambda o: -o[4])
        faces, persons, matches = set(), set(), []
        for face, media, embedding, person, _ in options:
            if face not in faces and person not in persons:
                faces.add(face)
                persons.add(person)
                matches.append((face, media, embedding, person))
        return matches

    def neighbors(self, face, embedding):
        return [(f, m, e) for f, (m, e) in self.pending.items() if f != face and float(e @ embedding) >= self.seed]

    @staticmethod
    def per_media(scored):
        best = collections.OrderedDict()
        for item, similarity in scored:
            if item[1] not in best or similarity > best[item[1]][1]:
                best[item[1]] = (item, similarity)
        return [item for item, _ in best.values()]

    def group_around(self, seed):
        members = [seed] + self.neighbors(seed[0], seed[2])
        if len(members) < self.min_faces:
            return None
        for _ in range(2):
            total = np.sum([m[2] for m in members], axis=0)
            scored = []
            for m in members:
                with_self = float(total @ m[2])
                self_squared = float(m[2] @ m[2])
                others = float(total @ total) - 2 * with_self + self_squared
                similarity = 0.0 if others <= 1e-6 else (with_self - self_squared) / np.sqrt(others)
                if similarity >= self.attach:
                    scored.append((m, similarity))
            members = self.per_media(scored)
            if len(members) < self.min_faces:
                return None
        return members

    def absorb(self, person):
        c = self.centroid(person)
        media = self.persons[person][1]
        scored = [((f, m, e), float(c @ e)) for f, (m, e) in self.pending.items() if m not in media]
        joined = self.per_media([(item, s) for item, s in scored if s >= self.attach])
        for face, m, e in joined:
            self.add(person, m, e)
            del self.pending[face]
        return joined

    def cluster(self, faces):
        assignments = {}
        for face in faces:
            self.pending.pop(face[0], None)
        waiting = []
        groups = collections.OrderedDict()
        for face in faces:
            groups.setdefault(face[1], []).append(face)
        for group in groups.values():
            for face, media, embedding, person in self.best_matches(group):
                self.add(person, media, embedding)
                assignments[face] = person
            for face in group:
                if face[0] not in assignments:
                    self.pending[face[0]] = (face[1], face[2])
                    waiting.append(face)
        for face in waiting:
            if face[0] not in self.pending:
                continue
            group = None
            for seed in [face] + self.neighbors(face[0], face[2]):
                if seed[0] in self.pending:
                    group = self.group_around(seed)
                    if group is not None:
                        break
            if group is None:
                continue
            person = self.next_id
            self.next_id -= 1
            self.persons[person] = [np.zeros_like(face[2]), set(), None]
            for f, m, e in group:
                self.add(person, m, e)
                del self.pending[f]
                assignments[f] = person
            for f, _, _ in self.absorb(person):
                assignments[f] = person
        return assignments

    def sweep(self):
        assignments = {}
        groups = collections.OrderedDict()
        for face, (media, embedding) in self.pending.items():
            groups.setdefault(media, []).append((face, media, embedding))
        for group in groups.values():
            for face, media, embedding, person in self.best_matches(group):
                self.add(person, media, embedding)
                del self.pending[face]
                assignments[face] = person
        return assignments


def simulate(V, labels, order, attach, seed):
    """Lots de 16 photos comme l'analyse ; reprise des visages en attente à chaque passage (~640 photos)."""
    clusterer = Clusterer(attach, seed)
    owner = {}
    for start in range(0, len(order), 16):
        if start and (start // 16) % 40 == 0:
            owner.update(clusterer.sweep())
        owner.update(clusterer.cluster([(int(i), int(i), V[i].astype(np.float64)) for i in order[start:start + 16]]))
    owner.update(clusterer.sweep())
    return clusterer, owner


def report_simulations(V, labels, by_id, repeats):
    scenarios = {
        "famille : 20 proches (≥ 10 images), 50 % d'inconnus": (10, 20, 0.5),
        "amis : 70 proches (≥ 8 images), 50 % d'inconnus": (8, 70, 0.5),
        "grande : 150 proches (≥ 6 images), 60 % d'inconnus": (6, 150, 0.6),
    }
    configs = ((0.42, 0.45), (0.45, 0.50), (0.48, 0.50), (0.50, 0.50), (0.50, 0.55))
    merges = (0.38, 0.45, 0.50, 0.55)
    singles = [l for l, m in by_id.items() if len(m) <= 2]
    print("\n## Simulation du regroupement (moyennes sur", repeats, "tirages)")
    print("  rattachement/création : visages mal rattachés, rappel des proches, inconnus rattachés, personnes fourre-tout,")
    print("  puis suggestions de fusion vraies/fausses par seuil")
    for title, (min_images, count, share) in scenarios.items():
        print(f"  {title}")
        for attach, seed in configs:
            rows = []
            for repeat in range(repeats):
                rng = np.random.default_rng(100 + repeat)
                chosen = rng.choice([l for l, m in by_id.items() if len(m) >= min_images], count, replace=False)
                faces = [i for l in chosen for i in by_id[l]]
                strangers = []
                for l in rng.permutation(singles):
                    if len(strangers) >= len(faces) * share / (1 - share):
                        break
                    strangers.extend(by_id[l])
                order = rng.permutation(np.array(faces + strangers))
                clusterer, owner = simulate(V, labels, order, attach, seed)
                members = collections.defaultdict(list)
                for face, person in owner.items():
                    members[person].append(face)
                majority = {p: collections.Counter(labels[f] for f in fs).most_common(1)[0][0] for p, fs in members.items()}
                known = set(labels[faces])
                persons = list(members)
                C = np.array([clusterer.centroid(p) for p in persons]).reshape(len(persons), -1)
                iu = np.triu_indices(len(persons), 1)
                sims = (C @ C.T)[iu] if len(persons) > 1 else np.zeros(0)
                same = np.array([majority[persons[a]] == majority[persons[b]] for a, b in zip(*iu)], bool)
                rows.append((
                    sum(labels[f] != majority[p] for f, p in owner.items()) / max(1, len(owner)),
                    sum(1 for f in faces if f in owner and labels[f] == majority[owner[f]]) / len(faces),
                    sum(1 for f in strangers if f in owner) / len(strangers),
                    sum(1 for p, fs in members.items() if sum(labels[f] == majority[p] for f in fs) < 0.5 * len(fs)),
                    [(int(np.sum((sims >= t) & same)), int(np.sum((sims >= t) & ~same))) for t in merges],
                ))
            mean = lambda i: np.mean([r[i] for r in rows])
            suggestions = "  ".join(f"{t:.2f} : {sum(r[4][j][0] for r in rows)}/{sum(r[4][j][1] for r in rows)}" for j, t in enumerate(merges))
            print(f"    {attach:.2f}/{seed:.2f} : mal rattachés {mean(0) * 100:4.1f} %, rappel {mean(1) * 100:5.1f} %, "
                  f"inconnus rattachés {mean(2) * 100:4.1f} %, fourre-tout {mean(3):4.1f} ; fusions {suggestions}")


def main():
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", required=True, help="dossier des données LFW (hors du dépôt)")
    parser.add_argument("--download", action="store_true", help="télécharge lfw.bin et pairs.txt")
    parser.add_argument("--model", default=os.path.join(root, "app/src/main/assets/models/sface_int8.onnx"))
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=3, help="tirages par scénario de simulation")
    parser.add_argument("--no-simulation", action="store_true")
    args = parser.parse_args()
    if args.download:
        download(args.data)
    for name, (_, digest) in SOURCES.items():
        check(os.path.join(args.data, name), digest)

    names, images, pairs = load_lfw(args.data)
    V = embeddings(args.data, args.model, images, args.threads)
    identities = {}
    labels = np.array([identities.setdefault(n, len(identities)) for n, _ in names])
    by_id = collections.defaultdict(list)
    for i, label in enumerate(labels):
        by_id[label].append(i)
    counts = collections.Counter(len(m) for m in by_id.values())
    print(f"LFW : {len(V)} images distinctes, {len(by_id)} identités, dont {sum(v for k, v in counts.items() if k >= 3)} "
          f"avec au moins 3 images et {sum(v for k, v in counts.items() if k >= 8)} avec au moins 8")
    mean, std = protocol_accuracy(V, pairs)
    print(f"Contrôle : exactitude du protocole LFW (10 plis) {mean * 100:.2f} ± {std * 100:.2f} %")

    rng = np.random.default_rng(0)
    report_distributions(V, labels, by_id, rng)
    if not args.no_simulation:
        report_simulations(V, labels, by_id, args.repeats)
    print(f"\nSeuils de l'application : rattachement {APP_ATTACH}, création {APP_SEED}, fusion {APP_MERGE}")


if __name__ == "__main__":
    sys.exit(main())
