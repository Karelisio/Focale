# Seuils de la détection des voyages

`yfcc_trips.py` calibre `TripDetector` (souvenirs de voyage) sur de vraies photos
géolocalisées et horodatées. Il n'est pas nécessaire à la compilation.

## Données

**YFCC100M** (*Yahoo Flickr Creative Commons 100M*) : métadonnées de 99,2 millions de photos
et vidéos Flickr publiées sous licences Creative Commons, hébergées par AWS Open Data
(*Multimedia Commons*) sous forme d'une base SQLite de 65 Go,
<https://multimedia-commons.s3-us-west-2.amazonaws.com/tools/etc/yfcc100m_dataset.sql>.
La base est lue en flux par plages HTTP (environ une heure), sans être stockée : seuls le
pseudonyme Flickr, la date de prise de vue (heure locale de l'appareil, comme l'EXIF), la
position et sa précision des médias géolocalisés sont gardés, hors du dépôt (46,3 millions
de médias, 581 000 utilisateurs, 800 Mo). Seules des statistiques agrégées sont publiées ici.

Utilisateurs retenus : au moins 300 médias situés à la ville près ou mieux (précision Flickr
≥ 11), datés de 2000 à 2014, sur au moins un an et 60 jours distincts, et moins de 5 % de
lignes illisibles (description trop longue, reportée dans une page de débordement) : 14 356
utilisateurs, 27,4 millions de médias ; l'analyse en tire 5 000 au hasard.

Limite : un photographe Flickr publie surtout ses sorties et ses voyages, moins sa vie
quotidienne qu'une galerie de téléphone ; le chez-soi y est moins photographié, les jours
sans photo plus nombreux. Les scénarios de `TripDetectorTest` complètent ces mesures.

## Usage

Python 3.10+ avec `numpy` :

```bash
python3 -I tools/trips/yfcc_trips.py extract --data /tmp/yfcc   # ~1 h, reprise possible
python3 -I tools/trips/yfcc_trips.py select --data /tmp/yfcc
python3 -I tools/trips/yfcc_trips.py analyze --data /tmp/yfcc --users 5000   # ~6 min
```

`analyze` compare l'ancienne détection (chez-soi par année civile, 100 km, un jour sans média
toléré, toute journée lointaine comptée) et la nouvelle, portage fidèle de `TripDetector.kt`,
puis la sensibilité de chaque seuil, un à la fois.

## Résultats (5 000 utilisateurs)

Voyages détectés par utilisateur et par an ; part de ceux d'un seul jour ; part de ceux de
trois semaines ou plus ; part de ceux vers une destination (cellule de 25 km) visitée au moins
trois fois dans l'année ; les années suivies d'un déménagement (chez-soi à plus de 100 km l'année
suivante), part des voyages vers la future adresse.

| Détection | Voyages / an | 1 jour | ≥ 21 j | Destination répétée | Vers la future adresse |
|---|---|---|---|---|---|
| Ancienne | 2,04 | 32,0 % | 0,6 % | 8,6 % | 26 % (2 288 / 8 960) |
| **Nouvelle** | **1,74** | **23,3 %** | **1,0 %** | **2,8 %** | **10 % (570 / 5 517)** |
| Chez-soi seul, sans autres lieux familiers | 1,92 | 22,9 % | 1,1 % | 6,1 % | 17 % |
| Lieux familiers dès 3 mois et 60 jours | 1,60 | 22,9 % | 1,0 % | 1,4 % | 8 % |
| 1 jour sans média toléré | 1,81 | 25,7 % | 0,6 % | 3,8 % | 10 % |
| 3 jours sans média tolérés | 1,70 | 22,0 % | 1,5 % | 2,2 % | 10 % |
| 50 km (au lieu de 60) | 1,76 | 22,9 % | 1,0 % | 2,8 % | 10 % |
| 80 km | 1,70 | 24,0 % | 1,1 % | 2,8 % | 11 % |
| 100 km | 1,65 | 24,7 % | 1,1 % | 2,9 % | 11 % |
| Journée seule dès 100 km (au lieu de 150) | 1,84 | 27,5 % | 1,0 % | 3,0 % | 11 % |
| Journée seule dès 200 km | 1,68 | 20,6 % | 1,1 % | 2,7 % | 10 % |

Deux suites de jours à plus de 100 km séparées par g jours sans aucun média (pas de retour
photographié à la maison) : part des cas où l'on est ensuite au même endroit (< 50 km).

| g | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|
| Cas | 24 162 | 9 940 | 5 706 | 4 060 | 3 415 | 2 767 | 1 837 |
| Même endroit | 52,5 % | 47,7 % | 43,0 % | 37,0 % | 33,0 % | 32,9 % | 29,2 % |

Séjours d'au moins 5 médias, tous à plus de 30 km des lieux familiers, par distance maximale,
par utilisateur et par an :

| Distance | 30-50 km | 50-60 | 60-80 | 80-100 | 100-150 | 150-300 | > 300 |
|---|---|---|---|---|---|---|---|
| Une journée | 0,138 | 0,057 | 0,089 | 0,071 | 0,108 | 0,145 | 0,475 |
| Deux jours ou plus | 0,024 | 0,013 | 0,027 | 0,032 | 0,072 | 0,168 | 1,024 |

## Seuils retenus et raisonnement

- **Lieux familiers** (`FAMILIAR_MONTHS = 4`, `FAMILIAR_SPAN_DAYS = 90`) : cellules de la
  grille de 25 km photographiées pendant au moins 4 mois distincts, étalés sur 90 jours, dans les
  12 mois autour du média. Domicile, résidence secondaire, lieu de travail d'un frontalier ou
  d'un habitant de grande banlieue, nouvelle adresse dès le déménagement : aucun n'est une
  destination. L'ancienne version (un seul chez-soi par année civile) faisait des voyages
  répétés vers ces lieux (8,6 % des voyages vers une destination vue au moins trois fois dans
  l'année, 2,8 % désormais) et, l'année d'un déménagement, un voyage sur quatre vers la future
  adresse. Trois mois suffiraient à écarter davantage de répétitions (1,4 %), mais des vacances
  au même endroit à Pâques et en été, ou six semaines à cheval sur trois mois, cesseraient
  d'être des voyages ; la fenêtre glissante suit un déménagement à toute date de l'année.
- **60 km pour un séjour d'au moins une nuit** (`MIN_DISTANCE_KM`, 100 auparavant) : un
  week-end à 80 km est un voyage ; les séjours de deux jours ou plus entre 60 et 100 km
  (0,06 par utilisateur et par an) n'étaient pas trouvés. Les distances de la vie courante
  (grande agglomération, travail à 45 km) sont couvertes par les lieux familiers ; sous 60 km,
  un séjour reste une sortie.
- **150 km pour une seule journée** (`MIN_DAY_TRIP_KM`) : un aller-retour dans la journée à
  100 km n'est pas un voyage (un tiers des « voyages » de l'ancienne version tenait en un jour) ;
  au-delà de deux heures de route, ou en train à 300 km, si.
- **Deux jours sans média tolérés** (`MAX_GAP_DAYS`, un auparavant) : après deux jours sans
  photo, on est encore au même endroit une fois sur deux (47,7 %, contre 29 % après une
  semaine, quand on est rentré entre-temps) : un jour de route et un jour de repos ne coupent
  plus un voyage en étapes. Trois jours fusionneraient davantage de voyages distincts pour un
  gain moindre.
- **5 médias** (`MIN_PHOTOS`) et **grille de 25 km** (`HOME_CELL_KM`) inchangés.
