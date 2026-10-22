#!/usr/bin/env bash
# Test de fumée d'un APK release sur émulateur, de la mise à jour à la restauration :
#  1. Mise à jour : la dernière version publiée de Focale analyse deux images de test (texte
#     net, photo d'un visage) et reçoit un favori, un album et un réglage ; l'APK à tester est
#     installé par-dessus (adb install -r). Focale doit démarrer sans plantage, la base être
#     migrée vers la version de app/schemas et les données être toujours là.
#  2. Langue : anglais puis français (langue par application, Android 13+), activité recréée.
#  3. Impression d'une photo depuis la visionneuse, par « Enregistrer en PDF » du système.
#  4. Écran externe simulé : la visionneuse y présente sa photo.
#  5. Sauvegarde des données : sauvegarde chiffrée, effacement des données de Focale (pas des
#     photos), nouvelle analyse des deux images par l'APK à tester (étiquettes, texte reconnu
#     et visages : ML Kit, SFace/ONNX), restauration : favori, album et réglage reviennent.
# En 1.4.0, R8 retirait le constructeur des « registrars » ML Kit : l'analyse échouait sans
# bruit, en release seulement. L'interface est pilotée par uiautomator (smoke_ui.py), la base
# et les réglages lus par smoke_db.py.
#
# Usage : tools/release/smoke_test.sh APK [APK_PRÉCÉDENT]
#
# APK_PRÉCÉDENT : version publiée installée d'abord (voir version_precedente.sh) ; sans lui,
# l'APK à tester est installé directement et la mise à jour n'est pas vérifiée.
# Appareil : émulateur « google_apis » (adb root, pour lire la base de l'application), en
# anglais, de préférence avec un clavier matériel (aucun clavier à l'écran sur les dialogues).
# Focale (app.focale) y est désinstallé puis réinstallé : ses données sont effacées.
# Requiert adb, python3 et curl. Variables facultatives : FOCALE_SMOKE_TIMEOUT (attente
# maximale de l'analyse, 480 s), FOCALE_SMOKE_INTERVAL (sondage, 20 s) et
# FOCALE_SMOKE_ARTEFACTS (dossier où garder captures d'écran et journal de l'émulateur).
set -euo pipefail

PAQUET=app.focale
ACTIVITE=.MainActivity # activité de lancement (app/src/main/AndroidManifest.xml)
OUVRIR_MEDIA=app.focale.action.OPEN_MEDIA # MainActivity.ACTION_OPEN_MEDIA, extra media_id
BASE=/data/data/$PAQUET/databases/focale.db
PREFERENCES=/data/data/$PAQUET/files/datastore
IMAGES=/sdcard/Pictures
TELECHARGEMENTS=/sdcard/Download
DELAI=${FOCALE_SMOKE_TIMEOUT:-480}
INTERVALLE=${FOCALE_SMOKE_INTERVAL:-20}
JOURNAL='AnalysisWorker|TextRecognitionStep|FaceAnalysisStep|EmbeddingStep|MediaSync|mlkit|ComponentDiscovery|AndroidRuntime|onnxruntime|WM-|PhotoPrint|DataBackup|DataRestore|CastPresentation|CastManager|LocaleChange|AppLocales'
ICI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCHEMAS=$ICI/../../app/schemas/app.focale.core.database.FocaleDatabase

# Données créées dans Focale, cherchées après la mise à jour et après la restauration. Saisies
# au clavier (input text) : ASCII seulement. Réglage : « Pure black » (Réglages → Apparence),
# désactivé par défaut et repris par la sauvegarde des données.
ALBUM=Fumee
MOT_DE_PASSE=FumeeFocale2024
REGLAGE=amoled
# Textes de l'interface (anglais, langue de l'émulateur ; français en second).
ONGLET_RECHERCHE='Search|Recherche' # onglet de la barre de navigation, écran principal
VISIONNEUSE='Add to favorites|Remove from favorites|Ajouter aux favoris|Retirer des favoris'
DOCUMENTS='com(\.google)?\.android\.documentsui' # sélecteur de documents du système
IMPRESSION=com.android.printspooler

# Photo d'un visage réel, domaine public (portraits officiels de la NASA), téléchargée au
# moment du test et non versionnée : voir fixtures/README.md. Sources essayées dans
# l'ordre : la première, au contenu vérifié (visage de face), puis Wikimedia Commons
# (960 px : largeur de miniature standard).
SOURCES_VISAGE=(
  "https://raw.githubusercontent.com/scikit-image/scikit-image/v0.19.3/skimage/data/astronaut.png"
  "https://commons.wikimedia.org/wiki/Special:FilePath/Neil_Armstrong_pose.jpg?width=960"
  "https://commons.wikimedia.org/wiki/Special:FilePath/Buzz_Aldrin.jpg?width=960"
)

