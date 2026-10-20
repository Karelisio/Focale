#!/usr/bin/env python3
"""Lit les données de Focale récupérées de l'appareil par smoke_test.sh.

smoke_db.py base FOCALE_DB [--etiquettes] [--visages] [--details]
    État de l'analyse sur une ligne, puis le détail avec --details. Toujours exigé : un
    texte reconnu contenant « facture ». --etiquettes : au moins une étiquette ML Kit ;
    sans cette option, l'analyse principale de tous les médias (enregistrée seulement si
    l'étiquetage n'échoue pas). --visages : au moins un visage détecté.
    Code de sortie : 0 contrôles réussis, 1 pas encore, 2 base absente ou illisible,
    3 aucune analyse enregistrée pour l'instant.

smoke_db.py preferences FICHIER.preferences_pb...
    Clés et valeurs de fichiers DataStore Preferences (réglages, état de l'analyse).

La base est ouverte dans son dossier, avec ses fichiers -wal et -shm s'ils ont été copiés.
"""

import argparse
import pathlib
import sqlite3
import struct
import sys

COMPTES = {
    "medias": "SELECT COUNT(*) FROM media WHERE is_trashed = 0",
    "analyses": """SELECT COUNT(*) FROM media m JOIN media_analysis a ON a.media_id = m.id
                   WHERE m.is_trashed = 0 AND a.analyzed_modified = m.date_modified""",
    "etiquettes": "SELECT COUNT(*) FROM media_label",
    "textes": "SELECT COUNT(*) FROM media_text",
    # text_key : texte normalisé (minuscules, sans accents), voir RecognizedText.searchKey.
    "factures": "SELECT COUNT(*) FROM media_text WHERE text_key LIKE '%facture%'",
    "examens": "SELECT COUNT(*) FROM face_scan",
    "visages": "SELECT COUNT(*) FROM face",
    "personnes": "SELECT COUNT(*) FROM person",
    "empreintes": "SELECT COUNT(*) FROM media_embedding WHERE vector IS NOT NULL",
}


def ouvrir(chemin):
    fichier = pathlib.Path(chemin)
    if not fichier.is_file():
        raise FileNotFoundError(f"base absente : {chemin}")
    # mode=rw : ne crée pas de base vide ; l'écriture reste possible pour relire le WAL.
    connexion = sqlite3.connect(fichier.resolve().as_uri() + "?mode=rw", uri=True)
    connexion.execute("PRAGMA query_only = ON")
    return connexion


def nombre(valeur):
    return f"{valeur:.2f}".replace(".", ",")


def details(connexion):
    print("Médias indexés :")
    for nom, type_mime, largeur, hauteur in connexion.execute(
        "SELECT display_name, mime_type, width, height FROM media WHERE is_trashed = 0 ORDER BY display_name"
    ):
        print(f"  {nom} ({type_mime}, {largeur}×{hauteur})")
    print("Étiquettes :")
    etiquettes = {}
    for nom, etiquette, confiance in connexion.execute(
        """SELECT m.display_name, l.label, l.confidence FROM media_label l JOIN media m ON m.id = l.media_id
           ORDER BY m.display_name, l.confidence DESC"""
    ):
        etiquettes.setdefault(nom, []).append(f"{etiquette} {nombre(confiance)}")
    for nom, liste in etiquettes.items():
        print(f"  {nom} : {', '.join(liste)}")
    print("Texte reconnu :")
    for nom, texte in connexion.execute(
        "SELECT m.display_name, t.text FROM media_text t JOIN media m ON m.id = t.media_id ORDER BY m.display_name"
    ):
        lignes = " / ".join(texte.splitlines()) or "(aucun)"
        print(f"  {nom} : {lignes[:160]}")
    print("Visages :")
    for nom, total, qualite in connexion.execute(
        """SELECT m.display_name, COUNT(f.id), MAX(f.quality) FROM face_scan s
           JOIN media m ON m.id = s.media_id LEFT JOIN face f ON f.media_id = s.media_id
           GROUP BY s.media_id ORDER BY m.display_name"""
    ):
        suite = f" (qualité {nombre(qualite)})" if qualite is not None else ""
        print(f"  {nom} : {total}{suite}")


