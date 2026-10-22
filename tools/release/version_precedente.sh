#!/usr/bin/env bash
# Dernière version publiée de Focale (version GitHub) que l'APK à tester peut mettre à jour, pour
# le test de mise à jour de smoke_test.sh : son APK universel (standard) est téléchargé dans
# DOSSIER. Écartées : les versions plus récentes que l'APK (Android refuse de revenir en
# arrière), celle construite par le commit testé (même APK), celles sans cet APK. Signature
# différente (secrets de signature absents, demande de fusion venue d'un fork) : avertissement,
# rien n'est retenu.
#
# Usage : tools/release/version_precedente.sh APK DOSSIER
#
# Requiert gh (GH_TOKEN), et aapt2 et apksigner du SDK Android (ANDROID_HOME ou ANDROID_SDK_ROOT).
# Dépôt : GITHUB_REPOSITORY, à défaut celui du clone ; commit testé : GITHUB_SHA ;
# FOCALE_PREVIOUS_TAG (facultatif) : version à prendre plutôt que la dernière (vX.Y.Z, pour
# éprouver une migration plus ancienne). Résultat : SMOKE_PREVIOUS_APK et SMOKE_PREVIOUS_VERSION
# ajoutés à GITHUB_ENV s'il existe, et affichés.
set -euo pipefail

NOM=app-standard-universal-release.apk

if [ $# -ne 2 ] || [ ! -f "$1" ]; then
  echo "Usage : $0 APK DOSSIER (APK introuvable : ${1:-aucun})" >&2
  exit 2
fi
APK=$1
DOSSIER=$2
avertir() { echo "::warning::Version précédente : $*"; }

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
OUTILS=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
if [ -z "$OUTILS" ] || [ ! -x "$OUTILS/aapt2" ] || [ ! -x "$OUTILS/apksigner" ]; then
  avertir "aapt2 ou apksigner introuvable (SDK : ${SDK:-non défini}) : mise à jour non testée"
  exit 0
fi

# Code de version (1.2.3 → 1002003, comme app/build.gradle.kts) et empreinte du certificat.
# (lus en entier : un lecteur qui s'arrête au premier résultat ferait échouer la commande, pipefail)
code_version() { "$OUTILS/aapt2" dump badging "$1" 2>/dev/null | sed -n "s/^package: .* versionCode='\([0-9]*\)'.*/\1/p" | sed -n 1p; }
certificat() { "$OUTILS/apksigner" verify --print-certs "$1" 2>/dev/null | sed -n 's/.*certificate SHA-256 digest: //p' | sed -n 1p; }

CODE=$(code_version "$APK")
SIGNATURE=$(certificat "$APK")
if [ -z "$CODE" ] || [ -z "$SIGNATURE" ]; then
  avertir "version ou signature de $(basename "$APK") illisible : mise à jour non testée"
  exit 0
fi
DEPOT=${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner 2>/dev/null || true)}
if [ -z "$DEPOT" ] || ! TAGS=$(gh release list --repo "$DEPOT" --exclude-drafts --exclude-pre-releases \
  --limit 30 --json tagName --jq '.[].tagName' 2>&1); then
  avertir "liste des versions de ${DEPOT:-?} illisible (${TAGS:-dépôt inconnu}) : mise à jour non testée"
  exit 0
fi

if [ -n "${FOCALE_PREVIOUS_TAG:-}" ]; then
  TAGS=$FOCALE_PREVIOUS_TAG
fi
for tag in $TAGS; do
  [[ $tag =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || continue
  code=$((BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 1000 + BASH_REMATCH[3]))
  if [ "$code" -gt "$CODE" ]; then
    echo "$tag écartée : plus récente que l'APK à tester (code $code > $CODE)"
    continue
  fi
  commit=$(gh api "repos/$DEPOT/commits/$tag" --jq .sha 2>/dev/null || true)
  if [ -n "${GITHUB_SHA:-}" ] && [ "$commit" = "$GITHUB_SHA" ]; then
    echo "$tag écartée : construite par le commit testé"
    continue
  fi
  rm -rf "${DOSSIER:?}/$tag"
  if ! gh release download "$tag" --repo "$DEPOT" --pattern "$NOM" --dir "$DOSSIER/$tag" >/dev/null 2>&1 \
    || [ ! -s "$DOSSIER/$tag/$NOM" ]; then
    echo "$tag écartée : $NOM absent ou téléchargement impossible"
    continue
  fi
  precedent=$DOSSIER/$tag/$NOM
  if [ "$(certificat "$precedent")" != "$SIGNATURE" ]; then
    avertir "$tag n'est pas signée par la clé de l'APK à tester (secrets de signature absents ?) : mise à jour non testée"
    rm -rf "${DOSSIER:?}/$tag"
    exit 0
  fi
  echo "Version précédente : $tag ($precedent, code $code ; APK à tester : code $CODE)"
  if [ -n "${GITHUB_ENV:-}" ]; then
    { echo "SMOKE_PREVIOUS_APK=$precedent"; echo "SMOKE_PREVIOUS_VERSION=$tag"; } >>"$GITHUB_ENV"
  fi
  exit 0
done
avertir "aucune version publiée de $DEPOT que $(basename "$APK") (code $CODE) puisse mettre à jour : mise à jour non testée"
