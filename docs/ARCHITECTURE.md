# Focale — Plan d'architecture

Focale est une galerie photo Android 100 % hors ligne, construite en Kotlin avec
Jetpack Compose et Material 3 Expressive. Ce document décrit l'architecture
cible, les choix techniques structurants et le découpage du développement.

---

## 1. Principes directeurs

| Principe | Conséquence concrète |
|---|---|
| **Hors ligne, zéro donnée envoyée** | Aucun SDK d'analytics. La télémétrie de ML Kit (`datatransport`/CCT) est neutralisée dans le manifeste. Les seules connexions réseau sont le fond de carte OpenStreetMap, **désactivé par défaut** et activable explicitement, et la recherche quotidienne de mises à jour sur GitHub (désactivable) ; la variante `strict` retire même la permission `INTERNET`. |
| **Fluide avec 50 000+ médias** | MediaStore est indexé dans Room ; toutes les listes passent par Paging 3 (placeholders + sauts) ; les miniatures viennent du cache système de MediaStore (`loadThumbnail`) via un `Fetcher` Coil dédié. |
| **MediaStore reste la source de vérité** | Room n'est qu'un miroir (index) + les métadonnées propres à l'app (favoris, albums virtuels, étiquettes ML, empreintes, coffre). |
| **Material You partout** | Couleurs dynamiques du fond d'écran, palette maison en repli, thème « couleur depuis la photo » dans la visionneuse et les albums. |
| **Adaptatif** | `NavigationSuiteScaffold` (barre/rail), grille dont le nombre de colonnes dépend de la largeur, panneaux latéraux sur grand écran. |

---

## 2. Stack

