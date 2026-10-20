#!/usr/bin/env bash
# Test de fumée d'un APK release sur émulateur : installe Focale, lui fournit deux images
# (texte net, photo d'un visage) et attend que l'analyse sur l'appareil enregistre ses
# résultats dans la base : étiquettes, texte reconnu et visages (ML Kit, SFace/ONNX).
# En 1.4.0, R8 retirait le constructeur des « registrars » ML Kit : l'analyse échouait
# sans bruit, en release seulement.
#
# Usage : tools/release/smoke_test.sh APK
#
# Appareil : émulateur « google_apis » (adb root, pour lire la base de l'application).
# Focale (app.focale) y est désinstallé puis réinstallé : ses données sont effacées.
# Requiert adb, python3 et curl. Variables facultatives : FOCALE_SMOKE_TIMEOUT (attente
# maximale de l'analyse, 480 s) et FOCALE_SMOKE_INTERVAL (sondage, 20 s).
set -euo pipefail

PAQUET=app.focale
ACTIVITE=.MainActivity # activité de lancement (app/src/main/AndroidManifest.xml)
BASE=/data/data/$PAQUET/databases/focale.db
PREFERENCES=/data/data/$PAQUET/files/datastore
IMAGES=/sdcard/Pictures
DELAI=${FOCALE_SMOKE_TIMEOUT:-480}
INTERVALLE=${FOCALE_SMOKE_INTERVAL:-20}
# Une relance si rien n'est analysé au quart du délai : la première tâche d'analyse peut
# se terminer, faute de médias, juste avant la fin de la première synchronisation.
RELANCE=$((DELAI / 4))
JOURNAL='AnalysisWorker|TextRecognitionStep|FaceAnalysisStep|EmbeddingStep|MediaSync|mlkit|ComponentDiscovery|AndroidRuntime|onnxruntime|WM-'
ICI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

# Photo d'un visage réel, domaine public (portraits officiels de la NASA), téléchargée au
# moment du test et non versionnée : voir fixtures/README.md. Sources essayées dans
# l'ordre : la première, au contenu vérifié (visage de face), puis Wikimedia Commons
# (960 px : largeur de miniature standard).
SOURCES_VISAGE=(
  "https://raw.githubusercontent.com/scikit-image/scikit-image/v0.19.3/skimage/data/astronaut.png"
  "https://commons.wikimedia.org/wiki/Special:FilePath/Neil_Armstrong_pose.jpg?width=960"
  "https://commons.wikimedia.org/wiki/Special:FilePath/Buzz_Aldrin.jpg?width=960"
)

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "Usage : $0 APK (fichier introuvable : ${1:-aucun})" >&2
  exit 2
fi
APK=$1
if ! command -v adb >/dev/null 2>&1 && [ -n "${ANDROID_HOME:-}" ]; then
  PATH=$ANDROID_HOME/platform-tools:$PATH
fi
for outil in adb python3 curl; do
  command -v "$outil" >/dev/null 2>&1 || { echo "$outil introuvable" >&2; exit 2; }
done

TRAVAIL=$(mktemp -d)
COPIE=$TRAVAIL/base
SIGNALE=
terminer() {
  local code=$?
  rm -rf "$TRAVAIL"
  if [ "$code" -ne 0 ] && [ -z "$SIGNALE" ]; then
    echo "::error::Test de fumée interrompu (code $code) : voir le journal ci-dessus"
  fi
}
trap terminer EXIT

etape() { printf '\n==> %s\n' "$*"; }
avertir() { echo "::warning::$*"; }
shell() { adb shell "$@" | tr -d '\r'; }
actif() { [ -n "$(shell pidof "$PAQUET" 2>/dev/null || true)" ]; }

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
  echo "::error::Test de fumée : $*"
  SIGNALE=1
  diagnostic
  exit 1
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

