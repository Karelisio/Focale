# Seuils du regroupement des visages

`calibrate.py` mesure, sur de vraies photos de visages, les seuils de similarité de
`FaceClusterer` (rattachement d'un visage à une personne, création d'une personne) et des
suggestions de fusion (`MergeSuggester`, « Est-ce la même personne ? »). Il n'est pas
nécessaire à la compilation.

## Données

- **LFW** (*Labeled Faces in the Wild*, University of Massachusetts,
  <https://vis-www.cs.umass.edu/lfw/>) : photos de presse de personnalités, sans licence
  explicite, diffusées pour la recherche. Version alignée 112 × 112 d'insightface (`lfw.bin`,
  65 Mo : les 12 000 images des 6 000 paires du protocole, alignées sur le gabarit ArcFace
  qu'utilise `FaceAlignment`), copie publique
  <https://media.githubusercontent.com/media/dmitriykim/oz_forensics_test/master/datasets/lfw.bin>.
- Liste officielle des paires (`pairs.txt`, protocole « View 2 »), qui nomme chaque image :
  <https://raw.githubusercontent.com/happynear/FaceDatasets/master/LFW/pairs.txt>.

Les deux fichiers sont téléchargés dans un dossier hors du dépôt (`--download`), empreintes
SHA-256 vérifiées. `lfw.bin` est un pickle : il est lu sans aucun objet ni code (octets,
listes et booléens seulement). Aucune photo ni empreinte n'est versionnée.

## Usage

Python 3.10+ avec `numpy`, `onnxruntime` et `pillow` :

```bash
python3 -I tools/faces/calibrate.py --data /tmp/lfw --download
```

Les empreintes (8 min sur deux cœurs) sont mises en cache dans le dossier des données ;
les simulations prennent quelques minutes.

## Méthode

1. Empreintes SFace des 7 701 images distinctes (4 281 identités, dont 747 ont au moins
   3 images et 70 au moins 8) : modèle embarqué `sface_int8.onnx`, entrée RVB 0..255,
   empreinte normalisée, comme `SFaceModel`. Contrôle : exactitude du protocole LFW
   (10 plis) **99,43 ± 0,47 %**, conforme aux 99,42 % annoncés par OpenCV Zoo.
2. Distributions : paires de visages ; visage contre centroïde de k visages d'une personne
   (rattachement) ; centroïdes de deux groupes de k1 et k2 visages (fusion), tirés au hasard,
   et « scissions adverses » (les visages d'une identité coupés en deux par 2-moyennes, au
   moins 3 par moitié : ce qui sépare le plus une personne, lumière, âge, lunettes…). Pour de
   très grandes personnes (centaines de visages), similarité des identités moyennes,
   débruitée : la somme de k visages vaut k·m + bruit, et |m|² se lit dans la similarité
   moyenne de deux visages de l'identité.
3. Simulation : portage fidèle de `FaceClusterer` (vérifié identique à la version Kotlin
   sur 1 302 visages), lots de 16 photos et reprise des visages en attente comme
   `FaceAnalysisStep`, sur trois bibliothèques composées de proches (identités d'au moins
   6 à 10 images) et d'inconnus (identités d'une ou deux images), trois tirages chacune.

## Résultats

Paires de visages : rappel / faux positifs.

| Seuil | 0,363 (OpenCV) | 0,42 | 0,45 | 0,50 | 0,55 |
|---|---|---|---|---|---|
| Deux visages | 99,3 % / 0,16 % | 98,2 % / 0,017 % | 97,2 % / 0,005 % | 94,5 % / 0,0006 % | 88,9 % / 0,0001 % |
| Visage → centroïde (k = 3 à 8) | | ≥ 99,9 % / 0,04 à 0,06 % | ≥ 99,5 % / 0,013 % | ≥ 99,4 % / ≤ 0,002 % | ≥ 98,3 % / ≤ 0,001 % |

Moyenner des visages ne rend pas deux personnes distinctes plus éloignées, au contraire :
les empreintes SFace partagent une forte composante commune (norme 0,32 de leur moyenne), que
le bruit propre à chaque photo masque ; une fois ce bruit moyenné, les centroïdes de deux
inconnus se rapprochent. Faux positifs entre centroïdes de personnes distinctes :