| Domaine | Choix |
|---|---|
| Langage / build | Kotlin 2.4, AGP 9 (Kotlin intégré), Gradle 9, catalogue de versions, KSP |
| UI | Jetpack Compose (BOM), **Material 3 1.5** (composants Expressive), material3-adaptive, navigation-suite |
| Architecture | MVVM + UDF : `ViewModel` → `StateFlow<UiState>`, événements ponctuels via `Channel` |
| Injection | Hilt (+ hilt-work, hilt-navigation-compose) |
| Asynchrone | Coroutines, Flow |
| Persistance | Room (index + métadonnées), DataStore Preferences (réglages) |
| Pagination | Paging 3 (`PagingSource` Room via `@RawQuery`) |
| Images | Coil 3 (miniatures MediaStore, GIF animés, frames vidéo), telephoto (zoom + sous-échantillonnage, glisser pour fermer) |
| Vidéo | Media3 ExoPlayer + `media3-ui-compose` (contrôles M3 maison) |
| Couleurs depuis photo | MaterialKolor (quantification + score Material Color Utilities) |
| Formes | `MaterialShapes` + androidx.graphics.shapes (`Morph`) |
| ML sur l'appareil | ML Kit Image Labeling, Text Recognition v2 (alphabet latin) et Face Detection, modèles **embarqués**, pas de téléchargement ; SFace (OpenCV Zoo) et TinyCLIP quantifié (recherche en langage naturel), exécutés par ONNX Runtime |
| Arrière-plan | WorkManager |
| Carte | osmdroid + fond vectoriel hors ligne (Natural Earth, domaine public) |
| Géocodage inverse | Hors ligne : base GeoNames (CC BY 4.0) embarquée, index spatial en grille |
| Chiffrement | Tink `StreamingAead` (AES-256-GCM-HKDF, segments de 1 Mo), keyset protégé par Android Keystore |
| Authentification | androidx.biometric (biométrie forte ou identifiants de l'appareil) + code PIN (PBKDF2) |
| Widget | Glance (`GlanceTheme` = couleurs dynamiques du système) |
| CI | GitHub Actions : tests unitaires, lint, APK debug + release, test de fumée de l'APK release sur émulateur |

---

## 3. Vue d'ensemble des couches

```
┌────────────────────────────── UI (Compose) ──────────────────────────────┐
│  Écrans + ViewModels : timeline, visionneuse, albums, recherche, plus,   │
│  favoris, corbeille, nettoyage, éditeur, coffre, carte, diaporama        │
│  Composants : grille zoomable, défilement rapide, sélection glissée,     │
│  miniatures morphées, thème photo, barres flottantes Expressive          │
└───────────────▲──────────────────────────────────────────▲───────────────┘
                │ StateFlow / PagingData                   │ actions
┌───────────────┴──────────────── Données ─────────────────┴───────────────┐
│ Repositories : Media, Album, Favorite, Trash, Search, Cleanup, Vault,    │
│ Memories, Settings, MediaOperations (copier/déplacer/renommer/partager)  │
├──────────────────────────────────────────────────────────────────────────┤
│ Sources : Room (FocaleDatabase) · MediaStore (scanner + observer) ·      │
│ DataStore · Fichiers privés (coffre, corbeille API 29) · ML Kit          │
└───────────────▲──────────────────────────────────────────────────────────┘
                │
┌───────────────┴───────────── Arrière-plan ───────────────────────────────┐
│ WorkManager : synchro MediaStore, analyse (étiquettes, pHash, flou,      │
│ GPS, lieu, texte), purge corbeille, rafraîchissement du widget           │
└──────────────────────────────────────────────────────────────────────────┘
```

Règles :
- l'UI ne parle qu'aux ViewModels ; les ViewModels ne parlent qu'aux repositories ;
- aucune classe Android de l'UI (Activity) n'est connue des repositories, sauf via
  l'abstraction `ConsentRequester` (voir § 6) ;
- tout accès disque/ContentResolver passe par `Dispatchers.IO` injecté.

---

## 4. Organisation des sources (module `app`)

Un seul module Gradle pour garder un build simple et rapide ; le découpage se fait
par packages, chacun pouvant devenir un module plus tard.

```
app/src/main/java/app/focale/
├── FocaleApplication.kt        Hilt, WorkManager, ImageLoader Coil
├── MainActivity.kt             edge-to-edge, thème, permissions, consentements
├── core/
│   ├── di/                     modules Hilt (DB, dispatchers, ML…)
│   ├── model/                  modèles métier (MediaItem, MediaQuery, Album…)
│   ├── database/               Room : entités, DAO, requêtes brutes
│   ├── mediastore/             scanner, synchro incrémentale, observer, consentements
│   ├── settings/               DataStore → AppSettings
│   ├── image/                  Fetchers Coil (miniatures, fichiers chiffrés)
│   ├── permissions/            état des permissions (complet / partiel / refusé)
│   └── util/                   dates, formats, haptique, extensions
├── data/                       repositories
├── ml/                         étiquetage, dictionnaire FR/EN, pHash, flou, géocodeur, texte
├── people/                     visages : détection, alignement, SFace, regroupement
├── work/                       workers
├── vault/                      crypto, stockage, verrouillage
├── widget/                     widget Glance
└── ui/
    ├── theme/                  couleurs, typo (Google Sans Flex), formes, mouvement
    ├── components/             composants réutilisables
    ├── navigation/             routes typées, NavHost, barre adaptative
    ├── timeline/  viewer/  albums/  search/  more/  favorites/  trash/
    ├── cleanup/   editor/  vault/   map/     slideshow/  memories/  settings/
```

---

## 5. Modèle de données

### 5.1 Index MediaStore (table `media`)

Clé = `_ID` MediaStore (unique pour images et vidéos dans la table `Files`).

| Colonne | Rôle |
|---|---|
| `id`, `media_type`, `mime_type` | identité, URI reconstruite à la volée |
| `display_name`, `relative_path`, `bucket_id`, `bucket_name` | albums dossiers |
| `date_taken` | date de tri (repli sur `DATE_MODIFIED`/`DATE_ADDED`) |
| `date_added`, `date_modified`, `generation` | synchro incrémentale |
| `size`, `width`, `height`, `orientation`, `duration` | infos et mise en page |
| `is_screenshot`, `is_gif` | filtres rapides (dérivés à l'indexation) |

Index : `date_taken`, `bucket_id`, `media_type`.

### 5.2 Métadonnées de l'app

| Table | Contenu |
|---|---|
| `media_analysis` | état d'analyse, `phash`, `blur_score`, `latitude`/`longitude`, `place`, `country` |
| `media_label` | (media, étiquette ML Kit canonique, confiance) |
| `media_text` | texte reconnu dans la photo (une ligne par ligne) et sa clé de recherche normalisée |
| `media_embedding` | empreinte TinyCLIP de chaque photo (512 flottants, norme 1) |
| `favorite` | médias favoris |
| `album` / `album_media` | albums virtuels et leur contenu |
| `trash_item` | corbeille applicative (API 29 uniquement) |
| `vault_item` | entrées du coffre (fichiers chiffrés), avec leur album du coffre (`album_id`, facultatif) |
| `vault_album` | albums du coffre : nom, dossier d'origine d'un album-dossier, date du dernier import |
| `media_date` | date d'origine d'un média restauré ou copié, quand MediaStore n'en a retrouvé aucune (voir § 6.1) |
| `search_history` | recherches récentes |
| `face_scan` / `face` / `person` | visages détectés, empreintes SFace et personnes (nom, vignette, masquée, épinglée) |
| `person_refusal` | paires de personnes déclarées distinctes (« Pas la même ») |

Les tables d'analyse ne référencent pas `media` par clé étrangère (l'index est
reconstruisible) : leurs lignes orphelines sont purgées après synchronisation. Les
migrations (`DatabaseMigrations`) reprennent le SQL exporté par Room dans
`app/schemas` et sont testées contre ce schéma.

### 5.3 `MediaQuery` : une requête pour tout

Toutes les grilles (timeline, album, favoris, résultats de recherche…) sont décrites
par un objet sérialisable `MediaQuery` (filtres de type, dossier, album virtuel,
favoris, étiquettes, période, lieu, texte, tri). Un `MediaQueryBuilder` le traduit
en SQL ; Room fournit la `PagingSource`.

Bénéfice clé : la visionneuse reçoit **la même** `MediaQuery` que la grille d'où
l'on vient, calcule la position de départ par un `COUNT(*)` et ouvre une
`PagingSource` démarrant à cet index (`initialKey`). Le swipe parcourt donc
exactement les mêmes éléments, dans le même ordre, même sur 50 000 médias.

---

## 6. Flux clés

### 6.1 Synchronisation MediaStore → Room
1. Au démarrage (et à chaque changement détecté par `ContentObserver`, avec
   anti-rebond), `MediaSyncManager` compare MediaStore et l'index.
2. API 30+ : synchro incrémentale par `GENERATION_MODIFIED` + détection des
   suppressions par la liste des `_ID` ; sinon comparaison complète
   (`id` + `date_modified`).
3. Écritures par lots de 500 dans des transactions ; Room invalide les
   `PagingSource` → l'UI se met à jour seule.
4. Date de tri : `DATE_TAKEN`, sinon la date retenue dans `media_date`, sinon `DATE_MODIFIED`.

Médias écrits par l'application (sortie du coffre, corbeille d'Android 10, copie, édition en
copie : `MediaOperations.insertAndWrite`) : ils gardent la date de l'original. À la publication
(`IS_PENDING` → 0), MediaStore relit le fichier, efface `DATE_TAKEN` (qu'une application ne peut
pas fixer), ne le retrouve que dans les métadonnées (EXIF, date de la vidéo) et prend la date du
fichier comme `DATE_MODIFIED`. Le fichier en attente reçoit donc d'abord la date d'origine comme
date de modification, par son chemin (`DATA`, accessible à son auteur depuis Android 11) :
`DATE_MODIFIED` la reprend, et un EXIF sans fuseau horaire est daté comme l'original
(MediaStore en déduit le fuseau par la date du fichier). Si le chemin est inaccessible
(Android 10) et que MediaStore n'a trouvé aucune date, l'index la retient dans `media_date`
(vidée si la base MediaStore est recréée, purgée avec les médias disparus).

