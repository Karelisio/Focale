#!/usr/bin/env python3
"""Pilote l'interface de l'émulateur pour smoke_test.sh : arbre d'accessibilité de la fenêtre
active lu par `uiautomator dump`, appuis et défilement par `input`, fenêtres par `dumpsys window`.

Un MOTIF est une expression régulière comparée, sans tenir compte de la casse, au texte entier
ou à la description (content-desc) d'un élément, ou à l'une de leurs parties séparées par une
virgule (textes fusionnés par Compose) ; « id:MOTIF » compare l'identifiant de ressource, sans
son préfixe « paquet:id/ » ; un motif vide retient tout élément (filtré par --paquet ou --classe).

smoke_ui.py toucher MOTIF [--delai S] [--defiler] [--paquet P] [--classe C] [--rang N] [--actif] [--champ texte|desc]
    Attend l'élément puis le touche au centre de son cadre (--defiler : fait défiler la liste
    tant qu'il n'apparaît pas). Code 1 : introuvable, l'écran est alors décrit.
smoke_ui.py attendre MOTIF... [--delai S] [--absent] [--paquet P]
    Attend qu'un des motifs soit à l'écran (affiche l'élément trouvé), ou --absent qu'aucun
    n'y soit plus.
smoke_ui.py etat MOTIF [--delai S]
    « coché » ou « non coché » : interrupteur ou case à cocher de l'élément.
smoke_ui.py ecran
    Éléments visibles de la fenêtre active (diagnostic).
smoke_ui.py fenetres PAQUET [--externe]
    Fenêtres de PAQUET et leur écran (dumpsys window) ; --externe : code 1 sans fenêtre
    affichée hors de l'écran de l'appareil (écran 0).

Variable facultative : ADB (commande adb, « adb » par défaut).
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
FICHIER = "/data/local/tmp/focale_ui.xml"
PAUSE = 0.7  # entre deux lectures de l'écran, en secondes
DEFILEMENTS = 12
CADRE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
# Dialogue « L'application ne répond pas » du système : « Attendre » le referme sans rien tuer.
ANR = re.compile(r".*(isn.t responding|ne répond pas|not responding).*", re.IGNORECASE | re.DOTALL)
ATTENDRE_ANR = re.compile(r"wait|attendre", re.IGNORECASE)


def adb(*args, timeout=60):
    try:
        resultat = subprocess.run([ADB, *args], capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return 124, b"", "délai d'adb dépassé".encode()
    return resultat.returncode, resultat.stdout, resultat.stderr


class Element:
    def __init__(self, attributs, parent):
        self.texte = attributs.get("text", "").strip()
        self.desc = attributs.get("content-desc", "").strip()
        self.ident = attributs.get("resource-id", "")
        self.classe = attributs.get("class", "")
        self.paquet = attributs.get("package", "")
        self.cochable = attributs.get("checkable") == "true"
        self.coche = attributs.get("checked") == "true"
        self.actif = attributs.get("enabled", "true") == "true"
        self.cliquable = attributs.get("clickable") == "true"
        self.defilable = attributs.get("scrollable") == "true"
        cadre = CADRE.fullmatch(attributs.get("bounds", ""))
        self.cadre = tuple(int(v) for v in cadre.groups()) if cadre else (0, 0, 0, 0)
        self.parent = parent
        self.enfants = []

    @property
    def visible(self):
        x1, y1, x2, y2 = self.cadre
        return x2 > x1 and y2 > y1

    @property
    def centre(self):
        x1, y1, x2, y2 = self.cadre
        return (x1 + x2) // 2, (y1 + y2) // 2

    def ancetres(self):
        noeud = self.parent
        while noeud is not None:
            yield noeud
            noeud = noeud.parent

    def descendants(self):
        for enfant in self.enfants:
            yield enfant
            yield from enfant.descendants()

    def cliquable_proche(self):
        """L'élément lui-même s'il réagit à l'appui, sinon son premier ancêtre cliquable."""
        if self.cliquable:
            return self
        return next((a for a in self.ancetres() if a.cliquable), self)

    def libelle(self):
        morceaux = []
        if self.texte:
            morceaux.append(f"« {self.texte[:80]} »")
        if self.desc and self.desc != self.texte:
            morceaux.append(f"({self.desc[:80]})")
        if self.ident:
            morceaux.append(f"#{self.ident.split(':id/')[-1]}")
        return " ".join(morceaux) or self.classe.rsplit(".", 1)[-1]