if [ $# -lt 1 ] || [ $# -gt 2 ] || [ ! -f "$1" ]; then
  echo "Usage : $0 APK [APK_PRÉCÉDENT] (fichier introuvable : ${1:-aucun})" >&2
  exit 2
fi
APK=$1
PRECEDENT=${2:-}
if [ -n "$PRECEDENT" ] && [ ! -f "$PRECEDENT" ]; then
  echo "APK précédent introuvable : $PRECEDENT" >&2
  exit 2
fi
if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then
  PATH=$ANDROID_HOME/platform-tools:$PATH
fi
for outil in adb python3 curl; do
  command -v "$outil" >/dev/null 2>&1 || { echo "$outil introuvable" >&2; exit 2; }
done

TRAVAIL=$(mktemp -d)
COPIE=$TRAVAIL/base
ARTEFACTS=${FOCALE_SMOKE_ARTEFACTS:-}
[ -z "$ARTEFACTS" ] || mkdir -p "$ARTEFACTS"
SIGNALE=
ETAPE=préparation
CAPTURES=0
JOURNAL_CONTINU=
ANR_SIGNALEE=
BILAN=()
terminer() {
  local code=$?
  if [ "$code" -ne 0 ] && [ -n "$ARTEFACTS" ]; then
    capture echec || true
    adb logcat -d -b crash >"$ARTEFACTS/logcat-plantages.txt" 2>/dev/null || true
  fi
  if [ -n "$JOURNAL_CONTINU" ]; then kill "$JOURNAL_CONTINU" 2>/dev/null || true; fi
  rm -rf "$TRAVAIL"
  if [ "$code" -ne 0 ] && [ -z "$SIGNALE" ]; then
    echo "::error::Test de fumée interrompu (code $code, étape « $ETAPE ») : voir le journal ci-dessus"
  fi
}
trap terminer EXIT

etape() {
  ETAPE=$1
  printf '\n==> %s\n' "$*"
}
avertir() { echo "::warning::$*"; }
reussi() { BILAN+=("$*"); }
shell() { adb shell "$@" | tr -d '\r'; }
actif() { [ -n "$(shell pidof "$PAQUET" 2>/dev/null || true)" ]; }
ui() { python3 -I "$ICI/smoke_ui.py" "$@"; }
db() { python3 -I "$ICI/smoke_db.py" "$@"; }

attendre_appareil() {
  if command -v timeout >/dev/null 2>&1; then timeout 90 adb wait-for-device; else adb wait-for-device; fi
}

# Juste après le démarrage, le stockage partagé (FUSE) peut encore refuser les écritures
# (« Operation not permitted ») : on attend qu'un fichier témoin puisse y être créé.
attendre_stockage() {
  for essai in $(seq 45); do
    if adb shell "mkdir -p $IMAGES && touch $IMAGES/.focale_sonde && rm -f $IMAGES/.focale_sonde" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# adb push avec quelques nouvelles tentatives (stockage encore indisponible par moments).
pousser() {
  for essai in 1 2 3 4 5; do
    adb push "$1" "$2" >/dev/null && return 0
    sleep 3
  done
  return 1
}

echec() {
  echo "::error::Test de fumée, $ETAPE : $*"
  SIGNALE=1
  diagnostic
  exit 1
}

# Capture de l'écran de l'appareil, gardée dans les artefacts (numérotée dans l'ordre).
capture() {
  [ -n "$ARTEFACTS" ] || return 0
  CAPTURES=$((CAPTURES + 1))
  adb exec-out screencap -p >"$ARTEFACTS/$(printf '%02d' "$CAPTURES")-$1.png" 2>/dev/null || true
}

# Journal complet de l'émulateur dans les artefacts, lu en continu : le tampon de logcat ne
# garde que les dernières minutes. Relancé après adb root (adbd redémarre).
suivre_journal() {
  [ -n "$ARTEFACTS" ] || return 0
  if [ -n "$JOURNAL_CONTINU" ]; then kill "$JOURNAL_CONTINU" 2>/dev/null || true; fi
  adb logcat -v threadtime >>"$ARTEFACTS/logcat.txt" 2>/dev/null &
  JOURNAL_CONTINU=$!
}

# Touche l'élément décrit par les options de smoke_ui.py toucher, sinon échec avec MESSAGE.
toucher() {
  local message=$1
  shift
  ui toucher "$@" || echec "$message"
}

# Attend l'élément décrit par les options de smoke_ui.py attendre, sinon échec avec MESSAGE.
attendre() {
  local message=$1
  shift
  ui attendre "$@" >/dev/null || echec "$message"
}

# Copie de la base (et de son journal WAL) : le journal d'abord, la base ensuite contient
# alors au moins ce qu'il a pu y reporter et la copie reste cohérente malgré les écritures.
recuperer_base() {
  rm -rf "$COPIE"
  mkdir -p "$COPIE"
  adb pull "$BASE-wal" "$COPIE/" >/dev/null 2>&1 || true
  adb pull "$BASE" "$COPIE/" >/dev/null 2>&1 || return 1
  adb pull "$BASE-shm" "$COPIE/" >/dev/null 2>&1 || true
}

recuperer_reglages() {
  rm -f "$TRAVAIL/settings.preferences_pb"
  adb pull "$PREFERENCES/settings.preferences_pb" "$TRAVAIL/" >/dev/null 2>&1
}

verifier() {
  # shellcheck disable=SC2086 # options sans espace : découpage voulu
  db base "$COPIE/focale.db" $CONTROLES "$@"
}

diagnostic() {
  echo "--- Écran"
  ui ecran 2>&1 | head -n 80 || true
  echo "--- Fenêtre au premier plan"
  shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus=|mFocusedApp=' || true
  echo "--- Base de l'application"
  if recuperer_base; then verifier --details || true; else echo "absente : $BASE"; fi
  echo "--- Préférences (DataStore ; fichier absent : valeurs par défaut)"
  for nom in settings state; do
    if adb pull "$PREFERENCES/$nom.preferences_pb" "$TRAVAIL/" >/dev/null 2>&1; then
      db preferences "$TRAVAIL/$nom.preferences_pb" || true
    else
      echo "$nom.preferences_pb : absent"
    fi
  done
  echo "--- Plantages (logcat, tampon crash)"
  adb logcat -d -b crash 2>/dev/null | tail -n 60 || true
  echo "--- Journal (logcat filtré, 150 dernières lignes)"
  adb logcat -d 2>/dev/null | grep -iE "$JOURNAL" | tail -n 150 || true
}

# Plantage de Focale (exception Java ou signal natif) dans le tampon « crash » de logcat,
# depuis le dernier oublier_plantages ; ANR signalée sans échec.
oublier_plantages() { adb logcat -b crash -c >/dev/null 2>&1 || true; }
verifier_plantages() {
  local plantages
  plantages=$(adb logcat -d -b crash 2>/dev/null | tr -d '\r' | grep -A 30 -E "Process: $PAQUET, |>>> $PAQUET <<<" || true)
  if [ -n "$plantages" ]; then
    echo "$plantages" | head -n 60 || true
    echec "plantage de Focale ($1) : $(grep -m 1 -E 'Exception|Error|signal' <<<"$plantages" | sed -E 's/^.*(AndroidRuntime|DEBUG) *: //')"
  fi
  if [ -z "$ANR_SIGNALEE" ] && [ "$(adb logcat -d 2>/dev/null | grep -c "ANR in $PAQUET\b" || true)" -gt 0 ]; then
    avertir "Focale ne répondait plus (ANR), vu pendant : $1"
    ANR_SIGNALEE=1
  fi
  actif || echec "Focale ne tourne plus ($1)"
}

type_image() {
  case $(od -An -tx1 -N4 "$1" 2>/dev/null | tr -d ' \n') in
    ffd8ff*) echo jpg ;;
    89504e47) echo png ;;
    *) return 1 ;;
  esac
}