### 6.2 Miniatures
`MediaThumbnail(uri, type, dateModified)` → `ThumbnailFetcher` Coil →
`ContentResolver.loadThumbnail()` (cache de miniatures du système, très rapide,
y compris pour les vidéos). Cache mémoire Coil ; la clé inclut `date_modified`
pour invalider après édition.

### 6.3 Opérations nécessitant le consentement de l'utilisateur
Supprimer, mettre à la corbeille, déplacer, renommer ou remplacer un média d'une
autre app exige une confirmation système :
- API 30+ : `MediaStore.createTrashRequest / createDeleteRequest / createWriteRequest` (par lot) ;
- API 29 : `RecoverableSecurityException` (élément par élément).

`ConsentRequester` (singleton) expose `suspend fun request(IntentSender): Boolean`.
`MainActivity` collecte les demandes et lance l'`IntentSender` ; le code métier
reste linéaire (`if (!consent.request(...)) return Cancelled`).

### 6.4 Corbeille (30 jours)
- API 30+ : corbeille **système** MediaStore (`IS_TRASHED`, expiration 30 jours gérée par Android).
- API 29 : corbeille applicative (fichier déplacé dans le stockage privé, entrée
  `trash_item`, restauration par réinsertion MediaStore, purge par WorkManager).

### 6.5 Analyse intelligente (WorkManager)
`AnalysisWorker` traite par lots les médias non analysés (contraintes : batterie
non faible, option « en charge uniquement »), se replanifie tant qu'il reste du
travail :
1. miniature 512 px via MediaStore ;
2. ML Kit Image Labeling (confiance ≥ 0,6) → étiquettes canoniques anglaises ;
3. dHash 64 bits (doublons) ;
4. variance du Laplacien (flou) ;
5. GPS EXIF (avec `ACCESS_MEDIA_LOCATION`) → ville/pays via géocodeur hors ligne ;
6. puis étapes complémentaires (`AnalysisStep`), dont le texte : ML Kit Text Recognition
   sur la photo réduite à 1600 px (orientation appliquée), captures d'écran d'abord, les
   plus hautes lues par bandes à leur largeur d'écran ; aussi lancée à la demande par
   l'action « Texte » de la visionneuse.

Recherche : la requête est normalisée (minuscules, sans accents), confrontée au
dictionnaire FR/EN des 447 étiquettes (avec synonymes et hyperonymes :
« chien » couvre aussi les races détectées), aux lieux, aux dates
(« 2023 », « juillet 2022 », « 14/07/2021 »), aux noms de fichiers et au texte reconnu
dans les photos (« wifi » trouve aussi « Wi-Fi »).