def analyser(xml):
    racine = ET.fromstring(xml)
    elements = []

    def parcourir(noeud, parent):
        for enfant in noeud.findall("node"):
            element = Element(enfant.attrib, parent)
            elements.append(element)
            if parent is not None:
                parent.enfants.append(element)
            parcourir(enfant, element)

    parcourir(racine, None)
    return elements


def lire(essais=4):
    """Éléments de la fenêtre active, None si l'écran reste illisible (interface occupée)."""
    for essai in range(essais):
        # Fichier effacé d'abord : uiautomator ne signale pas ses échecs par son code de sortie.
        _, sortie, _ = adb("exec-out", f"rm -f {FICHIER}; uiautomator dump {FICHIER} >/dev/null 2>&1; cat {FICHIER} 2>/dev/null")
        texte = sortie.decode("utf-8", "replace")
        debut = texte.find("<?xml")
        if debut >= 0:
            try:
                elements = analyser(texte[debut:])
            except ET.ParseError:
                elements = None
            if elements:
                if refermer_anr(elements):
                    continue
                return elements
        time.sleep(PAUSE * (essai + 1))
    return None


def refermer_anr(elements):
    if not any(ANR.fullmatch(e.texte) for e in elements if e.texte):
        return False
    bouton = next((e for e in elements if e.visible and ATTENDRE_ANR.fullmatch(e.texte or e.desc)), None)
    if bouton is None:
        return False
    message = next(e.texte for e in elements if e.texte and ANR.fullmatch(e.texte))
    print(f"Dialogue du système « {message} » : Attendre", file=sys.stderr)
    appuyer(bouton)
    time.sleep(1)
    return True


def compiler(motif):
    if motif == "":
        return ("tout", None)
    if motif.startswith("id:"):
        return ("id", re.compile(motif[3:], re.IGNORECASE))
    return ("texte", re.compile(motif, re.IGNORECASE | re.DOTALL))


def correspond(element, motif, champ=None):
    genre, expression = motif
    if genre == "tout":
        return True
    if genre == "id":
        return bool(element.ident) and expression.fullmatch(element.ident.split(":id/")[-1]) is not None
    valeurs = {"texte": (element.texte,), "desc": (element.desc,)}.get(champ, (element.texte, element.desc))
    for valeur in valeurs:
        if not valeur:
            continue
        if expression.fullmatch(valeur):
            return True
        parties = [partie.strip() for partie in valeur.split(",")]
        if len(parties) > 1 and any(expression.fullmatch(partie) for partie in parties):
            return True
    return False


def choisir(elements, motif, args):
    trouves = []
    for element in elements:
        if not element.visible or not correspond(element, motif, getattr(args, "champ", None)):
            continue
        if getattr(args, "paquet", None) and not re.fullmatch(args.paquet, element.paquet):
            continue
        if getattr(args, "classe", None) and not re.fullmatch(args.classe, element.classe):
            continue
        if getattr(args, "actif", False) and not (element.actif and element.cliquable_proche().actif):
            continue
        trouves.append(element)
    rang = getattr(args, "rang", 1)
    if rang < 0:
        rang = len(trouves) + rang + 1
    return trouves[rang - 1] if 0 < rang <= len(trouves) else None


def appuyer(element):
    x, y = element.centre
    adb("shell", "input", "tap", str(x), str(y))