# Télécharge la photo du visage dans $1 ; renseigne TYPE_VISAGE et SOURCE_VISAGE.
telecharger_visage() {
  local url
  for url in "${SOURCES_VISAGE[@]}"; do
    rm -f "$1"
    if curl -fsSL --retry 2 --connect-timeout 15 --max-time 90 --max-filesize 20000000 \
      -A "FocaleSmokeTest/1.0 (+https://github.com/${GITHUB_REPOSITORY:-Karelisio/Focale})" \
      -o "$1" "$url" && TYPE_VISAGE=$(type_image "$1"); then
      SOURCE_VISAGE=$url
      return 0
    fi
    echo "Téléchargement impossible : $url" >&2
  done
  return 1
}

# Indexation MediaStore : analyse du volume principal (synchrone), vérifiée par une
# requête ; à défaut, analyse fichier par fichier.
indexer() {
  local essai sortie nom manquants=
  shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
  for essai in 1 2 3 4 5 6 7 8 9 10; do
    sortie=$(shell content query --uri content://media/external/images/media --projection _display_name 2>&1 || true)
    if grep -qE 'Error while accessing provider|Exception' <<<"$sortie"; then
      avertir "MediaStore illisible depuis adb : indexation non vérifiée"
      return 0
    fi
    manquants=
    for nom in "$@"; do
      grep -qF "_display_name=$nom" <<<"$sortie" || manquants="$manquants $nom"
    done
    if [ -z "$manquants" ]; then
      echo "Indexées par MediaStore : $*"
      return 0
    fi
    echo "Indexation en attente (essai $essai) :$manquants"
    for nom in $manquants; do
      shell content call --uri content://media --method scan_file \
        --arg "/storage/emulated/0/Pictures/$nom" >/dev/null 2>&1 || true
    done
    sleep 3
  done
  avertir "Images absentes de MediaStore après 30 s :$manquants"
}

installer() {
  local sortie
  sortie=$(adb install -r "$1" 2>&1 | tr -d '\r') || true
  echo "$sortie" | tail -n 2
  grep -q '^Success' <<<"$sortie"
}

version_installee() { shell dumpsys package "$PAQUET" | awk -F= '/versionName=/ && !v { v = $2 } END { print v }' || true; }

accorder_permissions() {
  local permission
  if [ "$SDK" -ge 33 ]; then MEDIAS="READ_MEDIA_IMAGES READ_MEDIA_VIDEO"; else MEDIAS=READ_EXTERNAL_STORAGE; fi
  for permission in $MEDIAS ACCESS_MEDIA_LOCATION; do
    shell pm grant "$PAQUET" "android.permission.$permission"
  done
  echo "Accordées : $MEDIAS ACCESS_MEDIA_LOCATION"
  if [ "$SDK" -ge 33 ]; then
    # Refusée tant que Focale ne la déclare pas.
    if shell pm grant "$PAQUET" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1; then
      echo "Accordée : POST_NOTIFICATIONS"
    else
      echo "POST_NOTIFICATIONS non déclarée par l'application : ignorée"
    fi
  fi
}

lancer() {
  shell am start -W -n "$PAQUET/$ACTIVITE" || true
  sleep 5
  actif || echec "Focale ne démarre pas (plantage au lancement ?)"
}

# Attend que la base contienne les résultats de l'analyse (contrôles de smoke_db.py base).
# $1 = strict : échec au bout de DELAI ; sinon (version précédente), avertissement et code 1
# au bout de DELAI / 2. Une relance si rien n'est analysé au quart du délai : la première
# tâche d'analyse peut se terminer, faute de médias, juste avant la fin de la première
# synchronisation.
attendre_analyse() {
  local strict=$1 delai=$DELAI debut ecoule relance='' code
  [ "$strict" = strict ] || delai=$((DELAI / 2))
  debut=$SECONDS
  while :; do
    sleep "$INTERVALLE"
    ecoule=$((SECONDS - debut))
    actif || echec "le processus $PAQUET s'est arrêté pendant l'analyse"
    code=0
    if recuperer_base; then
      ETAT=$(verifier) || code=$?
    else
      ETAT="base absente : $BASE"
      code=2
    fi
    echo "[$ecoule s] $ETAT"
    [ "$code" -eq 0 ] && break
    if [ "$ecoule" -ge "$delai" ]; then
      [ "$strict" = strict ] && echec "analyse incomplète après $delai s ($ETAT)"
      avertir "Analyse incomplète après $delai s ($ETAT)"
      return 1
    fi
    if [ -z "$relance" ] && [ "$ecoule" -ge "$((delai / 4))" ] && [ "$code" -ge 2 ]; then
      echo "Rien d'analysé après $ecoule s : relance de Focale"
      shell am force-stop "$PAQUET"
      lancer
      relance=1
    fi
  done
  ANALYSE_DUREE=$ecoule
}

# Focale au premier plan, sur ses onglets (retour arrière depuis la visionneuse, un dialogue…).
accueil() {
  local essai
  for essai in 1 2 3 4 5 6; do
    if shell dumpsys window 2>/dev/null | grep 'mCurrentFocus=' | grep "$PAQUET" >/dev/null; then
      ui attendre "$ONGLET_RECHERCHE" --paquet "$PAQUET" --champ texte --delai 3 >/dev/null 2>&1 && return 0
      shell input keyevent KEYCODE_BACK
    else
      shell am start -n "$PAQUET/$ACTIVITE" >/dev/null
    fi
    sleep 1
  done
  echec "onglets de Focale introuvables (écran principal)"
}

# Réglages : onglet « More », puis « Settings » (en bas de l'onglet).
aller_reglages() {
  accueil
  toucher "onglet « More » introuvable" 'More|Plus' --paquet "$PAQUET" --champ texte
  toucher "« Settings » absent de l'onglet More" 'Settings|Réglages' --paquet "$PAQUET" --champ texte --defiler
  # Bouton retour : absent de l'onglet More, présent dans les réglages (quel que soit le défilement).
  attendre "réglages non ouverts" 'Back|Retour' --paquet "$PAQUET" --champ desc
}

# Réglage $1 (motif), cherché dans la liste puis touché, jusqu'à voir ce qu'il ouvre (motif et
# options de smoke_ui.py attendre, $2…) ; touché une seconde fois si l'appui s'est perdu.
ouvrir_reglage() {
  local reglage=$1 essai
  shift
  for essai in 1 2; do
    toucher "« $reglage » absent des réglages" "$reglage" --paquet "$PAQUET" --champ texte --defiler
    ui attendre "$@" --delai 10 >/dev/null && return 0
    echo "« $reglage » touché sans effet visible : nouvel essai"
  done
  echec "« $reglage » n'ouvre rien"
}

# Visionneuse sur l'image $1, ouverte comme depuis le widget (MainActivity.ACTION_OPEN_MEDIA),
# depuis les onglets ; « $2 = dessus » : par-dessus l'écran courant, visionneuse comprise (avant
# 1.11.0, elle gardait alors sa photo). La photo affichée est décrite par son nom de fichier.
ouvrir_media() {
  local id
  [ "${2:-}" = dessus ] || accueil
  recuperer_base || echec "base de Focale illisible"
  id=$(db media "$COPIE/focale.db" "$1") || echec "$1 absent de l'index de Focale"
  shell am start -W -n "$PAQUET/$ACTIVITE" -a "$OUVRIR_MEDIA" --el media_id "$id" >/dev/null
  attendre "visionneuse non ouverte sur $1 (média $id)" "$VISIONNEUSE" --paquet "$PAQUET" --delai 20
  attendre "la visionneuse n'affiche pas $1 (média $id) : autre photo à l'écran" "$1" --champ desc --paquet "$PAQUET" --delai 15
}

# « Nouveautés » présentées au premier lancement d'une version plus récente : refermées.
fermer_nouveautes() {
  if ui attendre "What.s new|Nouveautés" --paquet "$PAQUET" --delai 6 >/dev/null 2>&1; then
    echo "Nouveautés présentées : refermées"
    capture nouveautes
    # Bouton au bas de la feuille, qui défile.
    toucher "bouton « Got it » des nouveautés introuvable" "Got it|C'est parti" --paquet "$PAQUET" --defiler
    ui attendre "What.s new|Nouveautés" --absent --paquet "$PAQUET" --delai 10 >/dev/null || echec "nouveautés toujours affichées après « Got it »"
  fi
}

# Sélecteur de documents du système en mode « créer » : enregistre là où il s'ouvre, sinon
# dans Téléchargements (« Récents » n'accepte aucun fichier).
enregistrer_document() {
  local quoi=$1
  attendre "$quoi : sélecteur de documents du système non ouvert" '' --paquet "$DOCUMENTS" --delai 30
  capture "selecteur-$quoi"
  if ! ui toucher 'Save|Enregistrer' --paquet "$DOCUMENTS" --classe '.*Button' --actif --delai 6; then
    toucher "$quoi : liste des emplacements du sélecteur introuvable" 'Show roots|Afficher les racines' --paquet "$DOCUMENTS"
    toucher "$quoi : « Downloads » absent du sélecteur" 'Downloads|Téléchargements' --paquet "$DOCUMENTS"
    toucher "$quoi : bouton « Save » du sélecteur introuvable" 'Save|Enregistrer' --paquet "$DOCUMENTS" --classe '.*Button' --actif
  fi
}

# Sélecteur de documents en mode « ouvrir » : le fichier $2 (motif), dans les récents ou dans
# Téléchargements.
ouvrir_document() {
  local quoi=$1 motif=$2
  attendre "$quoi : sélecteur de documents du système non ouvert" '' --paquet "$DOCUMENTS" --delai 30
  capture "selecteur-$quoi"
  if ! ui toucher "$motif" --paquet "$DOCUMENTS" --delai 6; then
    toucher "$quoi : liste des emplacements du sélecteur introuvable" 'Show roots|Afficher les racines' --paquet "$DOCUMENTS"
    toucher "$quoi : « Downloads » absent du sélecteur" 'Downloads|Téléchargements' --paquet "$DOCUMENTS"
    toucher "$quoi : fichier absent de Téléchargements" "$motif" --paquet "$DOCUMENTS" --defiler
  fi
}

# Favori (photo du texte), album « $ALBUM » (photo du visage, à défaut la même) et réglage
# « Pure black », créés dans l'interface puis vérifiés dans la base et les préférences ;
# instantané des données dans $TRAVAIL/$1.json.
creer_donnees() {
  local cible=${VISAGE:-$TEXTE} etat
  etape "Données de l'utilisateur : favori, album « $ALBUM », réglage « Pure black »"
  ouvrir_media "$TEXTE"
  toucher "bouton « Add to favorites » introuvable dans la visionneuse" 'Add to favorites|Ajouter aux favoris' --paquet "$PAQUET"
  attendre "favori non pris en compte (bouton « Remove from favorites » absent)" 'Remove from favorites|Retirer des favoris' --paquet "$PAQUET"
  capture favori
  [ "$cible" = "$TEXTE" ] || ouvrir_media "$cible"
  toucher "menu de la visionneuse introuvable" 'More|Plus' --paquet "$PAQUET" --champ desc
  toucher "« Add to album » absent du menu de la visionneuse" 'Add to album|Ajouter à un album' --paquet "$PAQUET"
  toucher "« New album » absent de la feuille des albums" 'New album|Nouvel album' --paquet "$PAQUET"
  toucher "champ du nom de l'album introuvable" '' --classe 'android.widget.EditText' --paquet "$PAQUET"
  shell input text "$ALBUM"
  toucher "bouton « Create » de l'album introuvable" 'Create|Créer' --paquet "$PAQUET" --actif
  capture album
  aller_reglages
  toucher "réglage « Pure black » introuvable" 'Pure black|Noir pur' --paquet "$PAQUET" --defiler
  etat=$(ui etat 'Pure black|Noir pur' --paquet "$PAQUET") || echec "état du réglage « Pure black » illisible"
  [ "$etat" = coché ] || echec "réglage « Pure black » toujours désactivé après l'appui"
  capture reglage
  sleep 2
  recuperer_base || echec "base de Focale illisible"
  db instantane "$COPIE/focale.db" >"$TRAVAIL/$1.json" || echec "base de Focale illisible"
  python3 -I - "$TRAVAIL/$1.json" "$TEXTE" "$ALBUM" "$cible" <<'PY' || echec "favori ou album absent de la base (interface pilotée sans effet ?)"
import json, sys
donnees = json.load(open(sys.argv[1], encoding="utf-8"))
print(f"Base : favoris {donnees['favoris']}, albums {donnees['albums']}")
sys.exit(0 if donnees["favoris"] == [sys.argv[2]] and donnees["albums"] == {sys.argv[3]: [sys.argv[4]]} else 1)
PY
  recuperer_reglages || echec "réglages de Focale illisibles"
  db preferences "$TRAVAIL/settings.preferences_pb" --exiger "$REGLAGE=true" || echec "réglage « $REGLAGE » absent des préférences"
}

# Favori, album et réglage de l'instantané $1 toujours là (comparer de smoke_db.py, options $2…).
verifier_donnees() {
  local instantane=$1
  shift
  recuperer_base || echec "base de Focale illisible"
  db comparer "$COPIE/focale.db" "$TRAVAIL/$instantane.json" "$@" || echec "données de l'utilisateur perdues (favori, album)"
  recuperer_reglages || echec "réglages de Focale illisibles"
  db preferences "$TRAVAIL/settings.preferences_pb" --exiger "$REGLAGE=true" || echec "réglage « $REGLAGE » perdu"
}

etape "Appareil"
attendre_appareil || { echo "::error::Aucun appareil adb"; SIGNALE=1; exit 1; }
for essai in $(seq 60); do
  [ "$(shell getprop sys.boot_completed 2>/dev/null || true)" = 1 ] && break
  sleep 2
done
SDK=$(shell getprop ro.build.version.sdk)
case $SDK in '' | *[!0-9]*) SDK=0 ;; esac
echo "$(shell getprop ro.product.model) (API $SDK), $(shell wm size | tail -n 1), $(shell getprop persist.sys.locale)"