Recherche en langage naturel : l'étape d'analyse « empreintes » (`EmbeddingStep`) calcule
l'empreinte TinyCLIP de chaque photo (miniature MediaStore, prétraitement CLIP, modèle
ONNX quantifié sur 8 bits, `tools/clip/`). Les mots libres de la requête sont traduits en
anglais hors ligne (`QueryTranslator` : dictionnaire et règles simples, « chien sur la
plage » → « a photo of a dog on the beach »), encodés par le modèle de texte et comparés à
toutes les empreintes, gardées en mémoire (8 bits par valeur, rechargées quand la table
change). Les photos au-dessus d'un seuil calibré, absolu et relatif au meilleur score,
s'ajoutent aux correspondances exactes de chaque terme (`SearchTerm.similarIds`) ; un mot
désignant un lieu de la bibliothèque reste une contrainte. La préparation commence
pendant la frappe (anti-rebond, annulation de la saisie précédente).

Doublons : regroupement par dHash, distance de Hamming ≤ seuil, candidats obtenus
par découpage du hash en 4 bandes de 16 bits (évite le O(n²)).

Étapes complémentaires : une fois l'analyse principale à jour, le même worker
exécute les `AnalysisStep` fournies par multiliaison (texte, visages, empreintes).
Chacune tient son avancement dans sa table (version et `date_modified` analysés) :
elle reprend où elle s'était arrêtée et recalcule après une évolution ou une édition.
Chaque étape expose aussi son avancement (`observeProgress`, mêmes critères que sa file) :
`AnalysisScheduler.status` le combine à celui de l'analyse principale pour l'onglet
Recherche et les réglages. « Analyser maintenant » (`runNow`) lance un passage marqué
manuel, qui ignore « en charge uniquement » jusqu'à la fin (ses continuations aussi).
Un échec global (modèle indisponible, étiquetage en échec sur tout un lot) est noté par
`AnalysisIssues` jusqu'au prochain lot réussi et affiché sous l'analyse concernée, avec
l'état « interrompue » plutôt que « en attente ».

Release : le mode strict de R8 (AGP 9) retire le constructeur des « registrars » ML Kit,
instanciés par réflexion depuis le manifeste ; `proguard-rules.pro` le garde, et la CI
vérifie les APK release avec `tools/release/check_constructors.py`. Le job `smoke` exécute
ensuite l'APK release universel sur un émulateur (API 34, `google_apis`) : deux images de
test (texte, visage), puis attente que la base contienne étiquettes, texte « facture » et
visages (`tools/release/smoke_test.sh`). La publication d'une version en dépend.

### 6.6 Coffre-fort
- Import : flux MediaStore → `StreamingAead` (AES-256-GCM-HKDF, segments de 1 Mo)
  → `noBackupFilesDir/vault/<uuid>.bin` + miniature chiffrée `<uuid>.thumb`. La
  copie est relue intégralement (chaque segment est authentifié) avant la
  suppression de l'original (consentement MediaStore). Si un original n'a pas pu
  être supprimé (refus, échec), sa copie est annulée : un média n'est jamais en
  double ni perdu. L'opération n'est pas annulable une fois lancée.
