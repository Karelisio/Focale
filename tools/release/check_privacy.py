#!/usr/bin/env python3
"""Vérifie qu'un APK release n'embarque aucune télémétrie de ses bibliothèques.

ONNX Runtime déclare un fournisseur (`ai.onnxruntime.TelemetryInitializer`) qui crée au
démarrage son client d'envoi vers Microsoft ; ML Kit envoie ses événements par le backend
« cct » de datatransport. Le manifeste de l'application retire l'un et l'autre par leur nom :
si une mise à jour les renomme, le retrait ne s'applique plus, sans erreur. Ce contrôle fait
alors échouer la CI. Pour un APK `strict`, il vérifie aussi l'absence de toute permission
réseau (INTERNET, état du réseau et du Wi-Fi, multicast, réseau local) et du service de
diffusion vers les TV, propres à la variante standard.

Usage : check_privacy.py APK  (outil aapt2 du SDK Android requis)
"""

import os
import re
import subprocess
import sys

from check_constructors import build_tool

# Toute entrée d'ONNX Runtime dans le manifeste : seul son fournisseur de télémétrie en déclare.
ONNX_ENTRY = re.compile(r'"(ai\.onnxruntime\.[\w.$]+)"')
CCT_BACKEND = "com.google.android.datatransport.cct.CctBackendFactory"
# Permissions réseau : aucune dans la variante strict, même déclarée par une bibliothèque.
NETWORK_PERMISSIONS = [
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.CHANGE_WIFI_MULTICAST_STATE",
    "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.NEARBY_WIFI_DEVICES",
]
# Diffusion DLNA (serveur local, découverte SSDP) : variante standard seulement.
CAST_SERVICE = "app.focale.cast.CastService"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    dump = subprocess.run(
        [build_tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        check=True, capture_output=True, text=True,
    ).stdout
    problems = [f"entrée d'ONNX Runtime : {name}" for name in sorted(set(ONNX_ENTRY.findall(dump)))]
    if CCT_BACKEND in dump:
        problems.append(f"backend de télémétrie de ML Kit : {CCT_BACKEND}")
    if "-strict-" in os.path.basename(apk):
        problems += [f"permission {name} dans la variante strict" for name in NETWORK_PERMISSIONS if f'"{name}"' in dump]
        if f'"{CAST_SERVICE}"' in dump:
            problems.append(f"service de diffusion DLNA dans la variante strict : {CAST_SERVICE}")
    if problems:
        print(f"{apk} : télémétrie ou accès réseau inattendus :")
        print("\n".join(f"  {problem}" for problem in problems))
        sys.exit(1)
    print(f"{apk} : aucune télémétrie de bibliothèque dans le manifeste")


if __name__ == "__main__":
    main()