# Images poussées avant adb root, avec l'identité shell : FUSE les enregistre alors dans
# MediaStore (pas celles créées par root, que seule l'analyse du volume indexerait).
etape "Images de test dans $IMAGES"
attendre_stockage || echec "stockage partagé indisponible en écriture ($IMAGES)"
shell "rm -f $IMAGES/focale_smoke_* $TELECHARGEMENTS/focale*; mkdir -p $IMAGES"
TEXTE=focale_smoke_facture.png
pousser "$ICI/fixtures/facture.png" "$IMAGES/$TEXTE" || echec "copie de l'image de texte impossible"
NOMS=$TEXTE
VISAGE=
CONTROLES=
if telecharger_visage "$TRAVAIL/visage"; then
  VISAGE=focale_smoke_visage.$TYPE_VISAGE
  pousser "$TRAVAIL/visage" "$IMAGES/$VISAGE" || echec "copie de la photo de visage impossible"
  NOMS="$NOMS $VISAGE"
  CONTROLES="--etiquettes --visages"
  echo "Photo de visage (domaine public, NASA) : $SOURCE_VISAGE"
else
  avertir "Photo de visage indisponible : contrôle des visages sauté, étiquetage contrôlé par l'analyse principale seulement"
fi
# shellcheck disable=SC2086 # noms de fichiers sans espace
indexer $NOMS

