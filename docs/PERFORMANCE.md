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
CI comprise : environ 50 s sur la machine de mesure (§ 3), dont 20 s pour la file de l'analyse
et 19 s pour le défilement. Aucune propriété Gradle n'est donc nécessaire.

| Classe | Ce qu'elle mesure |
|---|---|
| `MediaSyncLoadTest` | `MediaSyncManager` depuis un faux MediaStore (`FakeMediaStore`, fournisseur Robolectric de l'autorité `media`) : synchronisation complète, puis incrémentale (300 modifiés, 150 ajoutés, 50 supprimés), puis sans changement |
| `TimelineScrollLoadTest` | pages Paging de la timeline (continu et sauts, `initialKey`), en-têtes (`insertSeparators`), histogramme de la bulle, position dans la visionneuse et dans la grille, dossiers, compteurs, recherche par étiquette et par texte, onglet Recherche |
| `AnalysisQueueLoadTest` | sélection et enregistrement des 2 078 lots de l'analyse principale, sélection des lots des étapes (texte, visages, empreintes) et compteurs d'avancement, au fil de l'avancement |
| `AnalysisComputeLoadTest` | calculs par photo exécutables sur la JVM : dHash et netteté, géocodage, alignement et SFace, prétraitement et TinyCLIP, recherche par le sens, regroupement des visages |
| `core/database/QueryPlanTest` | `EXPLAIN QUERY PLAN` des requêtes chaudes, sur le SQL réellement émis par Room (§ 6) |

Chaque mesure est affichée (lignes `PERF` des rapports de test) et ajoutée à
`app/build/reports/performance/mesures.tsv`. Elle est comparée à un **budget** d'environ cinq
fois la médiane mesurée ici (quelques millisecondes au moins) : assez large pour une CI chargée,
assez serré pour signaler le retour d'un point chaud corrigé (par exemple une page de la
timeline à 95 ms au lieu de 3, ou un lot de l'analyse à 25 ms au lieu de 1). Deux tests vérifient
en plus que le coût d'un lot de l'analyse ne croît pas avec l'avancement.

Base de données : Room comme en production (`AppModule.database` : fichier sur disque, journal
WAL, migrations, exécuteurs de Room), sous Robolectric (`@Config(sdk = [34])`) dont le SQLite natif
est la version **3.32.2** (celle d'Android 11 et 12 ; Android 10 a la 3.22, Android 14 la 3.39).

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
| Géolocalisés | 30,7 % (30 villes, chez soi d'abord) |
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
  médiane de plusieurs passages : 2 « avant » (anciens index et anciennes files), 3 « après ».
- Les temps sont ceux d'une JVM de bureau, pas d'un téléphone : voir les limites (§ 8).

---

## 4. Résultats

### 4.1 Synchronisation MediaStore → Room

| Opération | Avant | Après |
|---|---|---|
| Complète, 50 000 médias (premier lancement) | 1,5 s (0,8 à 2,3) | 1,0 s (0,8 à 1,4) |
| Incrémentale : 300 modifiés, 150 ajoutés, 50 supprimés | 300 ms | 285 ms |
| Sans changement (retour au premier plan) | 81 ms | 53 ms |

La synchronisation n'avait pas de point chaud : l'incrémentale lit les seules lignes modifiées
(`GENERATION_MODIFIED`), puis les 50 000 identifiants pour les suppressions, et purge les
métadonnées orphelines (`NOT IN`, par la clé primaire). Les nouveaux index composés ne la
ralentissent pas de façon mesurable. Le coût de MediaStore lui-même (IPC, `CursorWindow`) n'est
pas mesuré.

### 4.2 Défilement, grilles et recherche

| Opération | Avant | Après |
|---|---|---|
| Page suivante (120 médias), médiane des 413 pages de la timeline | 95 ms | **2,9 ms** |
| 20 dernières pages (décalage proche de 50 000) | 91 ms | **4,6 ms** |
| Chargement initial (360 médias + nombre total) | 121 ms | 63 ms |
| Saut de la barre de défilement (nouvelle `PagingSource`, 360 médias + nombre total) | 172 ms | 69 ms |
| … dont le `COUNT(*)` de `LimitOffsetPagingSource` | 71 ms | 53 ms |
| Saut tout au bout / filtre Photos | 188 / 180 ms | 58 / 60 ms |
| Liste des dossiers (onglet Albums, couvertures comprises) | 2 854 ms | **82 ms** |
| Dossier de 36 604 médias, saut au bout / petit dossier | 133 / 18 ms | 52 / 0,8 ms |
| Compteur de la timeline / du filtre Vidéos | 2,2 / 9,4 ms | 2,6 / 0,7 ms |
| Histogramme mensuel (bulle de date) | 124 ms | 94 ms |
| Position d'un média (ouverture de la visionneuse) | 11 ms | 3,3 ms |
| Position dans la grille avec en-têtes de jour (retour de la visionneuse) | 180 ms | 142 ms |
| En-têtes de jour (`insertSeparators`) sur 50 000 médias | 25 à 40 ms | (inchangé) |
| Conversion de 50 000 `MediaEntity` en `MediaItem` | ≈ 50 ms | (inchangé) |
| Recherche « chien » (races comprises, 5 004 résultats) : nombre / première page | 75 / 170 ms | 105 / 170 ms |
| Recherche « facture » (nom de fichier ou texte reconnu) : nombre / première page | 68 / 162 ms | 109 / 144 ms |
| Onglet Recherche : étiquettes les plus présentes / lieux | 376 / 96 ms | 354 / 39 ms |

En-têtes et conversion des pages s'exécutent sur le fil principal (transformations de
`PagingData` collectées dans `viewModelScope`) : 0,1 ms environ par page de 120 médias.

### 4.3 Analyse : coût base de données

Analyse principale, 2 078 lots de 24 (`AnalysisWorker.BATCH_SIZE`) sur toute la bibliothèque :

| Opération | Avant | Après |
|---|---|---|
| Sélection d'un lot, au début / à la fin de l'analyse | 23 / 17 ms (tri de toute la bibliothèque à chaque lot) | **0,9 / 0,6 ms** |
| Sélection des 2 078 lots | ≈ 42 s (estimé, 2 078 × 20 ms) | **1,6 s** (mesuré) |
| Enregistrement d'un lot (analyse et étiquettes) | (inchangé) | 2,2 ms (4,7 s pour les 2 078) |
| Premier lot d'un passage (sans curseur), à 0 / 49 / 94 % | 23 / 19 / 17 ms | 2,6 / 29 / 86 ms |
| Compteur observé (`observePendingCount`), à 0 / 49 / 94 % | 9 / 19 / 25 ms | 22 / 25 / 14 ms |

Étapes complémentaires, lots de 16 (`STEP_BATCH_SIZE`) :

| Étape | Lot suivant (curseur), de 50 à 95 % | Premier lot d'un passage, de 0 à 95 % | Sélection de tous les lots | Avancement observé |
|---|---|---|---|---|
| Texte (2 961 lots) | 0,9 à 1,4 ms | 56 à 210 ms | 3,3 s | 12 à 42 ms |
| Visages (2 695 lots) | 0,6 à 0,8 ms | 0,9 à 140 ms | 2,0 s | 11 à 50 ms |
| Empreintes (2 961 lots) | 0,4 à 0,8 ms | 0,8 à 172 ms | 2,1 s | 18 à 75 ms |

Sans curseur, chaque lot de ces étapes relisait depuis le début les photos déjà traitées : avec
les anciens index, un tri de toutes les photos par lot (≈ 18 ms, soit près d'une minute par
étape) ; avec les nouveaux, un coût proportionnel à l'avancement (jusqu'à 134 ms par lot à
95 %, plusieurs minutes par étape). Le premier lot d'un passage garde ce coût, une fois toutes
les six minutes au plus.

### 4.4 Analyse : calculs par photo (JVM de bureau)

| Calcul | Durée | Fréquence |
|---|---|---|
| dHash et netteté (luminance 384 × 288, laplacien, empreinte 9 × 8) | 0,9 ms | par média, hors réduction du bitmap (Android) |
| Géocodage : lieu le plus proche | 11 µs | par média géolocalisé (10 000 en 112 ms) |
| Chargement de la base des lieux (171 000 lieux) | 69 ms | par passage d'analyse |
| Alignement d'un visage (112 × 112) et netteté | 0,5 ms | par visage |
| Empreinte SFace (int8, 2 fils) | 55 ms | par visage |
| Chargement de SFace / de TinyCLIP image | 343 / 49 ms | par passage |
| Prétraitement CLIP (512 × 384 → 224 × 224) | 4,5 ms | par photo |
| Empreinte TinyCLIP (8 bits, 2 fils) | 24 ms | par photo |
| Empreinte d'une phrase de recherche | 4 ms | par recherche |
| Comparaison à 47 500 empreintes (un fil) | 26 ms | par recherche (quatre tranches en parallèle dans l'application) |
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
demande donc plusieurs nuits en charge. Les corrections du § 5 retirent de ce total près de
quatre minutes de requêtes (sur la JVM de bureau) qui croissaient avec le carré de la taille de
la bibliothèque ; le reste du coût propre à Focale (base, calculs Kotlin) est désormais
négligeable devant ML Kit et ONNX Runtime.

---

## 5. Points chauds trouvés et corrigés

### 5.1 Index de la corbeille : tri de toute la bibliothèque à chaque page

Sans statistiques (`ANALYZE`, que Room ne lance pas), SQLite estime qu'une égalité sur une
colonne indexée ne retient qu'une dizaine de lignes. L'index sur `is_trashed` seul gagnait donc
contre l'index `date_taken` pour toute requête `WHERE m.is_trashed = 0 … ORDER BY m.date_taken`,
puis triait **toute la bibliothèque** (`USE TEMP B-TREE FOR ORDER BY`) : à chaque page de la
timeline (95 ms), à chaque saut, à chaque lot de l'analyse et, dans la liste des dossiers, une
fois par dossier pour sa couverture (300 parcours de 50 000 lignes : 2,9 s).

Correction (`DatabaseMigrations.CHANGES_V5`, `DatabaseMigrationsTest`) : des index composés qui
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
recherche, qui parcourt aussi toute la timeline, passe de 70 à 105 ms (même raison).

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
pages, leur nombre total et leur histogramme (≈ 0,2 s de requêtes par lot sur la JVM de bureau).
Correction : une requête qui ne lit que `media` (`MediaQueryBuilder.readsOnlyMedia`) est observée
sur cette seule table (`MediaDao.pagingSourceOfMedia`, `observeCountOfMedia`…).
Test : `MediaObservationTest`.

---

## 6. Plans d'exécution surveillés

`QueryPlanTest` capture le SQL émis par Room (`setQueryCallback`) et vérifie son
`EXPLAIN QUERY PLAN`. Sans statistiques, le plan ne dépend pas des données : la base de test est
presque vide. Assertions :

- timeline, dossiers masqués, filtres Photos et Vidéos : page à un décalage de 45 000 et
  `COUNT(*)` de la `PagingSource` par leur index composé, sans `TEMP B-TREE` ;
- grille d'un dossier par l'index des dossiers ; couverture de chaque dossier par ce même index
  seul (sous-requête corrélée sans tri) ;
- compteurs de la timeline et des filtres, histogramme, position : index seul (`COVERING INDEX`) ;
- files de l'analyse : index de la timeline ou des types, sans tri ; reprise par une borne sur la
  date (`date_taken<?`) ;
- compteurs d'avancement : `media` dans l'ordre des identifiants, table de l'étape par sa clé ;
- recherche par étiquette et couverture de chaque étiquette : index `label`, jamais de parcours
  complet de `media_label`.

Les plans lus sur un téléphone dépendent de sa version de SQLite (3.22 sous Android 10) ; le test
protège le SQL de Focale, pas le choix de chaque version.

---

## 7. Coûts restants et pistes

- **Nombre total de `LimitOffsetPagingSource`** (53 ms, à chaque ouverture de source : saut,
  invalidation) : Room compte `SELECT COUNT(*) FROM (requête triée)`, sous-requête lue ligne
  entière ; le compteur de la timeline, par l'index seul, prend 2 ms. Une `PagingSource` maison
  qui compterait par `MediaQueryBuilder.count` ramènerait un saut à une dizaine de millisecondes.
- **Compteurs d'avancement** : chacun relit toute la bibliothèque à chaque lot de son étape, tant
  que l'avancement est affiché (onglet Recherche, réglages). Échantillonner les invalidations
  (un compte toutes les quelques secondes au plus) supprimerait ce coût.
- **Position dans la grille** (142 ms, au retour de la visionneuse si le média n'est pas visible)
  et **histogramme** (94 ms, seulement quand `media` change) : `strftime(…, 'localtime')` sur
  chaque ligne.
- **Étiquettes de l'onglet Recherche** (354 ms, relancées à chaque lot de l'analyse quand l'onglet
  est affiché) : la couverture de chaque étiquette trie toutes ses photos ; un index
  (`label`, `confidence`) et un regroupement limité aux étiquettes affichées l'éviteraient.
- **Nombre de résultats d'une recherche** (≈ 105 ms) : parcours de toute la timeline ; partir des
  médias de l'étiquette (5 000 au lieu de 50 000) serait plus court.

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