- Import en tâche de fond (`VaultImports`, `VaultImportWorker`) : toute mise au coffre (sélection,
  album entier, nouvelles photos d'un dossier) est confiée à WorkManager, un import à la fois dans
  l'ordre des demandes, en service au premier plan (`dataSync`) avec sa notification de
  progression (sans nom d'album ni image). L'écran suit la progression et peut la laisser
  « continuer en arrière-plan » ; la confirmation de suppression des originaux attend au besoin le
  retour de Focale à l'écran. Une sélection est passée dans un fichier (`Data` limité à 10 Kio).
- Journal : chaque copie garde l'identifiant de son original (`source_media_id`) et reste cachée
  (`importing`) jusqu'à la suppression de celui-ci. Repris après la fin du processus, l'import
  réutilise ses copies sans rien rechiffrer. Au lancement, `VaultRepairWorker` remet en ordre ce
  qu'aucun import ne reprendra : copies dont l'original est parti → entrées au coffre ; original
  resté dans la galerie → copie gardée et signalée (bannière « encore dans la galerie », retrait
  d'un geste sans rien chiffrer) ; copies identiques en double (versions antérieures, import
  relancé) → une seule gardée ; original identique (nom, dossier, taille, date, empreinte SHA-256)
  d'un élément visible → signalé ; fichiers chiffrés orphelins de plus d'une heure → effacés. Sortir
  du coffre un élément dont l'original est resté ne le recopie pas.
- Clés : jeu de clés Tink chiffré par une clé maîtresse non exportable de l'Android
  Keystore ; le nom du fichier sert de donnée associée (pas de substitution).
- Lecture : images déchiffrées en flux par un `Fetcher` Coil (clés de cache
  `vault:…` purgées au verrouillage) ; vidéos lues par une `DataSource` Media3 sur
  le canal déchiffrant *seekable* de Tink. Aucun fichier en clair sur le disque.
- Accès : code de 4 à 12 chiffres (PBKDF2-HMAC-SHA256, 120 000 itérations, sel
  aléatoire, blocage croissant après 5 erreurs) et biométrie forte (classe 3) en
  option ; état déverrouillé en mémoire seulement, reverrouillage dès la mise en
  arrière-plan ou après 1, 5 ou 15 min ; `FLAG_SECURE` sur les écrans du coffre.

- Tri (`VaultContent`, réglages `vaultSort` et `vaultAlbumSort`) : éléments par date de prise
  de vue, date d'ajout au coffre, nom (`Collator` primaire) ou taille, albums par nom, date de
  création ou nombre d'éléments, dans les deux sens ; même feuille que les grilles de la
  galerie (`SortSheet`). Le même ordre vaut pour la grille de tout le coffre, celles des albums
  et la visionneuse, qui parcourt les éléments dans l'ordre de la grille d'origine.

#### Albums du coffre
- De la métadonnée seulement : `vault_album`, et `vault_item.album_id` (clé étrangère
  `ON DELETE SET NULL` : supprimer un album garde ses éléments, hors album ; « Tous les
  éléments » montre tout le coffre). Chaque élément restant chiffré à part, remplir, compléter
  ou réorganiser un album ne chiffre que les médias qui entrent dans le coffre : ceux qui y
  sont ne sont ni relus ni réécrits (`VaultAlbumImportTest`), et l'import n'exige toujours pas
  de déverrouiller le coffre.
- Album entier (menu de `CollectionScreen`, même pipeline d'import) : un dossier rejoint
  l'album du coffre lié à son `BUCKET_ID` (`source_bucket_id`, `source_path`), créé au besoin ;
  un album de l'application devient un nouvel album du même nom, puis il est supprimé s'il a
  été vidé (il ne resterait dans la galerie que son nom, indice de ce qu'il contenait).
- Nouveaux médias d'un album-dossier (bannière dans l'album, « Ajouter ») : médias du dossier,
  hors corbeille, dont `date_added` suit `last_import_at` à une minute près (`DATE_ADDED` est à
  la seconde et l'index suit MediaStore avec retard). Les originaux importés ayant quitté
  MediaStore, la marge ne repropose que ceux dont la suppression a échoué. `last_import_at` est
  l'instant pris juste avant l'instantané du dossier (après une synchronisation), retenu dès
  qu'un média est entré.
- Depuis la galerie, « Déplacer vers le coffre » choisit l'album (aucun, existant, nouveau). Les
  noms des albums du coffre n'apparaissent que coffre déverrouillé, dans un dialogue protégé
  des captures (`SecureFlagPolicy.SecureOn`) ; verrouillé, la sélection entre hors album, ou le
  coffre se déverrouille seul (`VaultUnlockRoute`) sans montrer son contenu. Rien du coffre
  n'apparaît dans la galerie.
- Sauvegarde : le manifeste ajoute les albums et l'album de chaque élément, en champs
  facultatifs ; une sauvegarde antérieure se restaure sans albums, une nouvelle les recrée (ou
  rejoint un album de même nom et de même origine). Un élément déjà présent (même contenu) et
  rangé dans aucun album rejoint celui de la sauvegarde ; déjà rangé, il reste où il est.

### 6.7 Partage sans métadonnées
Option « retirer les métadonnées au partage » : copies dans `cache/shared/`,
exposées par un `FileProvider` et purgées au bout d'une heure.
- JPEG : segments EXIF / XMP / IPTC / commentaires retirés sans recompression,
  données après la fin d'image ignorées (vidéo de photo animée…), orientation
  réécrite ;
- autres images : ré-encodage (PNG si transparence, JPEG sinon) ;
- vidéos MP4 / 3GP / WebM : remultiplexage des pistes audio et vidéo
  (`MediaExtractor` → `MediaMuxer`), sans la localisation du conteneur.

### 6.8 Souvenirs, carte, diaporama et widget
- **Souvenirs** : pour chaque année passée (40 au plus), fenêtre du même jour ± 3 jours
  (`MemoryWindows`) ; couverture choisie par SQL : photo hors capture, la plus nette
  selon l'analyse. Recalcul quand la bibliothèque change et à minuit. Voyages et
  personnes : voir ci-dessous.
- **Carte** : `AnalysisDao.observeGeoPhotos()` ; regroupement par grille de Mercator à
  chaque niveau de zoom entier (`PhotoClustering`, linéaire, hors thread principal) ;
  un appui ouvre la photo, ou la grille de la zone (`MediaQuery.area`). Fond vectoriel
  embarqué (`assets/geo/basemap.bin`, format FBM1 : terres, lacs, frontières en deux
  niveaux de détail, noms des pays FR/EN). Hors ligne, la vue n'a aucun module de
  tuiles : aucune requête réseau n'est possible.
- **Diaporama** : même requête que la grille d'origine (photos seules si elle est
  mixte), pagination identique à la visionneuse, préchargement de la photo suivante,
  `keepScreenOn`.
- **Widget** : Glance, photo choisie par `WidgetPhotoPicker` (souvenir du carrousel avec
  son titre, sinon favori, sinon photo récente), stable sur une période de 4 h ;
  renouvellement par WorkManager tant qu'une instance existe, et immédiatement après un
  déplacement vers le coffre.

#### Souvenirs avec quelqu'un et voyages
Pour chaque fenêtre, `MemoriesRepository` produit au plus trois cartes, dans cet ordre : le
voyage commencé dans la fenêtre, le souvenir général, un souvenir « avec » une personne
(titres : `MemoryTitles`, communs au carrousel, à la grille ouverte et au widget). Les
lectures propres à ces cartes (`MemoryDao`) sont des agrégats SQL ou des colonnes
compactes, sur la clause WHERE de `MediaQueryBuilder` (corbeille et dossiers masqués exclus).
- **Avec quelqu'un** : personnes non masquées de la fenêtre, comptées en SQL. `MemoryPeople`
  retient la plus présente parmi celles qui figurent sur au moins 5 médias et moins de 80 %
  d'entre eux (au-delà, le souvenir général la montre déjà) ; une personne nommée d'abord,
  sinon une personne sans nom revenue sur au moins 3 mois (un proche plutôt qu'un inconnu),
  titrée « ensemble » avec son visage. Requête : fenêtre + `personId`.
- **Voyages** : `TripDetector`, logique pure sur les points (date, latitude, longitude) des
  médias géolocalisés. Chez soi : la cellule d'une grille de ~25 km photographiée pendant le
  plus de mois distincts (un séjour tient en un ou deux mois), par année pour suivre un
  déménagement (à défaut, celle de l'année la plus proche). Un voyage enchaîne les jours avec un média à plus de 100 km de chez soi (un
  jour sans média géolocalisé toléré, une journée à la maison l'interrompt), 5 médias
  lointains au moins ; il apparaît quand son premier jour tombe dans la fenêtre. Requête : du
  premier au dernier média lointain, sans les photos de la maison du départ et du retour.
  Titre : la ville qui rassemble au moins la moitié des médias lointains, sinon le pays.
- **Sans doublon** : une fenêtre passée à 80 % dans le voyage n'a pas de souvenir général ;
  deux cartes d'une même fenêtre ne partagent pas leur couverture.

### 6.9 Personnes (regroupement des visages)
Étape d'analyse `FaceAnalysisStep` (package `people/`), après l'analyse principale :
photo réduite à 1 280 px (orientation appliquée par `ImageDecoder`) → ML Kit Face
Detection (mode précis, 5 points de repère ; visages < 48 px, de profil ou flous ignorés) →
similitude vers le gabarit ArcFace 112 × 112 (`FaceAlignment`, conventions de
`FaceRecognizerSF` d'OpenCV) → SFace int8 via ONNX Runtime (entrée RVB 0..255) →
empreinte de 128 flottants normalisée (`face.embedding`). Après chaque lot,
`FaceClusterer` (logique pure) rattache les nouveaux visages au centroïde le plus proche
(cosinus ≥ 0,42, un visage par photo et par personne) ou crée une personne quand trois
visages en attente sur trois photos forment un groupe cohérent (voisins ≥ 0,45, chacun
proche du centroïde des autres). Les rattachements manuels (`face.manual`) ne sont jamais
modifiés ; une personne sans nom n'est proposée qu'à partir de trois photos. Vignettes
rondes : `FaceThumbnailFetcher` Coil (découpe dans la miniature MediaStore, sinon décodage
réduit de la région utile de l'original). Recherche : `SearchTerm.personIds`. La télémétrie
d'ONNX Runtime est neutralisée (fournisseur retiré du manifeste, `setTelemetry(false)`).

#### Liste des personnes
- **Tri** (réglage `peopleSort`) : plus de photos, nom (`Collator` primaire : ni casse ni
  accents, personnes sans nom à la fin) ou photo la plus récente. `FaceDao.observePeopleList`
  fournit l'épinglage et la date la plus récente, `PeopleRepository` trie ; les personnes
  épinglées (`person.pinned`, appui long) restent en tête, dans l'écran comme dans la rangée
  de l'onglet Albums.
- **« Est-ce la même personne ? »** (`MergeSuggestions`) : paires de personnes visibles dont
  les centroïdes (moyenne normalisée des empreintes de leurs visages) ont une similarité d'au
  moins 0,38, un peu sous le seuil de rattachement (0,42) : la moyenne de nombreux visages est
  peu bruitée. Une carte en tête de l'écran Personnes les propose une à une, les plus
  probables d'abord, sans les paires refusées ni deux personnes nommées différemment ; la
  cible est la personne nommée, sinon la plus présente. Recalcul hors thread principal à
  chaque changement des personnes ou des refus (flux Room) : seules les personnes dont les
  visages ont changé (nombre et somme de leurs identifiants) relisent leurs empreintes, par
  tranches. Les refus (`person_refusal`, clé ordonnée) référencent `person` par clé étrangère
  et disparaissent avec l'une ou l'autre personne (fusion, suppression).

#### Revue des visages et photos ensemble
« Vérifier les visages » (`FaceReviewScreen`, `PersonFacesRoute`) montre tous les visages
d'une personne, du moins au plus ressemblant au centroïde de ses empreintes (`FaceReview` :
cosinus à la moyenne normalisée), pour repérer d'abord les erreurs du regroupement. Retirer
ou déplacer une sélection (vers une autre personne ou une nouvelle) passe le visage en
rattachement manuel (`face.manual = 1`, personne nulle pour un retrait) : le regroupement
n'y revient plus. L'annulation (`FaceTransfer`) rend les rattachements d'origine, recrée la
personne si elle a disparu et supprime celle créée pour l'occasion ; une personne vidée
disparaît des listes, une vignette retirée retombe sur le meilleur visage. « Avec… » propose
les personnes vues sur les photos de la personne (nommées d'abord, avec le nombre de photos
en commun) et ouvre une collection `photosTogether(ids)` : un `SearchTerm(personIds = [id])`
par personne, combinés par ET, soit la requête de la recherche « alice bob ».

### 6.10 Albums automatiques
Section de l'onglet Albums, après les personnes : une tuile (couverture, nombre) par catégorie
d'au moins 3 médias, hors corbeille et dossiers masqués de la timeline. Chaque album est un
`SearchTerm` (`SmartAlbums`), ouvert dans la grille générique (`CollectionRoute`) :
- Animaux, Nourriture, Paysages, Fêtes, Véhicules, Sport : catégories du dictionnaire,
  sous-catégories comprises (`LabelDictionary.labelsOf`), comme la recherche ; les paysages
  écartent la végétation en gros plan (fleur, plante) ;
- Documents : étiquette de document papier, ou texte reconnu d'au moins 200 caractères
  normalisés (un ticket de caisse en compte quelques centaines, une enseigne rarement 100) ;
- Reçus : étiquette « Receipt », ou au moins deux indices (mots entiers de `text_key` :
  « total », « tva », « montant », synonymes de l'étiquette… ; symboles du texte brut : « € »),
  si bien qu'un « TOTAL » de station-service ou les prix d'un menu ne suffisent pas.

Le texte reconnu (`SearchTerm.recognizedText`) ne compte que hors captures d'écran. Nombres et
couvertures (`bestCover`) de tous les albums viennent d'une seule requête
(`MediaQueryBuilder.summaries`, `UNION ALL`) observée sur `media`, `media_label`,
`media_analysis` et `media_text` : les tuiles suivent l'analyse sans réagir aux lots de visages
ni d'empreintes.

---

## 7. Interface

### 7.1 Navigation
```
NavHost racine
├── Main  ── NavigationSuiteScaffold (barre en compact, rail sinon)
│           └── NavHost des onglets : Photos · Albums · Recherche · Plus
│               (+ détail d'album, favoris, corbeille, nettoyage, carte,
│                coffre, réglages, à propos…)
├── Viewer(query, mediaId)       plein écran, au-dessus des onglets
├── ExternalMedia(uri)           média reçu d'une autre application
├── Editor(mediaId)
├── Slideshow(query, mediaId)
├── VaultAlbum(albumId)
└── VaultViewer(itemId, albumId)
```
Le `NavHost` racine est enveloppé dans un `SharedTransitionLayout` : la miniature
de la grille et l'image de la visionneuse partagent la même clé
(`media-<id>`), d'où une transition fluide grille → visionneuse et retour.

Intentions : `MainActivity` (`singleTop`) traduit chaque intention en `OpenRequest`. Le widget
(`app.focale.action.OPEN_MEDIA`) ouvre la visionneuse dans la timeline ; `ACTION_VIEW` et les
`REVIEW` des appareils photo (images et vidéos, `content://`, `file://` du stockage partagé)
mènent à `ExternalMediaRoute`. `ExternalMediaRepository` y cherche le média dans l'index
(identifiant MediaStore, `MediaStore.getMediaUri` pour un document, colonne DATA pour un chemin ;
un média tout juste arrivé est attendu le temps d'une synchronisation, 2 s au plus) et ouvre la
visionneuse sur les médias de son dossier ; sinon, visionneuse simple de l'URI (zoom, lecture,
partage, « Ouvrir avec »), lue grâce à la permission accordée par l'intention. Lancée pour cette
ouverture, l'activité en fait sa première destination, sans timeline dessous, et se referme au
retour pour rendre la main à l'application appelante ; reçue par `onNewIntent`, l'ouverture
s'empile sur l'écran courant. `REVIEW_SECURE` n'est pas déclarée : l'activité unique, qui donne
accès à toute la galerie, ne doit pas s'afficher sur l'écran verrouillé. Réglages → Fichiers
résout `ACTION_VIEW` d'un JPEG (`MATCH_DEFAULT_ONLY`, `<queries>` du manifeste) pour dire quelle
application ouvre les photos et proposer de choisir Focale.

### 7.2 Thème
- `MaterialExpressiveTheme` + `MotionScheme.expressive()` (ressorts).
- Couleurs : `dynamicLight/DarkColorScheme` (Android 12+), sinon schéma généré
  depuis la couleur de marque. Modes clair / sombre / système + AMOLED (surfaces noires).
- « Couleur depuis la photo » : couleur dominante (MaterialKolor) d'une miniature
  de 112 px, mise en cache, schéma animé via `DynamicMaterialTheme`.
- Typographie : Google Sans Flex (police variable embarquée, axe `ROND`
  arrondi) ; Display/Headline pour les dates de la timeline.
- Formes généreuses (jusqu'à 36 dp), miniatures arrondies, morphing
  (`MaterialShapes` → cercle) de l'indicateur de sélection et des boutons.

### 7.3 Grille
- `LazyVerticalGrid` + Paging 3, en-têtes de date pleine largeur insérés par
  `insertSeparators` (jour / mois / année, ou automatique selon le zoom).
- Pincement : 2 à 7 colonnes (décalées sur tablette), ancrage sur l'élément visé.
- Défilement rapide : poignée + bulle de date calculée depuis un histogramme
  mensuel (`GROUP BY` mois) — exact et sans charger les pages.
- Sélection : appui long puis glissement (calcul de l'élément sous le doigt +
  défilement automatique en bord d'écran), retour haptique à chaque ajout.

### 7.4 Visionneuse
- `HorizontalPager` sur la `PagingSource` de la grille ; photos zoomables par telephoto
  (sous-échantillonnage des grandes images), glisser vers le bas pour fermer.
- Rotation à deux doigts : geste observé à la passe initiale sans rien consommer (zoom,
  balayage, double appui restent à telephoto), rotations parasites d'un pincement ignorées
  sous 15° (`RotationMath`). Au relâchement, ressort vers le quart de tour le plus proche,
  puis cadre aux dimensions de l'écran échangées pour que zoom et bords restent justes.
  Barre « Enregistrer la rotation » / « Annuler » ; changer de photo ou quitter annule.
- Enregistrement (`PhotoRotator`) : JPEG et WebP sans perte, orientation EXIF composée avec
  l'actuelle, miroirs compris (`ExifOrientation`) ; deux octets réécrits sur place si la
  balise existe (carte de gain Ultra HDR, vidéo de photo animée intactes), sinon segment
  EXIF refait ; consentement de « remplacer l'original ». Autres formats (PNG, dont Android
  ignore l'orientation EXIF ; HEIF et AVIF) : rendu de l'éditeur, en copie ou à la place.
  Clé mémoire Coil des images pleine taille liée à `date_modified`.

---

## 8. Découpage du développement

| Étape | Contenu |
|---|---|
| **1. Cœur** | Build, thème, permissions (dont accès partiel Android 14+), index Room, synchro, timeline paginée zoomable + défilement rapide, visionneuse (zoom, swipe, glisser pour fermer, vidéo, GIF, infos EXIF), navigation adaptative, CI |
| **2. Organisation** | Albums dossiers + virtuels, favoris, corbeille, sélection multiple glissée, copier / déplacer / renommer / supprimer / partager, tri et filtres |
| **3. Recherche** | Worker d'analyse, ML Kit, dictionnaire FR/EN, recherche mot-clé / date / lieu, doublons, flous, écran de nettoyage |
| **4. Édition** | Recadrage, rotation, filtres, luminosité / contraste / saturation, enregistrement en copie ou remplacement |
| **5. Coffre & vie privée** | Coffre chiffré biométrie / code, suppression EXIF/GPS au partage |
| **6. Extras** | Souvenirs, carte osmdroid, diaporama, widget Glance, finitions |

Chaque étape fait l'objet d'un commit compilé et testé (tests unitaires de la
logique pure : requêtes, regroupement, parsing de recherche, hash, flou, PIN…).

---

## 9. Permissions

| Permission | Usage |
|---|---|
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` (33+) / `READ_EXTERNAL_STORAGE` (≤ 32) | lecture des médias |
| `READ_MEDIA_VISUAL_USER_SELECTED` (34+) | accès partiel, bannière « Gérer la sélection » |
| `ACCESS_MEDIA_LOCATION` | coordonnées GPS non masquées des EXIF |
| `MANAGE_MEDIA` (31+, facultatif) | opérations sans confirmation à chaque fois |
| `USE_BIOMETRIC` | coffre-fort |
| `INTERNET` (variante `standard` uniquement) | fond de carte OSM (sur activation explicite), mises à jour |
| `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (`standard`) | installation des mises à jour, sans confirmation sur Android 12+ |