etape "Accès root (adb root), pour lire la base de l'application"
for essai in 1 2 3 4 5; do
  adb root >/dev/null 2>&1 || true
  sleep 2
  attendre_appareil || true
  [ "$(shell id -u 2>/dev/null || true)" = 0 ] && break
  if [ "$essai" -eq 5 ]; then
    echo "::error::adb root impossible : un émulateur « google_apis » (sans Play Store) est requis"
    SIGNALE=1
    exit 1
  fi
done
echo "adbd en root"
suivre_journal

# ── 1. Mise à jour par-dessus la version publiée précédente ──────────────────────────────
adb uninstall "$PAQUET" >/dev/null 2>&1 || true
if [ -n "$PRECEDENT" ]; then
  etape "Version publiée précédente : ${SMOKE_PREVIOUS_VERSION:-$(basename "$PRECEDENT")}"
  if ! installer "$PRECEDENT"; then
    avertir "Version précédente impossible à installer : mise à jour non testée"
    PRECEDENT=
  fi
fi
if [ -z "$PRECEDENT" ]; then
  etape "Installation de $(basename "$APK") (sans version précédente : mise à jour non testée)"
  installer "$APK" || echec "installation de l'APK impossible"
fi
VERSION_DEPART=$(version_installee)
echo "Version installée : ${VERSION_DEPART:-inconnue}"