def defiler(elements):
    """Fait défiler vers le bas la plus grande liste de l'écran (à défaut, la fenêtre), d'un geste
    lent : sans élan, la liste s'arrête avec le doigt."""
    listes = [e for e in elements if e.defilable and e.visible]
    zone = max(listes, key=lambda e: (e.cadre[2] - e.cadre[0]) * (e.cadre[3] - e.cadre[1]), default=elements[0])
    x1, y1, x2, y2 = zone.cadre
    x = (x1 + x2) // 2
    hauteur = y2 - y1
    adb("shell", "input", "swipe", str(x), str(y1 + hauteur * 17 // 20), str(x), str(y1 + hauteur // 5), "1000")
    time.sleep(0.8)


def decrire(elements, flux=sys.stdout):
    if elements is None:
        print("  (écran illisible : uiautomator dump sans résultat)", file=flux)
        return
    print("  Écran (fenêtre active) :", file=flux)
    for element in elements:
        if not element.visible:
            continue
        if not (element.texte or element.desc or element.cliquable or element.cochable):
            continue
        drapeaux = []
        if element.cliquable:
            drapeaux.append("cliquable")
        if not element.actif:
            drapeaux.append("inactif")
        if element.cochable:
            drapeaux.append("coché" if element.coche else "non coché")
        x1, y1, x2, y2 = element.cadre
        suite = f" {{{', '.join(drapeaux)}}}" if drapeaux else ""
        print(f"    [{element.paquet}] {element.libelle()}{suite} [{x1},{y1}][{x2},{y2}]", file=flux)


def commande_toucher(args):
    motif = compiler(args.motif)
    fin = time.monotonic() + args.delai
    defilements = 0
    precedent = None
    elements = None
    while True:
        elements = lire()
        if elements is not None:
            cible = choisir(elements, motif, args)
            if cible is not None and defilements:
                # Après un défilement, la liste peut encore bouger : appui sur un cadre stable.
                suivants = lire()
                suivante = choisir(suivants, motif, args) if suivants is not None else None
                if suivante is None or suivante.cadre != cible.cadre:
                    if time.monotonic() >= fin:
                        break
                    time.sleep(PAUSE)
                    continue
                cible = suivante
            if cible is not None:
                appuyer(cible)
                print(f"Touché : {cible.libelle()}")
                time.sleep(args.pause)
                return 0
            if args.defiler and defilements < DEFILEMENTS:
                signature = [e.libelle() for e in elements if e.visible]
                if signature != precedent:
                    precedent = signature
                    defiler(elements)
                    defilements += 1
                    continue
        if time.monotonic() >= fin:
            break
        time.sleep(PAUSE)
    print(f"Introuvable à l'écran après {args.delai:g} s : {args.motif}", file=sys.stderr)
    decrire(elements, sys.stderr)
    return 1


def commande_attendre(args):
    motifs = [compiler(m) for m in args.motifs]
    fin = time.monotonic() + args.delai
    elements = None
    while True:
        elements = lire()
        if elements is not None:
            trouves = [element for element in (choisir(elements, m, args) for m in motifs) if element is not None]
            if trouves and not args.absent:
                print(trouves[0].texte or trouves[0].desc or trouves[0].libelle())
                return 0
            if not trouves and args.absent:
                return 0
        if time.monotonic() >= fin:
            break
        time.sleep(PAUSE)
    etat = "toujours à l'écran" if args.absent else "absent de l'écran"
    print(f"{' | '.join(args.motifs)} : {etat} après {args.delai:g} s", file=sys.stderr)
    decrire(elements, sys.stderr)
    return 1


def commande_etat(args):
    motif = compiler(args.motif)
    fin = time.monotonic() + args.delai
    elements = None
    while True:
        elements = lire()
        cible = choisir(elements, motif, args) if elements is not None else None
        if cible is not None:
            # Interrupteur porté par l'élément, un ancêtre (ligne de réglage) ou un voisin.
            proches = [cible, *list(cible.ancetres())[:5]]
            candidats = proches + [d for p in proches[1:] for d in p.descendants()]
            cochable = next((e for e in candidats if e.cochable), None)
            if cochable is None:
                print(f"{cible.libelle()} : ni interrupteur ni case à cocher", file=sys.stderr)
                return 1
            print("coché" if cochable.coche else "non coché")
            return 0
        if time.monotonic() >= fin:
            break
        time.sleep(PAUSE)
    print(f"Introuvable à l'écran : {args.motif}", file=sys.stderr)
    decrire(elements, sys.stderr)
    return 1


def commande_ecran(args):
    decrire(lire())
    return 0


def commande_fenetres(args):
    code, sortie, erreurs = adb("shell", "dumpsys", "window", "windows")
    if code != 0:
        print(f"dumpsys window impossible : {erreurs.decode('utf-8', 'replace').strip()}", file=sys.stderr)
        return 2
    texte = sortie.decode("utf-8", "replace")
    blocs = re.split(r"\n(?=\s*Window #\d+ )", texte)
    externes = 0
    for bloc in blocs:
        titre = re.match(r"\s*Window #\d+ (Window\{[^}]*\})", bloc)
        if titre is None:
            continue
        nom = titre.group(1)
        if f"package={args.paquet}" not in bloc.split() and not re.search(rf" {re.escape(args.paquet)}(/|\}})", nom):
            continue
        ecran = re.search(r"mDisplayId=(-?\d+)", bloc)
        genre = re.search(r"\bty=(\w+)", bloc)
        visible = re.search(r"isVisible=(true|false)", bloc)
        surface = re.search(r"mHasSurface=(true|false)", bloc)
        affichee = (visible.group(1) == "true") if visible else (surface is not None and surface.group(1) == "true")
        numero = int(ecran.group(1)) if ecran else 0
        print(
            f"écran {numero} · {genre.group(1) if genre else '?'} · {'affichée' if affichee else 'masquée'} · {nom}"
        )
        if numero != 0 and affichee:
            externes += 1
    if args.externe and externes == 0:
        print(f"Aucune fenêtre de {args.paquet} affichée hors de l'écran de l'appareil", file=sys.stderr)
        return 1
    return 0


def main():
    parseur = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commandes = parseur.add_subparsers(dest="commande", required=True)

    def filtres(commande):
        commande.add_argument("--delai", type=float, default=20, help="attente maximale (s)")
        commande.add_argument("--paquet", help="paquet de l'élément (expression régulière)")
        commande.add_argument("--classe", help="classe de l'élément (expression régulière)")
        commande.add_argument("--rang", type=int, default=1, help="rang parmi les éléments trouvés (-1 : dernier)")
        commande.add_argument("--actif", action="store_true", help="élément activé seulement")
        commande.add_argument("--champ", choices=("texte", "desc"), help="comparer le texte seulement, ou la description")

    toucher = commandes.add_parser("toucher", help="toucher un élément")
    toucher.add_argument("motif")
    filtres(toucher)
    toucher.add_argument("--defiler", action="store_true", help="faire défiler jusqu'à l'élément")
    toucher.add_argument("--pause", type=float, default=1.0, help="attente après l'appui (s)")
    toucher.set_defaults(fonction=commande_toucher)

    attendre = commandes.add_parser("attendre", help="attendre un élément")
    attendre.add_argument("motifs", nargs="+")
    filtres(attendre)
    attendre.add_argument("--absent", action="store_true", help="attendre qu'aucun ne soit à l'écran")
    attendre.set_defaults(fonction=commande_attendre)

    etat = commandes.add_parser("etat", help="état d'un interrupteur")
    etat.add_argument("motif")
    filtres(etat)
    etat.set_defaults(fonction=commande_etat)

    ecran = commandes.add_parser("ecran", help="éléments visibles")
    ecran.set_defaults(fonction=commande_ecran)

    fenetres = commandes.add_parser("fenetres", help="fenêtres d'un paquet par écran")
    fenetres.add_argument("paquet")
    fenetres.add_argument("--externe", action="store_true", help="exiger une fenêtre hors de l'écran 0")
    fenetres.set_defaults(fonction=commande_fenetres)

    args = parseur.parse_args()
    sys.exit(args.fonction(args))


if __name__ == "__main__":
    main()