def commande_base(args):
    try:
        connexion = ouvrir(args.base)
        try:
            c = {cle: connexion.execute(sql).fetchone()[0] for cle, sql in COMPTES.items()}
            ligne = (
                f"médias {c['medias']} · analysés {c['analyses']} · étiquettes {c['etiquettes']}"
                f" · textes {c['textes']} (« facture » : {c['factures']}) · photos examinées {c['examens']}"
                f" · visages {c['visages']} · personnes {c['personnes']} · empreintes {c['empreintes']}"
            )
            controles = [("texte « facture »", c["factures"] > 0)]
            if args.etiquettes:
                controles.append(("étiquettes", c["etiquettes"] > 0))
            else:
                controles.append(("analyse principale", 0 < c["medias"] <= c["analyses"]))
            if args.visages:
                controles.append(("visages", c["visages"] > 0))
            manquants = [nom for nom, reussi in controles if not reussi]
            print(ligne + (f" — en attente : {', '.join(manquants)}" if manquants else " — contrôles réussis"))
            if args.details:
                details(connexion)
        finally:
            connexion.close()
    except (OSError, sqlite3.Error) as erreur:
        # Base pas encore créée, ou copiée pendant une écriture : réessayer plus tard.
        print(f"base illisible : {erreur}")
        return 2
    if not manquants:
        return 0
    return 3 if c["analyses"] + c["textes"] + c["examens"] == 0 else 1


# Format de DataStore Preferences (protobuf) : PreferenceMap { map<string, Value> preferences = 1 },
# Value { bool boolean = 1; float = 2; int32 integer = 3; int64 long = 4; string = 5;
# StringSet string_set = 6; double = 7; bytes = 8 }, StringSet { repeated string strings = 1 }.

def varint(donnees, position):
    resultat = decalage = 0
    while True:
        if position >= len(donnees):
            raise ValueError("varint tronqué")
        octet = donnees[position]
        position += 1
        resultat |= (octet & 0x7F) << decalage
        if not octet & 0x80:
            return resultat, position
        decalage += 7


def champs(donnees):
    position = 0
    while position < len(donnees):
        cle, position = varint(donnees, position)
        numero, type_filaire = cle >> 3, cle & 7
        if type_filaire == 0:
            valeur, position = varint(donnees, position)
        elif type_filaire == 1:
            valeur, position = donnees[position:position + 8], position + 8
        elif type_filaire == 2:
            taille, position = varint(donnees, position)
            valeur, position = donnees[position:position + taille], position + taille
        elif type_filaire == 5:
            valeur, position = donnees[position:position + 4], position + 4
        else:
            raise ValueError(f"type protobuf {type_filaire} non géré")
        if position > len(donnees):
            raise ValueError("message tronqué")
        yield numero, valeur


def texte(octets):
    return octets.decode("utf-8", "replace")


def valeur_preference(message):
    for numero, valeur in champs(message):
        if numero == 1:
            return "true" if valeur else "false"
        if numero == 2:
            return repr(struct.unpack("<f", valeur)[0])
        if numero in (3, 4):
            return str(valeur - (1 << 64) if valeur >= 1 << 63 else valeur)
        if numero == 5:
            return repr(texte(valeur))
        if numero == 6:
            return repr(sorted(texte(chaine) for n, chaine in champs(valeur) if n == 1))
        if numero == 7:
            return repr(struct.unpack("<d", valeur)[0])
        if numero == 8:
            return valeur.hex()
    return "?"


def commande_preferences(args):
    code = 0
    for chemin in args.fichiers:
        print(f"{pathlib.Path(chemin).name} :")
        try:
            entrees = {}
            for numero, entree in champs(pathlib.Path(chemin).read_bytes()):
                if numero != 1:
                    continue
                cle, valeur = "?", "?"
                for n, contenu in champs(entree):
                    if n == 1:
                        cle = texte(contenu)
                    elif n == 2:
                        valeur = valeur_preference(contenu)
                entrees[cle] = valeur
            for cle in sorted(entrees):
                print(f"  {cle} = {entrees[cle]}")
            if not entrees:
                print("  (vide)")
        except (OSError, ValueError, struct.error) as erreur:
            print(f"  illisible : {erreur}")
            code = 2
    return code


def main():
    parseur = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commandes = parseur.add_subparsers(dest="commande", required=True)
    base = commandes.add_parser("base", help="état de l'analyse dans focale.db")
    base.add_argument("base")
    base.add_argument("--etiquettes", action="store_true", help="exiger au moins une étiquette")
    base.add_argument("--visages", action="store_true", help="exiger au moins un visage")
    base.add_argument("--details", action="store_true", help="détail par média")
    base.set_defaults(fonction=commande_base)
    preferences = commandes.add_parser("preferences", help="contenu de fichiers .preferences_pb")
    preferences.add_argument("fichiers", nargs="+")
    preferences.set_defaults(fonction=commande_preferences)
    args = parseur.parse_args()
    sys.exit(args.fonction(args))


if __name__ == "__main__":
    main()