etape "Permissions et lancement"
accorder_permissions
adb logcat -c >/dev/null 2>&1 || true
# Réglages par défaut : analyse activée, pas seulement en charge ; elle démarre seule.
lancer

etape "Analyse par la version ${VERSION_DEPART:-installée} (jusqu'à $((DELAI / 2)) s, sondage toutes les $INTERVALLE s)"
attendre_analyse precedente || true
creer_donnees avant-mise-a-jour

if [ -n "$PRECEDENT" ]; then
  etape "Mise à jour : $(basename "$APK") installé par-dessus la version $VERSION_DEPART"
  oublier_plantages
  installer "$APK" || echec "mise à jour refusée par Android (signature, version ?)"
  VERSION=$(version_installee)
  echo "Version installée : ${VERSION:-inconnue} (depuis $VERSION_DEPART)"
  lancer
  fermer_nouveautes
  verifier_plantages "démarrage après la mise à jour"
  capture apres-mise-a-jour
  # Migration faite à la première ouverture de la base, au démarrage.
  for essai in $(seq 20); do
    code=0
    if recuperer_base; then MIGRATION=$(db schema "$COPIE/focale.db" "$SCHEMAS") || code=$?; else MIGRATION="base absente"; code=2; fi
    [ "$code" -eq 0 ] && break
    sleep 3
  done
  echo "Base : $MIGRATION"
  [ "$code" -eq 0 ] || echec "base non migrée vers le schéma de app/schemas ($MIGRATION)"
  verifier_donnees avant-mise-a-jour
  verifier || avertir "Résultats de l'analyse incomplets après la mise à jour (étape refaite par la nouvelle version ?)"
  reussi "Mise à jour depuis $VERSION_DEPART : démarrage, base ($MIGRATION), favori, album et réglage gardés"
else
  VERSION=$VERSION_DEPART
fi