verifier() {
  # shellcheck disable=SC2086 # options sans espace : découpage voulu
  python3 -I "$ICI/smoke_db.py" base "$COPIE/focale.db" $CONTROLES "$@"
}

diagnostic() {
  echo "--- Base de l'application"
  if recuperer_base; then verifier --details || true; else echo "absente : $BASE"; fi
  echo "--- Préférences (DataStore ; fichier absent : valeurs par défaut)"
  for nom in settings state; do
    if adb pull "$PREFERENCES/$nom.preferences_pb" "$TRAVAIL/" >/dev/null 2>&1; then
      python3 -I "$ICI/smoke_db.py" preferences "$TRAVAIL/$nom.preferences_pb" || true
    else
      echo "$nom.preferences_pb : absent"
    fi
  done
  echo "--- Journal (logcat filtré, 150 dernières lignes)"
  adb logcat -d 2>/dev/null | grep -iE "$JOURNAL" | tail -n 150 || true
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

lancer() {
  shell am start -W -n "$PAQUET/$ACTIVITE" || true
  sleep 5
  actif || echec "Focale ne démarre pas (plantage au lancement ?)"
}

etape "Appareil"
attendre_appareil || { echo "::error::Aucun appareil adb"; SIGNALE=1; exit 1; }
for essai in $(seq 60); do
  [ "$(shell getprop sys.boot_completed 2>/dev/null || true)" = 1 ] && break
  sleep 2
done
SDK=$(shell getprop ro.build.version.sdk)
case $SDK in '' | *[!0-9]*) SDK=0 ;; esac
echo "$(shell getprop ro.product.model) (API $SDK)"

# Images poussées avant adb root, avec l'identité shell : FUSE les enregistre alors dans
# MediaStore (pas celles créées par root, que seule l'analyse du volume indexerait).
etape "Images de test dans $IMAGES"
attendre_stockage || echec "stockage partagé indisponible en écriture ($IMAGES)"
shell "rm -f $IMAGES/focale_smoke_*; mkdir -p $IMAGES"
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

etape "Installation de $(basename "$APK")"
adb uninstall "$PAQUET" >/dev/null 2>&1 || true
adb install -r "$APK"
VERSION=$(shell dumpsys package "$PAQUET" | awk -F= '/versionName=/ && !v { v = $2 } END { print v }') || true
echo "Version installée : ${VERSION:-inconnue}"

etape "Permissions et lancement"
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
adb logcat -c >/dev/null 2>&1 || true
# Réglages par défaut : analyse activée, pas seulement en charge ; elle démarre seule.
lancer

etape "Analyse sur l'appareil (jusqu'à $DELAI s, sondage toutes les $INTERVALLE s)"
debut=$SECONDS
relance=
while :; do
  sleep "$INTERVALLE"
  ecoule=$((SECONDS - debut))
  actif || echec "le processus $PAQUET s'est arrêté pendant l'analyse"
  code=0
  if recuperer_base; then
    etat=$(verifier) || code=$?
  else
    etat="base absente : $BASE"
    code=2
  fi
  echo "[$ecoule s] $etat"
  [ "$code" -eq 0 ] && break
  [ "$ecoule" -ge "$DELAI" ] && echec "analyse incomplète après $DELAI s ($etat)"
  if [ -z "$relance" ] && [ "$ecoule" -ge "$RELANCE" ] && [ "$code" -ge 2 ]; then
    echo "Rien d'analysé après $ecoule s : relance de Focale"
    shell am force-stop "$PAQUET"
    lancer
    relance=1
  fi
done

etape "Résultat"
verifier --details || true
case $etat in
  *"étiquettes 0 "*) avertir "Aucune étiquette sur l'image de texte seule : étiquetage contrôlé par l'analyse principale" ;;
esac
echo "Test de fumée réussi : analyse terminée en $ecoule s."
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '### Test de fumée sur émulateur (API %s) : réussi\n\n%s\n' "$SDK" "$etat" >>"$GITHUB_STEP_SUMMARY"
fi