| Seuil | 0,38 | 0,42 | 0,45 | 0,50 | 0,55 |
|---|---|---|---|---|---|
| Groupes de 3 et 3 visages | 0,31 % | 0,080 % | 0,027 % | 0,005 % | 0,0007 % |
| Groupes de 7 et 7 visages | 0,62 % | 0,30 % | 0,11 % | 0,040 % | < 0,01 % |
| Très grandes personnes (k → ∞) | | | 0,24 % | 0,062 % | 0,014 % |

Deux moitiés d'une même identité restent, elles, bien plus proches : 1er centile 0,70 (3 + 3
visages) à 0,91 (7 + 7) au hasard ; scissions adverses : minimum 0,534, 1er centile 0,615,
médiane 0,833.

Simulation du regroupement (visages mal rattachés, inconnus rattachés à une personne,
personnes « fourre-tout » dont l'identité majoritaire n'a pas la moitié des visages) :

| Rattachement / création | 20 proches, 50 % d'inconnus | 70 proches, 50 % d'inconnus | 150 proches, 60 % d'inconnus |
|---|---|---|---|
| 0,42 / 0,45 (anciens seuils) | 1,3 % mal rattachés | 10 % ; 2,7 fourre-tout | 26 % ; 18 % des inconnus ; 21 fourre-tout |
| 0,48 / 0,50 | 0 % | 0,3 % | 2,3 % |
| **0,50 / 0,50** | **0 %** | **0,2 %** | **0,9 % ; aucun fourre-tout** |
| 0,50 / 0,55 | 0 % | 0,2 % | 0,6 % |

Avec 0,42, chaque visage d'inconnu a 0,04 à 0,06 % de chances de passer le seuil face à
chaque personne : avec 150 personnes, des inconnus sont rattachés, le centroïde dérive vers
le « visage moyen » et attire d'autres inconnus (effet boule de neige). Le rappel des proches
y gagne même (99,5 % contre 91 %), leurs visages n'étant plus absorbés par ces personnes
fourre-tout. Suggestions de fusion dans la simulation à 0,50 / 0,50 (aucune scission vraie
sur LFW, toutes fausses) : 54 à 185 au seuil de 0,38, 3 à 5 à 0,50, aucune à 0,55.

## Seuils retenus

- **Rattachement 0,50** (`FaceClusterer.Config.attach`) : faux rattachement ≤ 0,002 % par
  personne comparée, soit moins de 1 % d'inconnus rattachés à tort dans une bibliothèque de
  100 personnes (critère), rappel ≥ 99,4 % des visages d'une personne connue ; pas d'effet
  boule de neige dans les simulations.
- **Création 0,50** (`Config.seed`) : deux visages en attente sont voisins à partir de
  0,50 (0,0006 % de paires d'inconnus) ; le groupe est ensuite épuré au seuil de
  rattachement. 0,55 réduit à peine les erreurs (0,6 % contre 0,9 % dans la plus grande
  simulation) mais ne garde que 89 % des paires d'une même personne : les personnes
  naîtraient plus tard.
- **Fusion 0,55** (`MergeSuggester.THRESHOLD`), critère : au plus une fausse suggestion pour
  5 000 paires de personnes distinctes (≤ 0,02 %), y compris pour de très grandes personnes,
  en retrouvant au moins 99 % des scissions adverses. Plus petit seuil qui y satisfait :
  0,54 (0,020 % pour k → ∞, 99,3 % des scissions) ; 0,55 : 0,014 % et 99,3 %. L'ancien
  seuil de 0,38 proposait 0,3 à 0,6 % des paires de personnes distinctes (une quinzaine de
  fausses suggestions pour 70 personnes dans la simulation).

Limites : LFW réunit des adultes photographiés par la presse, surtout de face. Des photos
personnelles (enfants qui grandissent, profils jusqu'à 35°, flou, contre-jour) donnent des
similarités plus basses pour une même personne, et une famille compte des visages plus
proches que des inconnus pris au hasard : les marges ci-dessus en tiennent compte (pas de
seuil au ras des critères), sans pouvoir les mesurer.