# ── 2. Langue de l'application ───────────────────────────────────────────────────────────
if [ "$SDK" -ge 33 ]; then
  etape "Langue de Focale : anglais, français, puis anglais (cmd locale set-app-locales)"
  accueil
  oublier_plantages
  for langue in en fr en; do
    case $langue in
      en) attendu=Search ;;
      fr) attendu=Recherche ;;
    esac
    shell cmd locale set-app-locales "$PAQUET" --locales "$langue"
    ui attendre "$attendu" --paquet "$PAQUET" --champ texte --delai 20 >/dev/null || echec "interface pas passée en « $langue » (onglet « $attendu » absent)"
    verifier_plantages "langue « $langue »"
    echo "Langue « $langue » : onglet « $attendu » affiché"
    capture "langue-$langue"
  done
  reussi "Langue : anglais, français, anglais, interface recréée sans plantage"
else
  avertir "Android $SDK : langue par application absente avant Android 13, étape sautée"
fi

# ── 3. Impression ────────────────────────────────────────────────────────────────────────
etape "Impression : visionneuse, « Print », « Save as PDF »"
oublier_plantages
shell "rm -f $TELECHARGEMENTS/*.pdf"
ouvrir_media "$TEXTE"
toucher "menu de la visionneuse introuvable" 'More|Plus' --paquet "$PAQUET" --champ desc
toucher "« Print » absent du menu de la visionneuse" 'Print|Imprimer' --paquet "$PAQUET"
attendre "dialogue d'impression du système non ouvert" '' --paquet "$IMPRESSION" --delai 30
verifier_plantages "ouverture du dialogue d'impression"
PDF=
if ! ui attendre 'Save as PDF|Enregistrer au format PDF' --paquet "$IMPRESSION" --delai 10 >/dev/null 2>&1; then
  # Autre destination choisie : « Save as PDF » dans la liste des imprimantes.
  ui toucher 'id:destination_spinner' --paquet "$IMPRESSION" --delai 5 >/dev/null 2>&1 \
    && ui toucher 'Save as PDF|Enregistrer au format PDF' --paquet "$IMPRESSION" --delai 5 >/dev/null 2>&1 || true
fi
if ui attendre 'Save as PDF|Enregistrer au format PDF' --paquet "$IMPRESSION" --delai 5 >/dev/null 2>&1; then
  capture impression
  toucher "bouton d'enregistrement en PDF introuvable" 'id:print_button' --paquet "$IMPRESSION" --actif --delai 60
  enregistrer_document impression
  for essai in $(seq 30); do
    PDF=$(shell "ls $TELECHARGEMENTS/*.pdf 2>/dev/null" | sed -n 1p)
    [ -n "$PDF" ] && [ "$(shell "head -c 5 '$PDF'" 2>/dev/null)" = "%PDF-" ] && break
    PDF=
    sleep 2
  done
  [ -n "$PDF" ] || echec "aucun PDF enregistré dans $TELECHARGEMENTS après « Save as PDF »"
  sleep 2
  pages=$(shell "grep -c '/Type */Page[^s]' '$PDF'" 2>/dev/null || echo 0)
  echo "PDF : $PDF ($(shell "stat -c %s '$PDF'") octets, pages : ${pages:-?})"
  reussi "Impression : PDF enregistré ($(basename "$PDF"))"
else
  avertir "« Save as PDF » absent de cette image système : seule l'ouverture du dialogue d'impression est vérifiée"
  capture impression
  shell input keyevent KEYCODE_BACK
  reussi "Impression : dialogue du système ouvert (sans « Save as PDF »)"
fi
verifier_plantages "impression"

# ── 4. Écran externe simulé ──────────────────────────────────────────────────────────────
etape "Écran externe simulé (1920x1080, 320 ppp) : présentation de la visionneuse"
oublier_plantages
shell settings put global overlay_display_devices 1920x1080/320
for essai in $(seq 15); do
  [ "$(shell dumpsys display 2>/dev/null | grep -c 'Overlay #1' || true)" -gt 0 ] && break
  sleep 1
done
[ "$(shell dumpsys display 2>/dev/null | grep -c 'Overlay #1' || true)" -gt 0 ] || echec "écran simulé « Overlay #1 » non créé par le système"
ouvrir_media "$TEXTE"
PRESENTATION=
for essai in $(seq 10); do
  if ui fenetres "$PAQUET" --externe >"$TRAVAIL/fenetres.txt" 2>&1; then
    PRESENTATION=1
    break
  fi
  sleep 2
done
cat "$TRAVAIL/fenetres.txt"
capture ecran-externe
verifier_plantages "présentation sur l'écran externe"
[ -n "$PRESENTATION" ] || echec "aucune fenêtre de Focale sur l'écran externe : présentation absente"
if [ -n "$VISAGE" ]; then
  # Photo du widget demandée par-dessus la visionneuse : elle remplace la photo affichée.
  ouvrir_media "$VISAGE" dessus
  verifier_plantages "photo suivante sur l'écran externe"
  for essai in $(seq 10); do
    ui fenetres "$PAQUET" --externe >/dev/null 2>&1 && break
    [ "$essai" -lt 10 ] || echec "présentation perdue en passant à $VISAGE"
    sleep 2
  done
  echo "Visionneuse passée de $TEXTE à $VISAGE (ouverture par-dessus), toujours présentée"
