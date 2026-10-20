#!/usr/bin/env python3
"""Vérifie qu'un APK release garde le constructeur des classes nommées dans son manifeste.

Les bibliothèques Google (ML Kit…) déclarent en méta-données des classes qu'elles
instancient par réflexion. Si R8 retire leur constructeur, l'application démarre mais la
fonctionnalité échoue sans bruit : ce contrôle fait échouer la CI à la place.

Usage : check_constructors.py APK  (outils aapt2 et dexdump du SDK Android requis)
"""

import os
import re
import subprocess
import sys
import tempfile
import zipfile

CLASS_NAME = re.compile(r"[A-Za-z_]\w*(?:\.[\w$]+){2,}")
DESCRIPTOR = re.compile(r"Class descriptor  : 'L([^;]+);'")
ACCESS = re.compile(r"Access flags\s*:\s*0x\w+ \(([^)]*)\)")


def build_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        sys.exit("ANDROID_HOME non défini")
    root = os.path.join(sdk, "build-tools")
    versions = sorted(os.listdir(root), key=lambda v: [int(p) if p.isdigit() else 0 for p in re.split(r"[.-]", v)])
    for version in reversed(versions):
        path = os.path.join(root, version, name)
        if os.path.exists(path):
            return path
    sys.exit(f"{name} introuvable dans {root}")


def manifest_classes(apk):
    dump = subprocess.run(
        [build_tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
        check=True, capture_output=True, text=True,
    ).stdout
    # Les méta-données ML Kit s'écrivent « com.google.firebase.components:<classe> ».
    return {part for value in re.findall(r'"([^"]+)"', dump) for part in value.split(":") if CLASS_NAME.fullmatch(part)}


def dex_classes(apk):
    """Classe → (concrète, a un constructeur) pour chaque classe des fichiers dex."""
    classes = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            path = archive.extract(entry, tmp)
            dump = subprocess.run([build_tool("dexdump"), path], check=True, capture_output=True).stdout
            text = dump.decode("utf-8", errors="replace")
            starts = [(m.start(), m.group(1)) for m in DESCRIPTOR.finditer(text)]
            for i, (start, name) in enumerate(starts):
                chunk = text[start:starts[i + 1][0] if i + 1 < len(starts) else len(text)]
                flags = ACCESS.search(chunk)
                concrete = flags is None or not ({"INTERFACE", "ABSTRACT"} & set(flags.group(1).split()))
                direct = chunk.split("Direct methods")[1].split("Virtual methods")[0] if "Direct methods" in chunk else ""
                classes[name.replace("/", ".")] = (concrete, "'<init>'" in direct)
    return classes


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    classes = dex_classes(apk)
    missing = sorted(
        name for name in manifest_classes(apk)
        if name in classes and classes[name][0] and not classes[name][1]
    )
    if missing:
        print(f"{apk} : classes du manifeste sans constructeur (règle R8 manquante) :")
        print("\n".join(f"  {name}" for name in missing))
        sys.exit(1)
    print(f"{apk} : constructeurs des classes du manifeste présents")


if __name__ == "__main__":
    main()
