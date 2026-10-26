# Focale — Performances

Ce document consigne les tests de charge de Focale sur une bibliothèque de 50 000 médias, les
points chauds qu'ils ont révélés et leurs corrections, les plans d'exécution surveillés, une
estimation de la durée de l'analyse et le profil de référence (« baseline profile »). Il complète
le principe « Fluide avec 50 000+ médias » de [ARCHITECTURE.md](ARCHITECTURE.md) (§ 1).

---

## 1. Lancer les mesures

```
./gradlew testStandardDebugUnitTest --tests 'app.focale.perf.*' --tests 'app.focale.core.database.QueryPlanTest'
```

Les tests de charge (`app/src/test/java/app/focale/perf/`) font partie de la suite ordinaire,
CI comprise : environ 60 s sur la machine de mesure (§ 3), dont 20 à 25 s pour les files de
l'analyse (compteur affiché pendant 40 lots compris), 18 à 20 s pour le défilement et 11 s pour
la sauvegarde des données, sur près de 130 s pour toute la suite unitaire. Aucune propriété
Gradle n'est donc nécessaire.

| Classe | Ce qu'elle mesure |
|---|---|
| `MediaSyncLoadTest` | `MediaSyncManager` depuis un faux MediaStore (`FakeMediaStore`, fournisseur Robolectric de l'autorité `media`) : synchronisation complète, index des noms écrit ensuite en arrière-plan (première page de la timeline et recherche par nom pendant ce temps), puis incrémentale (300 modifiés, 150 ajoutés, 50 supprimés), puis sans changement |
| `TimelineScrollLoadTest` | pages Paging de la timeline (continu et sauts, `initialKey`, nombre total de chaque source), en-têtes (`insertSeparators`), histogramme de la bulle et position dans la grille (comparés aux en-têtes, sur dix et trente ans), position dans la visionneuse, dossiers, compteurs, recherche par étiquette, par texte (début de mot, repli au milieu d'un mot), par nom de fichier, par lieu, par mois de toutes les années, par une étiquette ou une personne seule (résultats vérifiés), onglet Recherche (étiquettes vérifiées), écriture d'un lot de textes, migration 8 → 9 (clés des textes, index du texte) |
| `AnalysisQueueLoadTest` | sélection et enregistrement des 2 078 lots de l'analyse principale, contrôle des comptes du début de chaque passage (§ 5.17), sélection des lots des étapes (texte, visages, empreintes) et compteurs d'avancement, au fil de l'avancement ; compteur affiché pendant 40 lots écrits en 2 s, relu à chaque lot ou échantillonné (§ 5.6) |
| `AnalysisComputeLoadTest` | calculs par photo exécutables sur la JVM : dHash et netteté, géocodage, alignement et SFace, prétraitement et TinyCLIP, recherche par le sens, regroupement des visages |
| `DataBackupLoadTest` | sauvegarde des données de toute la bibliothèque (20 000 visages et leurs empreintes, albums, historique), vérification de l'archive, restauration sur un autre téléphone (autres `_ID`, dossiers en partie déplacés) : durées et mémoire retenue |
| `core/database/QueryPlanTest` | `EXPLAIN QUERY PLAN` des requêtes chaudes, sur le SQL réellement émis par Room (§ 6) |

Chaque mesure est affichée (lignes `PERF` des rapports de test) et ajoutée à
`app/build/reports/performance/mesures.tsv`. Elle est comparée à un **budget** d'environ cinq
fois la médiane mesurée ici (quelques millisecondes au moins) : assez large pour une CI chargée,
assez serré pour signaler le retour d'un point chaud corrigé (par exemple une page de la
timeline à 95 ms au lieu de 3, ou un lot de l'analyse à 25 ms au lieu de 1). Deux tests vérifient
en plus que le coût d'un lot de l'analyse ne croît pas avec l'avancement.

Base de données : Room comme en production (`AppModule.database` : fichier sur disque, journal
WAL, migrations, exécuteurs de Room), sous Robolectric (`@Config(sdk = [34])`) dont le SQLite natif
est la version **3.32.2** (celle d'Android 12 et 13 ; Android 10 a la 3.22, Android 14 la 3.39).

---

## 2. Bibliothèque de synthèse

`SyntheticLibrary` (graine fixe, construite une fois par processus de test) imite un téléphone
réel : dix ans de prises de vue de plus en plus nombreuses (trois fois plus à la fin qu'au
début), groupées en sorties de quelques dizaines de photos ; identifiants dans l'ordre d'arrivée
dans MediaStore, avec des trous, les photos des anciens téléphones arrivées d'un bloc.

| Caractéristique | Valeur |
|---|---|
| Médias | 50 000, dont 0,3 % dans la corbeille système |
| Dossiers | 298 : appareil photo (36 604 médias), captures, WhatsApp, téléchargements, 293 albums (loi de Zipf) |
| Vidéos / captures d'écran / GIF | 5,0 % / 8,1 % / 0,45 % |
| Sans `DATE_TAKEN` (date de tri : modification) | 23 % (captures, WhatsApp, téléchargements) |
| Géolocalisés | 30,7 % : 30 villes, chez soi d'abord, lieux et clés tels que les écrit l'analyse (géocodeur et noms traduits de la base embarquée) |
| Étiquettes | 3,15 par média (dictionnaire ML Kit, loi de Zipf) |
| Texte reconnu | 8 512 photos sur 47 377 (captures, panneaux, factures et tickets) |
| Visages | 19 011 sur 11 489 photos, 40 personnes (15 nommées), 15 % sans personne |
| Favoris | 2 % |

Les empreintes TinyCLIP (2 Ko par photo, 95 Mo) ne sont pas écrites en base ; la recherche par
le sens est mesurée en mémoire (§ 4.4).

---

## 3. Machine et méthode

- Intel Xeon à 2,1 GHz, 4 cœurs virtuels (AVX2, AVX-512), 16 Go ; Linux 6.18 ; OpenJDK 21.0.12.
- Robolectric 4.17 (SDK 34), Room 2.8.5, Paging 3.5.3, ONNX Runtime 1.31.0 pour la JVM.
- **Machine partagée** avec d'autres compilations : les durées varient d'un passage à l'autre
  (jusqu'à × 2). Chaque valeur ci-dessous est la médiane des répétitions d'un passage, puis la
  médiane de plusieurs passages : 2 « avant » (anciens index et anciennes files), 5 « après »,
  dont 2 sur le code fusionné avec les lieux traduits, le redressement et la sauvegarde
  automatique du coffre (3 pour les compteurs d'avancement, modifiés en dernier). Corrections
  des § 5.5 à 5.9 (version 7) : 2 passages sur la 1.9.0 (« 1.9.0 » ci-dessous), 3 après.
  Corrections des § 5.10 à 5.13 (version 8) : 3 passages sur la 1.10.0 (« 1.10.0 » ci-dessous),
  alternés avec 3 après, la machine étant plus chargée (charge moyenne de 3 à 7) : les durées
  absolues varient davantage, les rapports peu ; la recherche, 3 passages alternés de plus sur
  une machine moins chargée (charge de 1,4 à 2,6), dont les médianes sont reprises ici.
  Corrections des § 5.14 à 5.17 (version 9) : 5 passages sur la 1.11.0 (« 1.11.0 » ci-dessous),
  alternés avec 5 après (3 de toute la suite, 2 du seul défilement), sur une machine chargée par
  deux autres compilations (charge moyenne de 2 à 8) : étendues larges, médianes reprises ici.
- Les temps sont ceux d'une JVM de bureau, pas d'un téléphone : voir les limites (§ 8).

---

## 4. Résultats

### 4.1 Synchronisation MediaStore → Room

| Opération | Avant | Après |
|---|---|---|
| Complète, 50 000 médias (premier lancement) | 1,5 s (0,8 à 2,3) | **1,0 s** (0,98 à 1,43 ; 1.11.0 : 1,19 à 1,55, mots des noms compris, § 5.11), puis 0,28 à 0,39 s d'index des noms en arrière-plan (§ 5.16) |
| Incrémentale : 300 modifiés, 150 ajoutés, 50 supprimés | 300 ms | 295 ms (270 à 660) |
| Sans changement (retour au premier plan) | 81 ms | 66 ms |

La synchronisation n'avait pas de point chaud : l'incrémentale lit les seules lignes modifiées
(`GENERATION_MODIFIED`), puis les 50 000 identifiants pour les suppressions, et purge les
métadonnées orphelines (`NOT IN`, par la clé primaire). Les nouveaux index composés ne la
ralentissent pas de façon mesurable ; les mots des noms de fichiers (§ 5.11), écrits avec chaque
média, n'ajoutent rien de mesurable à l'incrémentale ; la première indexation les laisse à
l'arrière-plan (§ 5.16) ; les comptes des étiquettes (§ 5.10) n'ajoutent rien sans étiquette. Le coût de MediaStore lui-même (IPC, `CursorWindow`)
n'est pas mesuré.

### 4.2 Défilement, grilles et recherche

| Opération | Avant | Après |
|---|---|---|
| Page suivante (120 médias), médiane des 413 pages de la timeline | 95 ms | **2,9 ms** |
| 20 dernières pages (décalage proche de 50 000) | 91 ms | **3,3 ms** |
| Chargement initial (360 médias + nombre total) | 121 ms | **7,7 ms** (1.9.0 : 55 à 57) |
| Saut de la barre de défilement (nouvelle `PagingSource`, 360 médias + nombre total) | 172 ms | **6,2 ms** (1.9.0 : 61 à 66) |
| … dont le nombre total : `COUNT(*)` de la requête triée (Room) → par l'index (§ 5.5) | 71 ms | **1,8 ms** (Room : 50 à 81) |
| Saut tout au bout / filtre Photos | 188 / 180 ms | **9,1 / 7,4 ms** (1.9.0 : 53 à 54 / 52 à 57) |
| Liste des dossiers (onglet Albums, couvertures comprises) | 2 854 ms | **92 ms** |
| Dossier de 36 604 médias, saut au bout / petit dossier | 133 / 18 ms | 8,8 / 0,9 ms (1.9.0 : 43 à 48 / 0,8 à 1,0) |
| Compteur de la timeline / du filtre Vidéos | 2,2 / 9,4 ms | 2,1 / 0,6 ms |
| Histogramme mensuel (bulle de date, § 5.7) | 124 ms | **4,5 ms** (1.9.0 : 91 à 101) |
| Position d'un média (ouverture de la visionneuse) | 11 ms | 3,6 ms |
| Position dans la grille avec en-têtes de jour (retour de la visionneuse, § 5.7) ; média le plus ancien (§ 5.13) | 180 ms | **11 ms** ; 14 ms (1.9.0 : 134 à 149 ; 1.10.0 : 12 à 19 pour le plus ancien) |
| … média le plus ancien d'une bibliothèque de trente ans, vingt premières années clairsemées (§ 5.13) | — | **20 ms** (1.10.0 : 34 à 40) |
| En-têtes de jour (`insertSeparators`) sur 50 000 médias | 25 à 50 ms | (inchangé) |
| Conversion de 50 000 `MediaEntity` en `MediaItem` | ≈ 50 ms | (inchangé) |
| Recherche « chien » (races comprises, 5 004 résultats) : nombre / première page (§ 5.9, 5.11, 5.14) | 75 / 170 ms | **15 / 47 ms** (1.9.0 : 96 à 107 / 164 à 200 ; 1.10.0 : 60 à 94 / 106 à 135 ; 1.11.0 : 38 à 69 / 96 à 184) |
| Recherche « facture » (nom de fichier ou texte reconnu) : nombre / première page (§ 5.14) | 68 / 162 ms | **6,7 / 18 ms** (1.9.0 : 108 à 139 / 146 à 149 ; 1.10.0 : 60 à 87 / 93 à 100 ; 1.11.0 : 28 à 41 / 64 à 90) |
| Recherche « shot » (« Screenshot_… », nom de fichier ou texte reconnu, 4 023 résultats) : nombre / première page (§ 5.11, 5.14) | — | **11,5 / 20 ms** (1.10.0 : 45 à 56 / 70 à 81 ; 1.11.0 : 29 à 40 / 54 à 104) |
| Texte « fact » / « wifi » (nom de fichier ou début d'un mot du texte, 1 452 / 2 572 résultats) : nombre / première page (§ 5.14) | — | **4,5 / 15 ms ; 6,2 / 17 ms** (1.11.0 : 23 à 35 / 51 à 72 ; 25 à 37 / 52 à 72) |
| Texte « ture » (milieu de mot : rien d'autre ne répond, repli sur `LIKE`, 1 452 résultats) : nombre (§ 5.14) | — | 24 ms (23 à 26 ; 1.11.0 : 24 à 32) |
| Lieu « malo » seul (début d'un mot du lieu, 314 résultats) : nombre (§ 5.15) | — | **1,4 ms** (1,1 à 2,4 ; 1.11.0 : 5,8 à 10,5) |
| Recherche « juillet » (mois de toutes les années, 3 767 résultats) : nombre / première page (§ 5.12) | — | **12 / 18 ms** (1.10.0 : 73 à 85 / 84 à 100) |
| Étiquette seule (ouverte depuis l'onglet Recherche) / personne : nombre, `Flow` compris | — | 24 / 12 ms (SQL seul : 24 → 11 ms) |
| Onglet Recherche : étiquettes les plus présentes / lieux (§ 5.8, 5.10) | 376 / 96 ms | **1,2** / 30 ms (1.9.0 : 364 à 426 / 28 à 39 ; 1.10.0 : 90 à 106 / 29 à 38), au plus une fois par 5 s |

En-têtes et conversion des pages s'exécutent sur le fil principal (transformations de
`PagingData` collectées dans `viewModelScope`) : 0,1 ms environ par page de 120 médias.

### 4.3 Analyse : coût base de données

Analyse principale, 2 078 lots de 24 (`AnalysisWorker.BATCH_SIZE`) sur toute la bibliothèque :

| Opération | Avant | Après |
|---|---|---|
| Sélection d'un lot, au début / à la fin de l'analyse | 23 / 17 ms (tri de toute la bibliothèque à chaque lot) | **0,8 / 0,6 ms** |
| Sélection des 2 078 lots | ≈ 42 s (estimé, 2 078 × 20 ms) | **1,5 s** (mesuré) |
| Enregistrement d'un lot (analyse et étiquettes) | (inchangé) | 3,7 ms, comptes des étiquettes et des lieux compris (7,8 à 10,2 s pour les 2 078 ; 1.11.0 : 3,2 ms, 7,3 à 7,6 s ; 1.10.0 : 2,3 ms, 5,0 s ; § 5.10, 5.15) |
| Premier lot d'un passage (sans curseur), à 0 / 49 / 94 % | 23 / 19 / 17 ms | 2,6 / 31 / 87 ms |
| Compteur d'avancement (`pendingCount`), une lecture, à 0 / 49 / 94 % | 9 / 19 / 25 ms | 13 / 17 / 15 ms |
| Compteur affiché pendant 40 lots écrits en 2 s (§ 5.6) : lectures, temps de lecture | 41 lectures, 595 à 661 ms (1.9.0, une par lot) | **2 lectures, 25 à 35 ms** |
| … fin de la file affichée après | 3 ms | 14 à 23 ms (une lecture, sans attendre les 3 s) |

Étapes complémentaires, lots de 16 (`STEP_BATCH_SIZE`) :

| Étape | Lot suivant (curseur), de 50 à 95 % | Premier lot d'un passage, de 0 à 95 % | Sélection de tous les lots | Avancement observé |
|---|---|---|---|---|
| Texte (2 961 lots) | 0,9 à 1,3 ms | 56 à 139 ms | 3,3 s | 17 à 54 ms |
| Visages (2 695 lots) | 0,7 à 0,8 ms | 1 à 140 ms | 2,0 s | 12 à 61 ms |
| Empreintes (2 961 lots) | 0,7 ms | 0,8 à 171 ms | 2,1 s | 13 à 75 ms |

L'« avancement observé » est le coût d'une lecture ; affiché, chacun est désormais lu au plus
toutes les 3 s au lieu d'une fois par lot de son étape (§ 5.6). Un lot de 16 textes lus s'écrit en
4,6 à 14 ms, index du texte compris (1.11.0 : 3,7 à 7,1 ms ; § 5.14), à côté de 2 à 8 s de
reconnaissance.

Début de chaque passage : `AnalysisDao.checkCounts` compare les comptes des étiquettes et des
lieux aux lignes qu'ils comptent (22 à 32 ms, 93 à 110 ms de plus s'il faut les recalculer ;
§ 5.17), puis `refreshPlaceKeys` recalcule les clés de recherche des lieux (noms traduits) ; rien
n'est réécrit d'ordinaire (12 ms pour les 15 300 médias géolocalisés), et chaque lieu dont la clé
change l'est une fois après une nouvelle base de lieux (49 ms ici pour 16 lieux : une requête par
lieu, sur les médias de son pays ; les comptes des lieux sont alors recalculés, quelques ms).

Sans curseur, chaque lot de ces étapes relisait depuis le début les photos déjà traitées : avec
les anciens index, un tri de toutes les photos par lot (≈ 18 ms, soit près d'une minute par
étape) ; avec les nouveaux, un coût proportionnel à l'avancement (140 à 170 ms par lot à
95 %, plusieurs minutes par étape). Le premier lot d'un passage garde ce coût, une fois toutes
les six minutes au plus.

### 4.4 Analyse : calculs par photo (JVM de bureau)

| Calcul | Durée | Fréquence |
|---|---|---|
| dHash et netteté (luminance 384 × 288, laplacien, empreinte 9 × 8) | 1,1 ms | par média, hors réduction du bitmap (Android) |
| Géocodage : lieu le plus proche | 11 µs | par média géolocalisé (10 000 en 112 ms) |
| Chargement de la base des lieux (171 000 lieux) | 69 ms | par passage d'analyse |
| Chargement des 4 471 noms traduits des lieux (`PlaceNames`) | 5 ms | une fois par processus |
| Alignement d'un visage (112 × 112) et netteté | 0,5 ms | par visage |
| Empreinte SFace (int8, 2 fils) | 57 ms | par visage |
| Chargement de SFace / de TinyCLIP image | 315 / 46 ms (bibliothèque native d'ONNX Runtime comprise au premier) | par passage |
| Prétraitement CLIP (512 × 384 → 224 × 224) | 4,4 ms | par photo |
| Empreinte TinyCLIP (8 bits, 2 fils) | 26 ms | par photo |
| Empreinte d'une phrase de recherche | 4 ms | par recherche |
| Comparaison à 47 500 empreintes (un fil) | 27 ms | par recherche (quatre tranches en parallèle dans l'application) |
| Regroupement d'un lot de 26 visages (40 personnes, 2 000 visages en attente) | 3,6 ms | par lot de l'étape « visages » |

### 4.5 Estimation de la durée totale de l'analyse (50 000 médias)

ML Kit (étiquettes, texte, visages), la lecture des miniatures MediaStore, le décodage des photos
et les EXIF ne s'exécutent pas sur la JVM : leurs durées ci-dessous sont des **hypothèses**
(ordres de grandeur d'un téléphone de milieu de gamme), pas des mesures. Les autres parties
reprennent les mesures du § 4.4, à multiplier par 1 à 3 environ sur un téléphone.

| Analyse | Médias | Par média | Total |
|---|---|---|---|
| Principale : miniature 512 px (10 à 100 ms), étiquetage (20 à 50 ms), EXIF et lieu (2 à 10 ms), dHash et netteté (1 à 5 ms), base (0,1 ms) | 50 000 | 35 à 165 ms | 0,5 à 2,3 h |
| Texte : photo réduite à 1 600 px (30 à 80 ms), reconnaissance (100 à 400 ms) | 47 377 | 130 à 480 ms | 1,7 à 6,3 h |
| Visages : photo réduite à 1 280 px (30 à 80 ms), détection (50 à 200 ms), 0,4 visage × (alignement + SFace, 30 à 150 ms) | 43 000 | 90 à 340 ms | 1,1 à 4,1 h |
| Empreintes : miniature (10 à 100 ms), prétraitement et TinyCLIP (30 à 90 ms) | 47 377 | 40 à 190 ms | 0,5 à 2,5 h |
| **Ensemble** | | | **4 à 15 h de calcul** |

WorkManager découpe ce travail en passages de six minutes au plus, sous ses contraintes
(batterie non faible, en charge si l'option est active) : une bibliothèque de 50 000 médias
demande donc plusieurs nuits en charge. Les corrections du § 5 retirent de ce total plus de
trois minutes de requêtes (sur la JVM de bureau : 42 s pour l'analyse principale, près d'une
minute par étape, contre 9 s en tout désormais), un coût qui croissait avec le carré de la
taille de la bibliothèque ; le reste du coût propre à Focale (base, calculs Kotlin) est
négligeable devant ML Kit et ONNX Runtime.

### 4.6 Sauvegarde des données

La bibliothèque de synthèse est complétée comme par l'utilisateur : 20 000 visages (989 de plus
que l'analyse simulée), 40 personnes, 30 albums virtuels (6 000 photos environ, couverture
choisie), 500 dates d'origine, 50 recherches, 3 dossiers masqués. La restauration a lieu sur un
autre téléphone : mêmes fichiers sous d'autres `_ID`, un dossier d'album sur vingt déplacé
(appariement par nom, taille et date). Médianes de trois passages.

| Opération | Avant | Après |
|---|---|---|
| Sauvegarde : 114 174 enregistrements, archive de 11,7 Mio | 7,8 s | 2,0 s (1,9 à 2,2) |
| Vérification de l'archive : mot de passe, intégrité, résumé | — | 0,3 s |
| Vérification depuis la copie locale : copie du document comprise, une seule lecture | — | 0,4 s |
| Restauration : 43 490 médias retrouvés, 20 000 visages | 3,0 s | 2,3 s (2,2 à 2,5) |
| Mémoire retenue pendant la sauvegarde | — | 5 Mio |
| Mémoire retenue pendant la restauration | — | 14 à 17 Mio |

Mémoire retenue : tas occupé après ramasse-miettes, relevé à mi-parcours et vers la fin de
l'opération, au-delà de celui d'avant (`HeapProbe`) ; le temps des relevés est retiré des durées.
La sauvegarde ne garde que les identifiants des médias écrits (8 octets chacun) et une tranche de
lignes ; la restauration, les clés des médias du téléphone et de la sauvegarde (40 octets par
média et par côté), leurs identifiants et un lot de 500 visages. La mémoire suit le nombre de
médias, ni celui des visages ni la taille de l'archive : les empreintes seules occupent 10 Mio,
le contenu décompressé plus de 20. Budgets : 10 s, 2 s et 12 s ; 12 Mio et 28 Mio.

---

## 5. Points chauds trouvés et corrigés

### 5.1 Index de la corbeille : tri de toute la bibliothèque à chaque page

Sans statistiques (`ANALYZE`, que Room ne lance pas), SQLite estime qu'une égalité sur une
colonne indexée ne retient qu'une dizaine de lignes. L'index sur `is_trashed` seul gagnait donc
contre l'index `date_taken` pour toute requête `WHERE m.is_trashed = 0 … ORDER BY m.date_taken`,
puis triait **toute la bibliothèque** (`USE TEMP B-TREE FOR ORDER BY`) : à chaque page de la
timeline (95 ms), à chaque saut, à chaque lot de l'analyse et, dans la liste des dossiers, une
fois par dossier pour sa couverture (300 parcours de 50 000 lignes : 2,9 s).

Correction (version 6 de la base, `DatabaseMigrations.CHANGES_V6`, `DatabaseMigrationsTest`) : des index composés qui
finissent par la date remplacent les index simples de `is_trashed`, `bucket_id` et `media_type` :

| Index | Requêtes servies dans leur ordre, sans tri |
|---|---|
| (`is_trashed`, `date_taken`) | timeline (dossiers masqués compris), compteurs, histogramme, position, analyse principale |
| (`bucket_id`, `is_trashed`, `date_taken`) | grille d'un dossier, couverture de chaque dossier |
| (`media_type`, `is_trashed`, `date_taken`) | filtres Photos et Vidéos, compteurs des filtres, files des étapes (photos seules) |

L'index `date_taken` seul est gardé. Les requêtes qui lisent toute la table avec une jointure
(compteurs d'avancement) seraient devenues plus lentes en suivant l'index trié par date, qui lit
les lignes au hasard : elles le contournent (`+m.is_trashed`) pour parcourir `media` et la table
de l'étape dans l'ordre des identifiants. Effet de bord accepté : le nombre de résultats d'une
recherche, qui parcourt aussi toute la timeline, passe de 70 à 105 ms (même raison ; corrigé au
§ 5.9).

### 5.2 Files de l'analyse : coût quadratique

Chaque lot relisait la file depuis le début. Avec l'ancien index, cela triait toute la
bibliothèque par lot ; avec l'index composé, cela parcourt tous les médias déjà analysés : un coût
par lot proportionnel à l'avancement, donc un coût total quadratique : de 50 à 100 millions de
lignes lues pour l'analyse principale de 50 000 médias, selon l'index.

Correction : chaque passage parcourt sa file une fois. `AnalysisQueue` (analyse principale) et
`AnalysisStep.pendingAfter` (texte, visages, empreintes) reprennent après le dernier média du lot
précédent (`AnalysisDao.pendingAfter`, `TextDao.pendingAfter`, … : borne sur `date_taken`, ordre
total avec l'identifiant). Le texte garde son ordre, captures d'abord : la reprise lit les
captures, puis les autres photos. Un média ajouté ou modifié pendant un passage, plus récent que
le curseur, attend le passage suivant, que le worker programme déjà s'il reste du travail.
Tests : `AnalysisQueueTest` (ordre, égalités de dates, reprise), `AnalysisQueueLoadTest`.

### 5.3 Timeline relancée à chaque lot de l'analyse

La timeline et les dossiers observaient aussi favoris, albums, étiquettes et analyse : pendant
les heures de la première analyse, chaque lot (toutes les quelques secondes) rechargeait leurs
pages, leur nombre total et leur histogramme (≈ 0,3 s de requêtes par lot sur la JVM de bureau
avec les anciens index, encore 0,16 s avec les nouveaux).
Correction : une requête qui ne lit que `media` (`MediaQueryBuilder.readsOnlyMedia`) est observée
sur cette seule table (`MediaDao.observeCountOfMedia`…, pages et histogramme par les tables de
`MediaRepository.Observed`).
Test : `MediaObservationTest`.

### 5.4 Sauvegarde des données : chaque tranche relisait la bibliothèque

Les lectures par tranches de la sauvegarde (`BackupDao` : médias désignés, passages de la
détection, visages, contenu des albums ; médias du téléphone à la restauration) écartent la
corbeille (`m.is_trashed = 0`) et reprennent après la clé de la tranche précédente. Comme au
§ 5.1, SQLite préférait l'index (`is_trashed`, `date_taken`) à la clé primaire, puis triait toute
la bibliothèque par identifiant (`USE TEMP B-TREE FOR ORDER BY`) à chaque tranche de 500 à 2 000
lignes : une centaine de tris de 50 000 lignes par sauvegarde.
Correction : `+m.is_trashed` écarte l'index et chaque tranche se lit par la clé primaire
(`rowid>?`), le contenu des albums par sa clé composée en valeur de ligne
(`(album_id, media_id) > (?, ?)`, SQLite 3.15 et plus : Android 10 a la 3.22) ; la couverture
choisie des albums, cherchée album par album pour chaque média, est une liste lue une fois
(`IN`). Sauvegarde : 7,8 s → 2,0 s ; restauration : 3,0 s → 2,3 s. Test : `QueryPlanTest`.

### 5.5 Nombre total de chaque `PagingSource` : la requête triée relue ligne à ligne

`LimitOffsetPagingSource` (Room) compte `SELECT COUNT(*) FROM (requête triée)` à chaque ouverture
de source, donc à chaque saut de la barre de défilement et à chaque invalidation : une
sous-requête lue ligne entière, 50 à 80 ms sur 50 000 médias, quand le compteur de la timeline
passe par l'index seul en 2 ms.
Correction : `MediaPagingSource` reprend son fonctionnement (clé = position, `LIMIT`/`OFFSET`
dans l'ordre de la grille, placeholders, sauts, clé de rafraîchissement centrée, invalidation au
premier changement des tables de la requête par l'`InvalidationTracker` de Room, observé avant
le premier comptage) et compte par `MediaQueryBuilder.count`. Chargement initial et saut :
55 à 66 ms → 6 à 8 ms ; tout au bout : 53 → 9 ms. `room-paging` n'est plus une dépendance.
Tests : `QueryPlanTest` (nombre total par l'index seul, plus de `SELECT COUNT(*) FROM (`),
`MediaObservationTest` (tables observées), `TimelineScrollLoadTest` (pages, sauts, budgets).

### 5.6 Compteurs d'avancement relus à chaque lot

Chaque compteur d'avancement lit toute la bibliothèque (13 à 75 ms) ; tant qu'il était affiché
(onglet Recherche, réglages), Room le relisait à chaque lot de son étape, soit, pendant les
heures de la première analyse, une lecture toutes les quelques secondes par compteur.
Correction : `sampledQuery` relit une requête au plus une fois par intervalle (3 s,
`AnalysisScheduler`) : première valeur sans attendre, changements survenus pendant l'attente lus
par la lecture qui la termine, aucun perdu ; quand le worker vide une file ou finit son passage
(`AnalysisMilestones`), la lecture suit aussitôt, attente comprise : la fin s'affiche sans délai.
Pendant 40 lots écrits en 2 s (un téléphone en écrit un toutes les 1 à 4 s) : 41 lectures et
600 ms de requêtes → 2 lectures et 30 ms, fin affichée 15 à 25 ms après la dernière écriture.
Tests : `SampledQueryTest` (temps virtuel), `AnalysisQueueLoadTest` (nombre de lectures, fin).

### 5.7 Position dans la grille et histogramme : une date locale par média

L'histogramme (bulle de date) regroupait par `strftime('%Y-%m', …, 'localtime')`, la position
dans la grille au retour de la visionneuse comptait les jours distincts (`COUNT(DISTINCT
strftime(…))`) avant le média : une conversion en heure locale (tables de fuseau de la
bibliothèque C) pour chaque média lu, 91 à 101 ms et 134 à 149 ms.
Correction : les bornes des périodes (jours, mois, années) sont calculées en Kotlin dans le
fuseau courant (`DateGrouping.bounds` : minuit local du premier jour de chacune, jours de 23 et
25 heures compris), celles-là mêmes des en-têtes de la grille ; SQLite compte chaque période par
sa plage de `date_taken` dans l'index de la requête (`MediaQueryBuilder.histogram`, une table de
valeurs, 500 périodes par requête) ou vérifie qu'elle n'est pas vide (`periodsWithMedia`, pour
les seules périodes entre le média et le début de la grille). Histogramme : 4,5 ms ;
position : 11 ms, 21 ms au plus pour le plus ancien des 50 000 (3 500 jours sondés). Le résultat
ne dépend plus du fuseau de SQLite, qui pouvait différer de celui de la JVM.
Tests : `MediaPeriodsTest` (histogramme et position égaux à ceux de la grille, autour de minuit
et des changements d'heure, dans les deux ordres), `DateGroupingTest`, `QueryPlanTest`.

### 5.8 Étiquettes de l'onglet Recherche : tout regroupé, à chaque lot

La requête des étiquettes les plus présentes partait des médias (index de la corbeille), puis
regroupait les 157 000 lignes d'étiquettes dans un arbre temporaire ; la couverture de chaque
étiquette, cherchée pour toutes (et non les 60 affichées), triait toutes ses photos : 364 à
426 ms, relancés à chaque lot de l'analyse tant que l'onglet était affiché.
Correction (version 7 de la base, `DatabaseMigrations.MIGRATION_6_7`) : l'index (`label`,
`confidence`, `media_id`) remplace celui de `label` seul. Le comptage parcourt cet index dans
l'ordre des étiquettes, sans arbre temporaire, chaque ligne gardée si son média est hors de la
corbeille (liste de leurs identifiants ; « + » empêche SQLite de partir des médias) ; la
couverture n'est cherchée que pour les 60 retenues, première de l'index par confiance
décroissante. 364 à 426 ms → 96 à 131 ms. Étiquettes et lieux de l'onglet sont relus au plus
toutes les 5 s (`sampledQuery`, aussitôt à la fin d'une file), et rien ne change à l'écran
quand le résultat est le même. La recherche par étiquette lit le même index, sans ouvrir la table.
Tests : `QueryPlanTest`, `DatabaseMigrationsTest` (6 → 7 depuis `6.json`),
`TimelineScrollLoadTest` (résultat comparé à un comptage en Kotlin).

### 5.9 Nombre de résultats d'une recherche : toute la timeline, dans l'ordre des dates

Sans statistiques, SQLite croit l'égalité `is_trashed = 0` sélective : pour compter une
recherche, il parcourait toute la timeline par cet index, trié par date, donc ses lignes au
hasard, en évaluant chaque critère. Correction : avec un critère d'une autre table, le compte
écarte cet index (`+m.is_trashed`, `MediaQueryBuilder.count`) ; SQLite part alors des médias du
critère par la clé primaire (étiquette seule, personne, favoris : 24 → 11 ms, 13 → 1 ms), ou,
quand une alternative l'exige (nom de fichier, dans chaque mot saisi), lit `media` dans l'ordre
des identifiants : « chien » 96 à 107 → 71 ms, « facture » 108 à 139 → 67 ms. La première page,
qui comptait aussi, passe de 146 à 200 ms à 100 à 124 ms. Les pages gardent l'index des dates.
Tests : `QueryPlanTest`, `MediaQueryBuilderTest`.

### 5.10 Étiquettes de l'onglet Recherche : des comptes tenus à jour

Après le § 5.8, le comptage parcourait encore l'index des étiquettes en vérifiant, pour chacune des
157 000 lignes, que son média est présent et hors corbeille (liste de leurs identifiants) : 90 ms,
dont 10 pour le parcours lui-même. Correction (version 8 de la base, `MIGRATION_7_8`) : la table
`label_count` (étiquette → nombre de médias de l'index, hors corbeille), tenue à jour dans la
transaction de chaque écriture des médias (`MediaDao.upsert`, `deleteByIds` : synchronisation,
corbeille, accès partiel) et des étiquettes (`AnalysisDao.save`).
`LabelCountQueries.withLabelCounts` retire des comptes les étiquettes visibles des médias écrits
avant l'écriture, puis ajoute celles d'après : deux `INSERT OR REPLACE … SELECT … GROUP BY label`,
depuis la clé primaire de `media_label` (« + » écarte l'index de la corbeille, qui ferait lire
toute la timeline à chaque lot) ; rien sans aucune étiquette (première synchronisation). L'onglet
lit les 60 premières de `label_count`, puis leur couverture comme avant : 90 à 106 ms → 0,9 à 3 ms
(médiane 1,2). Coût : un lot de l'analyse s'enregistre en 3,1 ms au lieu de 2,3 (7,3 s au lieu de
5,0 pour les 2 078 lots, à côté d'heures de ML Kit). Écartée : soustraire du total les étiquettes
des médias absents ou dans la corbeille (fusion `EXCEPT` des deux index par identifiant) : 41 ms,
mais 165 ms quand l'index ne garde qu'une partie de la bibliothèque (accès partiel d'Android 14).
Tests : `LabelCountTest` (comptes égaux au recalcul complet après chaque type d'écriture),
`QueryPlanTest`, `DatabaseMigrationsTest` (7 → 8 depuis `7.json`), `TimelineScrollLoadTest`
(résultat comparé à un comptage en Kotlin, budget 20 ms).

### 5.11 Recherche par mot : les noms de fichiers par un index plein texte

Chaque mot cherche aussi dans le nom de fichier (`display_name LIKE '%…%'`), une alternative
qu'aucun index ne sert : le nombre de résultats lisait toute la table `media` (23 ms pour ce seul
`LIKE`), la première page l'évaluait pour chaque média parcouru. Correction (version 8) : l'index
plein texte FTS4 `media_name` (FTS5 n'est ni garanti dans le SQLite d'Android ni pris en charge par
Room), tokenizer `simple`, qui ne replie que la casse ASCII comme `LIKE`. FTS ne trouve que des
débuts de mots : chaque suite de lettres du nom y figure avec ses suffixes d'au moins trois lettres
(« Screenshot » : « Screenshot creenshot … shot hot », 2,6 mots par nom), si bien qu'un mot d'au
moins trois lettres ASCII contenu dans un nom commence l'un d'eux (`MATCH '"shot*"'`). Les
candidats sont revérifiés par le `LIKE` d'avant : aucun résultat perdu ni ajouté. Un texte plus
court, avec un chiffre, un séparateur ou une lettre non ASCII garde le `LIKE` sur chaque nom (les
mots de la saisie sont normalisés : `[a-z0-9]`). Toutes les alternatives d'un mot passent alors par
les médias de leur critère (`MULTI-INDEX OR`, clé primaire) : nombre de résultats de « chien »,
« facture » et « shot » : 62, 63 et 48 → 37, 31 et 33 ms ; première page, nombre compris : 119, 95
et 76 → 90, 60 et 55 ms. L'index suit `media` dans les mêmes écritures que `label_count` (§ 5.10) :
la synchronisation complète de 50 000 médias (premier lancement) passe de 0,9 à 1,25 s,
l'incrémentale ne change pas de façon mesurable ; la migration calcule les mots des médias déjà
indexés.
Tests : `MediaNameSearchTest` (mêmes résultats que `LIKE` pour les 667 mots de lettres des noms
d'essai : majuscules, accents, chiffres, longues suites de lettres ; renommage et retrait),
`QueryPlanTest`, `MediaQueryBuilderTest`, `TimelineScrollLoadTest` (« shot », comparé à un comptage
en Kotlin).

### 5.12 Mois de toutes les années : bornes calculées en Kotlin

Un mois de toutes les années (« juillet ») comparait `strftime('%m', …, 'localtime')`, calculé pour
chaque média : 73 à 85 ms pour le nombre de résultats de « juillet », 84 à 100 ms pour la première
page. Correction : comme au § 5.7, les bornes du mois de chaque année de 1900 à 2049 sont calculées
en Kotlin dans le fuseau courant (minuit local du premier jour, comme les en-têtes), puis cherchées
par dichotomie (`CASE` imbriqués, 9 comparaisons) sur la date lue dans l'index de la requête ; hors
de ces années (photos très anciennes, dates aberrantes), le mois local de SQLite, évalué pour ces
seuls médias. Nombre : 10 à 27 ms (médiane 12) ; première page : 16 à 23 ms (médiane 18). Écartées : une
alternative par année (`OR`, évaluées une à une : 63 ms pour 58 ans, 182 ms pour 200), une
sous-requête sur une table de valeurs (5 ms pour le nombre, mais la requête ne lirait plus
seulement `media`, § 5.3).
Tests : `MediaPeriodsTest` (fuseau de Paris, autour de minuit et des changements d'heure, aux deux
bouts des années bornées et au-delà), `MediaQueryBuilderTest`, `QueryPlanTest`,
`TimelineScrollLoadTest` (comparé au mois local de chaque média, budgets 50 et 60 ms).

### 5.13 Position du média le plus ancien : des suites de périodes, les mois d'abord

Au retour de la visionneuse, les en-têtes de jour avant un média étaient comptés par une recherche
dans l'index par jour, vide ou non, jusqu'au début de la grille : pour le plus ancien, 3 650 jours
en huit tables de valeurs de 500 lignes, plus longues à préparer (5 ms) qu'à parcourir.
Correction : les périodes sont regroupées en suites de même durée (`MediaQueryBuilder.periodRuns` :
une suite pour dix ans de jours en temps universel, 41 avec les changements d'heure de Paris)
qu'une table récursive (`WITH RECURSIVE`, SQLite 3.8.3 et plus : Android 10 a la 3.22) déroule :
une requête courte, 3 650 jours sondés en 5 ms.
Au-delà de deux mois de jours, les mois sans média sont écartés d'abord (une requête, 1 ms pour
121 mois) et seuls les jours des autres sont sondés : sans gain sur la bibliothèque de synthèse,
dense, mais une bibliothèque de trente ans aux vingt premières années clairsemées (deux photos par
an) n'a plus 7 300 jours vides à chercher. Plus ancien des 50 000 : 12 à 19 → 11 à 16 ms ; sur
trente ans : 34 à 40 → 20 à 24 ms.
Tests : `MediaPeriodsTest` (positions égales à celles de la grille, mois d'abord compris),
`MediaQueryBuilderTest`, `QueryPlanTest`, `TimelineScrollLoadTest` (dix et trente ans).

### 5.14 Texte reconnu dans la recherche par mot : un index plein texte, par débuts de mots

Chaque mot cherchait aussi dans le texte reconnu (`text_key LIKE '%…%'`), une alternative
qu'aucun index ne sert : la clé de chacun des 47 000 textes relue à chaque recherche, 16 à 33 ms
par mot (médiane 18), pour le nombre de résultats comme pour la première page, l'essentiel d'une
recherche après le § 5.11. Le SQLite d'Android n'a pas FTS5 (pas de tokenizer trigramme) ; FTS4
ne trouve que des débuts de mots, et les suffixes de chaque mot, comme pour les noms, feraient un
index trop gros (des dizaines de mots par capture d'écran).

Correction (version 9, `MIGRATION_8_9`) : l'index FTS4 `media_text_fts` des clés normalisées
(tokenizer `unicode61`, sans accents), à contenu externe : il ne garde que l'index (0,57 Mio pour
2,1 Mio de textes et de clés), tenu à jour par les déclencheurs que Room crée avec lui à chaque
écriture de `media_text`, purge des orphelines comprise. Un mot du texte doit commencer par le mot
cherché (`MATCH '"fact*"'` : « facture », « factures » ; 0,3 à 1 ms au lieu de 16 à 33). Quand rien
d'autre dans la bibliothèque ne répond au terme (aucun média hors corbeille par une étiquette, un
lieu, un pays, un nom de fichier, une personne ou un mot d'un texte qui commence ainsi), l'ancien
`LIKE` cherche le mot n'importe où dans les textes (`MediaTexts`) : « ture » trouve encore
« facture », « fi » « WIFI » quand rien d'autre ne correspond. Le repli dépend d'une sous-requête
d'une ligne au plus, boucle extérieure de sa jointure (`CROSS JOIN`) : vide dès qu'une autre voie
trouve un média, et `media_text` n'est alors pas lue (`QueryPlanTest`). La clé sépare aussi les
lettres et les chiffres collés d'un même mot (`RecognizedText.searchKey` : « #vacances2024 » →
« vacances2024 vacances 2024 »), si bien que « 2024 » trouve « CAFE2024 », et « 1234 » « AF1234 »,
comme avant ; la migration recalcule en Kotlin la clé des textes déjà lus, sans relire les photos
(0,39 à 0,58 s pour 47 377 textes, dont 8 512 non vides), puis construit l'index (63 à 100 ms).

Nombre de résultats et première page : « facture » 28 à 41 → 6 à 11,5 ms et 64 à 90 → 15 à 18 ms ;
« fact », « wifi » 23 à 37 → 3,6 à 7,7 ms et 51 à 72 → 10 à 39 ms ; « chien », qu'aucun texte ne
contient mais que des étiquettes trouvent (pas de repli), 38 à 69 → 12,5 à 27 ms et 96 à 184 → 40 à
52 ms. Un terme que rien d'autre ne trouve (« ture ») garde le `LIKE` : 23 à 26 ms, comme avant.
Coût : un lot de 16 textes lus s'écrit en 4,6 à 14 ms au lieu de 3,7 à 7,1 (déclencheurs de
l'index), à côté de 2 à 8 s de reconnaissance.

Résultats (`MediaTextSearchTest`, corpus de 40 textes de photos et de captures : tickets, factures,
réseaux Wi-Fi, billets, panneaux, notifications, ordonnance, RIB) : les cas courants, mots entiers,
débuts de mots, accents et majuscules (« FACTURÉ », « Été », « BRÛLÉE », « Œuf »), nombres
(« 2024 », « 2024118 », « 0612345678 », « 14h30 », « AF1234 ») et mots collés à la ponctuation
(« (facture) », « total: », « l'été », « e-mail », « contact@exemple.fr », « #plage »), trouvent les
mêmes photos qu'avant. Change un mot ou un début de mot contenu aussi au milieu d'un mot d'un autre
texte, quand quelque chose d'autre répond au terme : la photo où il n'est qu'au milieu n'est plus
trouvée. Sur les 790 mots et débuts de mots du corpus, 73 changent, dont 7 de quatre caractères ou
plus : « crème » ne trouve plus « demi-écrémé », « atte » (attestation) plus « batterie », « nive »
(niveau) plus « anniversaire », « emai » (email) plus « demain », « 1234 » et « 3456 » plus un
numéro qui les contient au milieu (« 06 12 34 56 78 ») ; les autres ont de un à trois caractères
(« sur » dans « assurance », « fac » dans « surface »). La condition du repli porte sur toute la
bibliothèque, pas sur les autres mots ni les filtres de la recherche : « fi » ne trouve pas
« wifi » dès qu'un autre texte a un mot qui commence par « fi » ou qu'un nom de fichier le
contient, même hors des résultats affichés.
Tests : `MediaTextSearchTest` (cas courants comparés au `LIKE` d'avant, toutes les sous-chaînes
des mots du corpus comparées au modèle « début de mot, sinon `LIKE` », repli selon les autres voies,
index tenu à jour), `RecognizedTextTest`, `TextSearchTest`, `QueryPlanTest`, `MediaQueryBuilderTest`,
`DatabaseMigrationsTest` (8 → 9 depuis `8.json`), `TimelineScrollLoadTest` (« fact », « wifi »,
« ture », comparés à un comptage en Kotlin ; budgets 25 et 60 ms).

### 5.15 Lieux dans la recherche par mot : les clés distinctes d'abord

Chaque mot cherche aussi parmi les lieux (`(' ' || place_key) LIKE '% mot%'`), évalué sur chacune
des 50 000 analyses (6 à 10 ms par mot) pour 30 clés distinctes. Correction (version 9) : la table
`place_count` (clé → nombre d'analyses qui la portent, corbeille et médias absents compris : la
recherche les écarte ensuite), tenue à jour comme `label_count` (`PlaceCountQueries.withPlaceCounts`
dans la transaction de `AnalysisDao.upsert`, donc de chaque lot) et recomptée en entier par l'index
de `place_key` (2 à 3 ms) quand beaucoup de lieux changent d'un coup : clés recalculées
(`refreshPlaceKeys`), purge des analyses orphelines. Le mot est cherché parmi les clés de compte non
nul, puis les médias des clés trouvées par l'index de `place_key` : lieu « malo » seul 5,8 à 10,5 →
1,1 à 2,4 ms. Un mot désignant un lieu de la bibliothèque (contrainte de la recherche par le sens,
`isPlaceWord`) lit la même table. Coût : un lot de l'analyse s'enregistre en 3,7 ms au lieu de 3,2
(deux écritures de plus). Tests : `PlaceCountTest` (comptes égaux au recalcul complet après chaque
type d'écriture, recherche comparée au `LIKE` sur chaque analyse), `PlaceSearchTest`,
`QueryPlanTest`, `DatabaseMigrationsTest`, `TimelineScrollLoadTest` (comparé à un comptage en Kotlin).

### 5.16 Première synchronisation : l'index des noms après, en arrière-plan

Les mots des 50 000 noms (§ 5.11) ajoutaient 0,35 s (+ 40 %) à la première indexation, que
l'utilisateur attend devant une timeline qui se remplit. Correction (version 9) : la première
indexation (index vide) écrit les médias sans leurs mots (`MediaDao.upsertNamesLater`) et note chacun
dans `media_name_pending` ; à la fin de la synchronisation, `MediaSyncManager.indexNames` les écrit
en arrière-plan, par lots de 1 000 dans leur propre transaction (nom lu et écrit dans la même : un
renommage entre-temps n'est pas écrasé). Ni la fin de la synchronisation ni la timeline, qui ne lit
pas ces tables, ne l'attendent ; un passage interrompu reprend à la synchronisation suivante. D'ici
là, les médias en attente sont candidats de la recherche par nom avec ceux de l'index, revérifiés par
le même `LIKE` : la recherche reste exacte, au prix d'un `LIKE` sur les noms en attente.
Synchronisation complète : 1,19 à 1,55 s → 0,98 à 1,43 s (médianes 1,39 et 1,0), puis 0,28 à 0,39 s
d'index des noms, pendant lesquels la première page de la timeline se charge en 20 à 50 ms.
Tests : `MediaSyncNamesTest` (première indexation sans les mots, recherche par nom exacte, média
ajouté ensuite indexé avec son nom), `MediaNameSearchTest` (mêmes résultats que `LIKE` avant,
pendant et après l'écriture des mots, renommage et retrait entre-temps), `MediaSyncLoadTest`.

### 5.17 Garde-fou des comptes tenus à jour

`label_count` et `place_count` ne sont justes que si toutes les écritures passent par les
méthodes prévues (`withLabelCounts`, `withPlaceCounts`) : une écriture qui les contourne (une
requête ajoutée plus tard, une restauration) les fausserait jusqu'à la réinstallation. Au début de
chaque passage d'analyse, `AnalysisDao.checkCounts` compare le total de chacun aux lignes qu'il
compte : étiquettes des médias de l'index hors corbeille (l'index de la clé primaire de `media_label`
seul, sans regroupement : 20 ms, deux à trois fois moins qu'en partant des médias), analyses avec un
lieu (l'index des clés seul, 2 ms) ; un écart fait recalculer la table (93 à 110 ms pour les deux).
Contrôle mesuré : 22 à 32 ms, une fois par passage de six minutes au plus. Un écart qui se
compenserait (une étiquette de trop pour l'une, de moins pour une autre, ou une clé de lieu changée
sans ses comptes) passe inaperçu ; les mots des noms (`media_name`), sans compte, ne sont pas
contrôlés. Tests : `CountCheckTest` (étiquettes, analyse et corbeille écrites sans les méthodes,
lignes supprimées directement : recalcul au passage suivant, puis plus rien), `QueryPlanTest`,
`AnalysisQueueLoadTest` (budget 100 ms).

---

## 6. Plans d'exécution surveillés

`QueryPlanTest` capture le SQL émis par Room (`setQueryCallback`) et vérifie son
`EXPLAIN QUERY PLAN`. Sans statistiques, le plan ne dépend pas des données : la base de test est
presque vide. Assertions :

- timeline, dossiers masqués, filtres Photos et Vidéos : page à un décalage de 45 000 par leur
  index composé, sans `TEMP B-TREE` ; nombre total de `MediaPagingSource` par l'index seul, jamais
  `SELECT COUNT(*) FROM (requête triée)` ;
- grille d'un dossier par l'index des dossiers ; couverture de chaque dossier par ce même index
  seul (sous-requête corrélée sans tri) ;
- compteurs de la timeline et des filtres, position : index seul (`COVERING INDEX`) ;
- histogramme et en-têtes avant un média : dates extrêmes puis chaque période par sa plage de
  dates dans l'index seul (`date_taken>? AND date_taken<?`), sans `strftime` ni tri ; avant un
  média lointain, mois puis jours non vides, chacun d'une requête à table récursive (`WITH
  RECURSIVE`) ;
- mois de toutes les années : index de la timeline (seul pour le nombre), sans tri, `strftime`
  seulement pour les dates hors des années bornées en Kotlin ;
- files de l'analyse : index de la timeline ou des types, sans tri ; reprise par une borne sur la
  date (`date_taken<?`) ;
- compteurs d'avancement : `media` dans l'ordre des identifiants, table de l'étape par sa clé ;
- recherche par étiquette : index (`label`, `confidence`, `media_id`) seul, jamais de parcours
  complet de `media_label` ; nombre de résultats depuis les médias du critère (clé primaire) ;
- recherche par mot : noms de fichiers par l'index plein texte `media_name`, nombre de résultats
  sans parcours de `media`, pages par l'index des dates sans tri ; `media` dans l'ordre des
  identifiants pour un texte que cet index ne sert pas (chiffre, séparateur) ; texte reconnu par
  l'index plein texte `media_text_fts`, `media_text` parcourue seulement sous la ligne du repli
  (boucle extérieure de la jointure, vide quand une autre voie répond au terme) ;
- lieux : clés distinctes de `place_count` parcourues, médias des clés trouvées par l'index de
  `place_key`, jamais tout `media_analysis` (de même pour un mot de lieu de la recherche par le
  sens) ; comptes d'un lot par la clé primaire des analyses, recalcul complet par l'index des
  clés, sans tri ;
- contrôle des comptes (début de chaque passage d'analyse) : étiquettes des médias visibles par
  l'index de la clé primaire de `media_label` seul, sans regroupement ; analyses avec un lieu par
  l'index des clés seul ;
- étiquettes de l'onglet Recherche : nombres lus dans `label_count`, sans relire `media_label` ;
  couverture par l'index des étiquettes, sans trier toutes les photos d'une étiquette ; comptes
  d'une écriture (lot de l'analyse, synchronisation) depuis les étiquettes de ses médias (clé
  primaire), jamais toute la timeline ;
- sauvegarde des données : chaque tranche par la clé primaire de sa table (`rowid>?`), le contenu
  des albums par sa clé composée (`(album_id,media_id)>(?,?)`), sans `TEMP B-TREE`.

Les plans lus sur un téléphone dépendent de sa version de SQLite (3.22 sous Android 10) ; le test
protège le SQL de Focale, pas le choix de chaque version.

---

## 7. Coûts restants et pistes

Les trois pistes de la 1.11.0 sont traitées (§ 5.14 à 5.16), avec un garde-fou des comptes
(§ 5.17). Restent :

- **Repli du texte reconnu** : un mot que rien d'autre ne trouve (ni étiquette, ni lieu, ni nom,
  ni début d'un mot d'un texte : une faute de frappe, un fragment comme « ture ») lit encore la
  clé de chaque texte (`LIKE`, 23 à 26 ms), comme avant. Le vocabulaire de l'index (`fts4aux`, que
  Room ne déclare pas) permettrait de chercher le fragment parmi les mots distincts.
- **Lieux de l'onglet Recherche** (30 à 50 ms, au plus toutes les 5 s) : regroupement par lieu et
  pays des 15 000 analyses géolocalisées, médias visibles seulement ; des comptes par lieu et pays
  tenus comme `label_count` le ramèneraient à quelques millisecondes.
- **Migration 8 → 9** : une fois, 0,4 à 0,6 s sur la JVM de bureau pour recalculer les clés de
  47 000 textes et construire l'index du texte, au premier lancement de la mise à jour.

---

## 8. Limites

- **JVM de bureau ≠ téléphone** : un cœur de téléphone de milieu de gamme est plus lent, sa
  mémoire et son stockage aussi, et il ralentit en chauffant ; ART n'est pas HotSpot. Les rapports
  avant/après et les comportements (tri ou non, coût constant ou croissant) se transposent, pas
  les durées absolues.
- **Robolectric** : SQLite 3.32.2 natif, mais sur le disque et la mémoire d'un ordinateur ;
  faux MediaStore sans IPC ni `CursorWindow` ; ni miniatures, ni décodage, ni rendu Compose.
- **ML Kit** ne s'exécute pas sur la JVM : étiquetage, reconnaissance de texte et détection des
  visages sont estimés (§ 4.5). ONNX Runtime de bureau utilise AVX2 ou AVX-512 ; sur téléphone,
  NEON et les instructions int8 d'ARM.
- **Machine partagée** : mesures bruitées, d'où des médianes et des budgets larges.

---

## 9. Profil de référence

`app/src/main/baseline-prof.txt` est écrit à la main, par paquets (jokers `**`). Choix :

- **S** (démarrage) pour ce qui s'exécute avant la première image de la timeline : application,
  activité, classes de base Hilt et **composant Dagger** (`DaggerFocaleApplication_HiltComponents_*`,
  absent du profil précédent), `core`, thème, navigation, mise à jour et nouveautés (pastille et
  feuille montrées au lancement), lancement des tâches de fond (`work`), inscription du coffre et
  des personnes (verrouillage automatique, contrôles planifiés, fetchers Coil), timeline, grille,
  `data`. Ces méthodes sont regroupées dans la partie « démarrage » du DEX : le reste n'y entre pas.
- **H + P** (chaud, après le démarrage) pour les parcours fréquents ensuite : visionneuse (photos
  animées comprises), onglets, recherche, **analyse en arrière-plan** (`ml`, `people` : boucles
  par pixel et par empreinte, exécutées des heures durant juste après l'installation),
  personnes, **coffre une fois déverrouillé** (grilles, visionneuse, déchiffrement en flux,
  sauvegardes manuelle et automatique), **éditeur** (rendu, recadrage et redressement, dessin,
  montage vidéo), carte, diaporama, widget.
- Écrans rares (réglages, nettoyage, corbeille) : laissés au JIT.
- `Lapp/focale/**;` : classes de l'application dans l'image de démarrage, comme avant.

### Proposition : profil généré et défilement mesuré sur l'émulateur de la CI

Rien n'est encore branché à la CI. Proposition, sur le modèle du job `smoke` (émulateur API 34
`google_apis` x86_64, KVM, `reactivecircus/android-emulator-runner`) :

1. Un module `:baselineprofile` (`com.android.test`, plugin `androidx.baselineprofile`) dont le
   générateur (`BaselineProfileRule.collect`) remplit d'abord la galerie, par un script comme
   `tools/release/smoke_test.sh` (quelques milliers d'images de synthèse poussées dans
   `/sdcard/Pictures`, puis attente de l'index), puis lance l'application et parcourt avec
   UiAutomator : défilement continu et barre de défilement rapide de la timeline, visionneuse,
   albums et un dossier, recherche (« chien », « plage »), personnes, coffre (code saisi),
   éditeur (réglage, redressement). Dans `app/build.gradle.kts` : plugin `androidx.baselineprofile`
   et `baselineProfile { automaticGenerationDuringBuild = false }` ; le profil généré
   (`src/standardRelease/generated/baselineProfiles/`) s'ajoute alors au fichier écrit à la main.

   ```
   ./gradlew :app:generateStandardReleaseBaselineProfile
   ```

2. Un module `:macrobenchmark` mesurant le défilement : `MacrobenchmarkRule.measureRepeated` avec
   `FrameTimingMetric` (durée CPU des images, `frameOverrunMs`) sur un défilement de la timeline
   (`device.findObject(…).fling(Direction.DOWN)` × 10, puis la barre de défilement), et
   `StartupTimingMetric` pour le démarrage à froid, comparés en `CompilationMode.None()` et
   `CompilationMode.Partial(BaselineProfileMode.Require)` :

   Par exemple (types de build `benchmarkRelease` créés par le plugin) :

   ```
   ./gradlew :macrobenchmark:connectedStandardBenchmarkReleaseAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
   ```

   Sur émulateur (rendu logiciel `swiftshader`, cœurs partagés), les durées absolues ne valent
   pas un téléphone : on suivrait les rapports (avec et sans profil) et les dérives entre
   versions, avec des seuils larges, plutôt que des valeurs.

3. Un job CI après `build`, sur le même émulateur, qui publie les résultats JSON du benchmark et
   le profil généré en artefacts, sans bloquer la publication tant que les mesures n'ont pas
   montré leur stabilité.