fi
shell settings delete global overlay_display_devices >/dev/null
sleep 3
verifier_plantages "départ de l'écran externe"
reussi "Écran externe : photo présentée, photo suivante ouverte par-dessus, écran retiré sans plantage"

# ── 5. Sauvegarde, effacement, analyse, restauration ─────────────────────────────────────
etape "Sauvegarde des données (Réglages → Back up now)"
recuperer_base || echec "base de Focale illisible"
db instantane "$COPIE/focale.db" >"$TRAVAIL/avant-sauvegarde.json" || echec "base de Focale illisible"
oublier_plantages
aller_reglages
ouvrir_reglage "Back up now|Sauvegarder maintenant" '' --classe 'android.widget.EditText' --paquet "$PAQUET"
toucher "champ du mot de passe introuvable" '' --classe 'android.widget.EditText' --paquet "$PAQUET" --rang 1
shell input text "$MOT_DE_PASSE"
toucher "champ de confirmation du mot de passe introuvable" '' --classe 'android.widget.EditText' --paquet "$PAQUET" --rang 2
shell input text "$MOT_DE_PASSE"
toucher "bouton « Choose location » introuvable ou inactif" "Choose location|Choisir l'emplacement" --paquet "$PAQUET" --actif
enregistrer_document sauvegarde
RESULTAT=$(ui attendre 'Data backed up.*|Données sauvegardées.*' 'Backup failed|Échec de la sauvegarde' 'Backup cancelled|Sauvegarde annulée' \
  --paquet "$PAQUET" --delai 90 2>/dev/null) || RESULTAT=
capture sauvegarde
case $RESULTAT in
  'Data backed up'* | 'Données sauvegardées'* | '') ;;
  *) echec "sauvegarde : « $RESULTAT »" ;;
esac
SAUVEGARDE=$(shell "ls $TELECHARGEMENTS/focale-*.focale 2>/dev/null" | sed -n 1p)
[ -n "$SAUVEGARDE" ] || echec "fichier de sauvegarde absent de $TELECHARGEMENTS (${RESULTAT:-aucun message})"
TAILLE=$(shell "stat -c %s '$SAUVEGARDE'")
[ "${TAILLE:-0}" -gt 0 ] || echec "fichier de sauvegarde vide : $SAUVEGARDE"
echo "Sauvegarde : $SAUVEGARDE ($TAILLE octets) — ${RESULTAT:-message non vu, fichier présent}"
verifier_plantages "sauvegarde"

etape "Effacement des données de Focale (pm clear), photos gardées"
[ "$(shell pm clear "$PAQUET")" = Success ] || echec "pm clear $PAQUET refusé"
accorder_permissions
adb logcat -c >/dev/null 2>&1 || true
lancer

etape "Analyse sur l'appareil par $(basename "$APK") (jusqu'à $DELAI s, sondage toutes les $INTERVALLE s)"
attendre_analyse strict
verifier --details || true
case $ETAT in
  *"étiquettes 0 "*) avertir "Aucune étiquette sur l'image de texte seule : étiquetage contrôlé par l'analyse principale" ;;
esac
reussi "Analyse : $ETAT ($ANALYSE_DUREE s)"

etape "Restauration de la sauvegarde (Réglages → Restore)"
oublier_plantages
aller_reglages
ouvrir_reglage 'Restore|Restaurer' '' --paquet "$DOCUMENTS"
ouvrir_document restauration 'focale-.*\.focale'
attendre "mot de passe de la sauvegarde non demandé" 'Backup password|Mot de passe de la sauvegarde' --paquet "$PAQUET" --delai 30
toucher "champ du mot de passe introuvable" '' --classe 'android.widget.EditText' --paquet "$PAQUET"
shell input text "$MOT_DE_PASSE"
toucher "bouton « Restore » du mot de passe introuvable" 'Restore|Restaurer' --paquet "$PAQUET" --actif
attendre "résumé de la sauvegarde non affiché (mot de passe refusé ?)" 'Restore the backup from .*|Restaurer la sauvegarde du .*' --paquet "$PAQUET" --delai 90
capture resume
toucher "bouton « Restore » du résumé introuvable" 'Restore|Restaurer' --paquet "$PAQUET" --actif
RESULTAT=$(ui attendre 'Restore complete|Restauration terminée' 'Restore failed|Échec de la restauration' --paquet "$PAQUET" --delai 120) \
  || echec "restauration sans bilan après 120 s"
capture restauration
ui ecran | grep -E '«' | sed -n '1,12p' || true
[[ $RESULTAT =~ ^(Restore complete|Restauration terminée) ]] || echec "restauration : « $RESULTAT »"
toucher "bouton « Close » du bilan introuvable" 'Close|Fermer' --paquet "$PAQUET"
sleep 2
verifier_donnees avant-sauvegarde --donnees
verifier_plantages "restauration"
reussi "Sauvegarde des données : fichier de $TAILLE octets, données effacées puis restaurées (favori, album, réglage)"

etape "Résultat"
for ligne in "${BILAN[@]}"; do echo "- $ligne"; done
echo "Test de fumée réussi (Focale ${VERSION:-?}, API $SDK)."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    printf '### Test de fumée sur émulateur (API %s) : réussi\n\n' "$SDK"
    for ligne in "${BILAN[@]}"; do printf -- '- %s\n' "$ligne"; done
  } >>"$GITHUB_STEP_SUMMARY"
fi
