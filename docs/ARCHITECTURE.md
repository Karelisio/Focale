# Focale — Plan d'architecture

Focale est une galerie photo Android 100 % hors ligne, construite en Kotlin avec
Jetpack Compose et Material 3 Expressive. Ce document décrit l'architecture
cible, les choix techniques structurants et le découpage du développement.

---

## 1. Principes directeurs

| Principe | Conséquence concrète |
|---|---|
| **Hors ligne, zéro donnée envoyée** | Aucun SDK d'analytics. La télémétrie de ML Kit (`datatransport`/CCT) est neutralisée dans le manifeste. Les seules connexions réseau sont le fond de carte OpenStreetMap, **désactivé par défaut** et activable explicitement, et la recherche quotidienne de mises à jour sur GitHub (désactivable) ; la diffusion vers une TV (DLNA, § 6.12) ne parle qu'aux adresses du réseau local (et, pour une TV qui se désigne par son nom, au résolveur DNS du réseau Wi-Fi). La variante `strict` retire toute permission réseau, `INTERNET` comprise. |
| **Fluide avec 50 000+ médias** | MediaStore est indexé dans Room ; toutes les listes passent par Paging 3 (placeholders + sauts) ; les miniatures viennent du cache système de MediaStore (`loadThumbnail`) via un `Fetcher` Coil dédié. Tests de charge sur 50 000 médias, plans d'exécution surveillés, profil de référence : [PERFORMANCE.md](PERFORMANCE.md). |
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
| Pagination | Paging 3 (`MediaPagingSource` sur des `@RawQuery` Room : positions, placeholders, sauts ; nombre total par l'index) |
| Images | Coil 3 (miniatures MediaStore, GIF animés, frames vidéo), telephoto (zoom + sous-échantillonnage, glisser pour fermer) |
| Vidéo | Media3 ExoPlayer + `media3-ui-compose` (contrôles M3 maison) |
| Couleurs depuis photo | MaterialKolor (quantification + score Material Color Utilities) |
| Formes | `MaterialShapes` + androidx.graphics.shapes (`Morph`) |
| ML sur l'appareil | ML Kit Image Labeling, Text Recognition v2 (alphabet latin) et Face Detection, modèles **embarqués**, pas de téléchargement ; SFace (OpenCV Zoo) et TinyCLIP quantifié (recherche en langage naturel), exécutés par ONNX Runtime |
| Arrière-plan | WorkManager |
| Carte | osmdroid + fond vectoriel hors ligne (Natural Earth, domaine public) |
| Géocodage inverse | Hors ligne : base GeoNames (CC BY 4.0) embarquée, index spatial en grille, noms français et anglais (alternateNames) |
| Chiffrement | Tink `StreamingAead` (AES-256-GCM-HKDF, segments de 1 Mo), keyset protégé par Android Keystore |
| Authentification | androidx.biometric (biométrie forte ou identifiants de l'appareil) + code PIN (PBKDF2) |
| Widget | Glance (`GlanceTheme` = couleurs dynamiques du système) |
| Diffusion | API `Presentation` / `DisplayManager` (écran externe) ; DLNA/UPnP écrit dans l'application (SSDP, SOAP, serveur HTTP local), sans SDK Google Cast |
| CI | GitHub Actions : tests unitaires, lint, APK debug + release, test de fumée de l'APK release sur émulateur (mise à jour, langue, impression, écran externe, sauvegarde, analyse) |

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
│ GPS, lieu, texte), purge corbeille, rafraîchissement du widget, coffre : │
│ import, contrôle au lancement, nouveaux médias, purge de sa corbeille    │
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
│   ├── locale/                 langue de l'application (AppLocales), changements de langue
│   ├── image/                  Fetchers Coil (miniatures, fichiers chiffrés)
│   ├── permissions/            état des permissions (complet / partiel / refusé)
│   └── util/                   dates, formats, haptique, extensions
├── data/                       repositories
├── backup/                     sauvegardes par mot de passe : données, format commun au coffre, automatiques
├── cast/                       diffusion : écrans externes (display/), TV DLNA (dlna/), service au premier plan
├── collage/                    collages : dispositions, géométrie, cadrage, orientation, rendu
├── ml/                         étiquetage, dictionnaire FR/EN, pHash, flou, géocodeur, texte
├── people/                     visages : détection, alignement, SFace, regroupement
├── panorama/                   GPano (XMP), vidéos 360° (MP4 : équirectangulaire, cube, maillage), pose, choix de la vue, sphère OpenGL ES, capteur de rotation
├── print/                      impression : mise en page, document PDF du framework d'impression
├── work/                       workers
├── vault/                      crypto, stockage, verrouillage
├── widget/                     widget Glance
└── ui/
    ├── theme/                  couleurs, typo (Google Sans Flex), formes, mouvement
    ├── components/             composants réutilisables
    ├── navigation/             routes typées, NavHost, barre adaptative
    ├── timeline/  viewer/  albums/  search/  more/  favorites/  trash/
    ├── cleanup/   editor/  vault/   map/     slideshow/  memories/  settings/  backup/  cast/
    ├── collage/
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

Index : `date_taken`, et trois index composés qui finissent par la date, pour lire les grilles
dans leur ordre sans trier la bibliothèque : (`is_trashed`, `date_taken`) pour la timeline,
(`bucket_id`, `is_trashed`, `date_taken`) pour les dossiers, (`media_type`, `is_trashed`,
`date_taken`) pour les filtres et les files de l'analyse (voir [PERFORMANCE.md](PERFORMANCE.md)).

### 5.2 Métadonnées de l'app

| Table | Contenu |
|---|---|
| `media_analysis` | état d'analyse, `phash`, `blur_score`, `latitude`/`longitude`, `place`, `place_key` (ses noms normalisés, traductions comprises), `country` |
| `media_label` | (media, étiquette ML Kit canonique, confiance) ; index (`label`, `confidence`, `media_id`), version 7 |
| `label_count` | nombre de médias hors corbeille de chaque étiquette (onglet Recherche), tenu à jour à chaque écriture des médias ou des étiquettes (`LabelCountQueries`), version 8 |
| `media_name` | index plein texte FTS4 des noms de fichiers : chaque suite de lettres du nom et ses suffixes (`MediaNames`), tenu à jour avec `media` (`MediaDao.upsert`, `deleteByIds`), version 8 |
| `media_name_pending` | médias dont les mots du nom attendent d'être écrits dans `media_name` : la première indexation les y laisse, `MediaSyncManager.indexNames` les écrit ensuite en arrière-plan, version 9 |
| `media_text` | texte reconnu dans la photo (une ligne par ligne) et sa clé de recherche normalisée (lettres et chiffres collés aussi séparés) |
| `media_text_fts` | index plein texte FTS4 des clés de `media_text` (`unicode61`, sans accents), à contenu externe : tenu à jour par les déclencheurs que Room crée avec lui, version 9 |
| `place_count` | nombre d'analyses de chaque clé de lieu (`place_key`), tenu à jour à chaque écriture des analyses (`PlaceCountQueries`), version 9 |
| `media_embedding` | empreinte TinyCLIP de chaque photo (512 flottants, norme 1) |
| `favorite` | médias favoris |
| `album` / `album_media` | albums virtuels et leur contenu |
| `trash_item` | corbeille applicative (API 29 uniquement) |
| `vault_item` | entrées du coffre (fichiers chiffrés), avec leur album du coffre (`album_id`, facultatif), le journal de leur import (`source_media_id`, `importing`) et leur mise à la corbeille (`deleted_at`) |
| `vault_album` | albums du coffre : nom, dossier d'origine d'un album-dossier, date du dernier import |
| `media_date` | date d'origine d'un média restauré ou copié, quand MediaStore n'en a retrouvé aucune (voir § 6.1) |
| `search_history` | recherches récentes |
| `face_scan` / `face` / `person` | visages détectés, empreintes SFace et personnes (nom, vignette, masquée, épinglée) |
| `person_refusal` | paires de personnes déclarées distinctes (« Pas la même ») |

Les tables d'analyse ne référencent pas `media` par clé étrangère (l'index est
reconstruisible) : leurs lignes orphelines sont purgées après synchronisation. Les
migrations (`DatabaseMigrations`) reprennent le SQL exporté par Room dans
`app/schemas` et sont testées contre ce schéma.

Ce que l'utilisateur a construit (`favorite`, `album`, `album_media`, `person`, `face`,
`face_scan`, `person_refusal`, `media_date`, `search_history`), avec les dossiers masqués et les
réglages, part dans la sauvegarde des données (§ 6.11) ; ce que l'analyse recalcule, ce qui se
déduit de l'index ou de l'analyse (`label_count`, `place_count`, `media_name`, `media_name_pending`,
`media_text_fts`), le coffre et la corbeille, non.

Les comptes tenus à jour (`label_count`, `place_count`) ne sont justes que si toutes les écritures
passent par les méthodes prévues des DAO : au début de chaque passage d'analyse,
`AnalysisDao.checkCounts` compare le total de chacun aux lignes qu'il compte et le recalcule en cas
d'écart.

### 5.3 `MediaQuery` : une requête pour tout

Toutes les grilles (timeline, album, favoris, résultats de recherche…) sont décrites
par un objet sérialisable `MediaQuery` (filtres de type, dossier, album virtuel,
favoris, étiquettes, période, lieu, texte, tri). Un `MediaQueryBuilder` le traduit
en SQL ; `MediaPagingSource` en lit les pages (`LIMIT`/`OFFSET`, clé = position, comme
`LimitOffsetPagingSource` de Room) et compte le total par `MediaQueryBuilder.count` (l'index
seul pour la timeline), invalidée par l'`InvalidationTracker` de Room.

Bénéfice clé : la visionneuse reçoit **la même** `MediaQuery` que la grille d'où
l'on vient, calcule la position de départ par un `COUNT(*)` et ouvre une
`PagingSource` démarrant à cet index (`initialKey`). Le swipe parcourt donc
exactement les mêmes éléments, dans le même ordre, même sur 50 000 médias.

Une requête qui ne lit que `media` (timeline, dossiers, filtres de type ou de date :
`MediaQueryBuilder.readsOnlyMedia`) n'est observée que sur cette table : les lots de l'analyse ne
rechargent ni ses pages, ni son nombre, ni son histogramme.

---

## 6. Flux clés

### 6.1 Synchronisation MediaStore → Room
1. Au démarrage (et à chaque changement détecté par `ContentObserver`, avec
   anti-rebond), `MediaSyncManager` compare MediaStore et l'index.
2. API 30+ : synchro incrémentale par `GENERATION_MODIFIED` + détection des
   suppressions par la liste des `_ID` ; sinon comparaison complète
   (`id` + `date_modified`).
3. Écritures par lots (1 000 lignes ajoutées ou modifiées, 500 suppressions) dans des
   transactions ; l'`InvalidationTracker` de Room invalide les `PagingSource` → l'UI se met à jour seule.
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
5. GPS EXIF (avec `ACCESS_MEDIA_LOCATION`) → ville/pays via géocodeur hors ligne ; la base
   (`assets/geo/cities.bin`, format FCG2) précède ses 171 000 lieux de leurs noms français et
   anglais quand ils diffèrent du nom GeoNames (2 749 en français, 378 en anglais : « Lisbon »
   → « Lisbonne », « Köln » → « Cologne » ; 1 885 autres noms anglais, cherchés mais non
   affichés : « Makkah » → « Mecca »), lus seuls par `PlaceNames`. `media_analysis.place`
   garde le nom GeoNames ; l'affichage (infos, carte, recherche, titres des voyages, widget)
   choisit la forme de la langue de l'application (`PlaceName.display`) ; `place_key` réunit les
   formes normalisées (« lisbon | lisbonne »). Une base FCG1 (sans noms traduits) se lit encore ;
   à chaque passage, l'analyse met à jour les clés des lieux déjà analysés qui diffèrent
   (`AnalysisDao.refreshPlaceKeys`, par lieu distinct, sans nouvelle version de l'analyse) : ni
   géocodage ni étiquetage à refaire ;
6. puis étapes complémentaires (`AnalysisStep`), dont le texte : ML Kit Text Recognition
   sur la photo réduite à 1600 px (orientation appliquée), captures d'écran d'abord, les
   plus hautes lues par bandes à leur largeur d'écran ; aussi lancée à la demande par
   l'action « Texte » de la visionneuse.

Recherche : la requête est normalisée (minuscules, sans accents), confrontée au
dictionnaire FR/EN des 447 étiquettes (avec synonymes et hyperonymes :
« chien » couvre aussi les races détectées), aux lieux (début d'un mot de l'un de leurs noms :
« Lisbonne » comme « Lisbon » ; une carte de lieu cherche le nom GeoNames exact), aux dates
(« 2023 », « juillet 2022 », « 14/07/2021 »), aux noms de fichiers et au texte reconnu
dans les photos (un mot du texte qui commence par le mot cherché : « fact » trouve « facture »,
« wifi » « Wi-Fi », « 2024 » « #vacances2024 »).

Index de la recherche (base v8, voir [PERFORMANCE.md](PERFORMANCE.md)) : un mot d'au moins trois
lettres ASCII cherché dans les noms de fichiers passe par l'index plein texte `media_name` (FTS4,
tokenizer `simple`) : chaque suite de lettres d'un nom y figure avec ses suffixes (« Screenshot » :
« Screenshot creenshot … shot hot »), si bien qu'un mot contenu dans un nom commence l'un d'eux ;
les candidats sont revérifiés par le `LIKE '%mot%'` d'avant, sans résultat perdu ni ajouté
(`MediaNameSearchTest`). Un texte plus court, avec un chiffre ou un séparateur garde le `LIKE` sur
chaque nom. Un mois de toutes les années (« juillet ») est borné en Kotlin dans le fuseau courant,
de 1900 à 2049 (minuit local du premier jour, comme les en-têtes), puis cherché par dichotomie sur
la date de chaque média ; hors de ces années, le mois local de SQLite. Les étiquettes de l'onglet
Recherche sont lues dans `label_count`, sans recompter `media_label`.

Base v9 : le texte reconnu passe par l'index plein texte `media_text_fts` (FTS4, `unicode61` sans
accents, sur la clé normalisée), par début de mot ; quand rien d'autre dans la bibliothèque ne
répond au mot (étiquette, lieu, pays, nom de fichier, personne, début d'un mot d'un texte), l'ancien
`LIKE` le cherche n'importe où dans les textes (`MediaTexts`) : « fi » trouve encore « WIFI » quand
rien d'autre ne correspond, mais « crème » ne trouve plus « demi-écrémé » (voir
[PERFORMANCE.md](PERFORMANCE.md)). Un mot est cherché parmi les clés distinctes des lieux
(`place_count`), puis les médias de ces lieux par l'index de `place_key`. La première indexation de
la bibliothèque écrit les mots des noms après coup, en arrière-plan (`media_name_pending`) ; d'ici
là, les noms en attente sont comparés par `LIKE`, sans résultat perdu.

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

Pastilles de filtre : dans l'onglet Recherche, le texte se combine par ET à des pastilles
(`SearchFilters`, `data/`) : personnes (un `SearchTerm(personIds = [id])` chacune, soit les
photos ensemble), lieu exact (`exactPlace` sur son nom GeoNames, et son pays ; affiché et
suggéré par ses noms français ou anglais), période (`dateFrom/dateTo` croisés avec
ceux du texte, ou `month`) et type (`filter` ; deux types ou deux mois inconciliables donnent une
requête vide). Elles se tirent de la saisie (`SearchChipDetector`, mêmes règles que
`SearchQueryParser` ; un appui retire leurs mots du texte) ou se choisissent : personnes, lieux,
années et mois où la recherche a des médias, sélecteur de dates M3. Les résultats s'affichent
dans l'onglet : `SearchSession` garde la recherche dans l'état sauvegardé (rotation, processus
tué) et produit la `MediaQuery` de la grille, transmise telle quelle à la visionneuse ; une
pastille s'applique aussitôt, un texte validé garde la requête précédente le temps de la
recherche par le sens. Sans résultat, le nombre obtenu sans chaque pastille est proposé.
Historique : une entrée par recherche, remplacée à mesure qu'elle s'affine ; le texte seul
comme avant, sinon la recherche en JSON dans la même colonne (pas de migration), affichée
lisiblement (« plage · Alice · 2023 ») et relancée telle quelle (une personne fusionnée depuis
est retrouvée par son nom).

Doublons : regroupement par dHash, distance de Hamming ≤ seuil, candidats obtenus
par découpage du hash en 4 bandes de 16 bits (évite le O(n²)).

Étapes complémentaires : une fois l'analyse principale à jour, le même worker
exécute les `AnalysisStep` fournies par multiliaison (texte, visages, empreintes).
Chacune tient son avancement dans sa table (version et `date_modified` analysés) :
elle reprend où elle s'était arrêtée et recalcule après une évolution ou une édition.
Chaque étape expose aussi son avancement (`progress`, mêmes critères que sa file, et les tables
dont il dépend) : `AnalysisScheduler.status` le combine à celui de l'analyse principale pour
l'onglet Recherche et les réglages. Chaque lecture parcourt toute la bibliothèque : tant qu'il est
affiché, un compteur est relu au plus toutes les 3 s (`sampledQuery`), aussitôt quand le worker
vide une file ou finit son passage (`AnalysisMilestones`). « Analyser maintenant » (`runNow`) lance un passage marqué
manuel, qui ignore « en charge uniquement » jusqu'à la fin (ses continuations aussi).
Un échec global (modèle indisponible, étiquetage en échec sur tout un lot) est noté par
`AnalysisIssues` jusqu'au prochain lot réussi et affiché sous l'analyse concernée, avec
l'état « interrompue » plutôt que « en attente ».

Files d'attente : un passage parcourt chaque file une fois, des plus récents aux plus anciens
(le texte : captures d'abord) ; chaque lot reprend après le dernier média du précédent
(`AnalysisQueue`, `AnalysisStep.pendingAfter`) au lieu de relire les médias déjà traités, si bien
que le coût d'une sélection ne croît pas avec l'avancement. Un média arrivé pendant le passage,
plus récent que le curseur, va au passage suivant (voir [PERFORMANCE.md](PERFORMANCE.md)).

Release : le mode strict de R8 (AGP 9) retire le constructeur des « registrars » ML Kit,
instanciés par réflexion depuis le manifeste ; `proguard-rules.pro` le garde, et la CI
vérifie les APK release avec `tools/release/check_constructors.py`, puis avec
`tools/release/check_privacy.py` : aucun fournisseur d'ONNX Runtime ni backend de télémétrie de
ML Kit dans le manifeste (leur retrait se fait par leur nom, qu'une mise à jour peut changer),
ni permission réseau (`INTERNET`, état du réseau et du Wi-Fi, multicast, réseau local) ni service
de diffusion DLNA dans la variante strict. Le job `smoke` exécute
ensuite l'APK release universel sur un émulateur (API 34, `google_apis`, clavier matériel,
`tools/release/smoke_test.sh`) ; la publication d'une version en dépend. L'interface y est
pilotée par `smoke_ui.py` (arbre d'accessibilité de `uiautomator dump`, appuis par `input`,
textes anglais de l'émulateur), la base et les réglages lus par `smoke_db.py` (adb root) :
- mise à jour : la dernière version GitHub que l'APK peut remplacer (`version_precedente.sh` :
  même certificat, code de version inférieur ou égal, pas construite par le commit testé ;
  `FOCALE_PREVIOUS_TAG` en impose une, pour éprouver une migration plus ancienne) analyse les
  deux images de test (texte, visage) ; un favori, un album (visionneuse, menu « Add to album »)
  et « Pure black » sont créés dans l'interface ; `adb install -r` de l'APK testé, puis
  démarrage sans plantage (tampon `crash` de logcat), « Nouveautés » refermées, base à la
  version et à l'empreinte (`room_master_table`) du dernier schéma de `app/schemas`, mêmes
  favoris, albums et réglage. Sans version utilisable (fork sans secrets de signature…),
  avertissement ;
- langue : `cmd locale set-app-locales` en, fr, en ; l'onglet Recherche change de libellé ;
- impression : « Print » de la visionneuse, « Save as PDF » du spouleur, PDF enregistré par le
  sélecteur de documents (à défaut de ce service, ouverture du dialogue seulement) ;
- écran externe : `overlay_display_devices` 1920x1080/320, visionneuse ouverte (action
  `OPEN_MEDIA` du widget) ; une fenêtre de Focale doit être affichée hors de l'écran 0
  (`dumpsys window`) ; la photo du visage, demandée par-dessus, doit remplacer celle du texte
  (nom de fichier de la photo affichée) ; l'écran est retiré sans plantage ;
- sauvegarde des données : « Back up now », mot de passe, sélecteur de documents
  (Téléchargements) ; `pm clear` ; nouvelle analyse attendue jusqu'à étiquettes, texte
  « facture » et visages ; « Restore » du fichier, résumé, bilan ; favori, album et réglage
  revenus. Piloter l'interface et le sélecteur du système éprouve l'APK release lui-même (R8,
  sérialisation, accès aux documents), ce qu'un test instrumenté sur l'APK de debug ne ferait
  pas.

Chaque étape échoue avec un message propre ; captures d'écran et journal complet de logcat
sont publiés en artefact quand le job échoue.

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

- Corbeille du coffre : supprimer un élément du coffre le met à la corbeille (`deleted_at`), où
  il reste chiffré 30 jours, hors des grilles, albums, sauvegardes et contrôles ; il peut en être
  remis (dans son album s'il existe encore) ou effacé définitivement. `VaultTrashPurgeWorker`
  efface chaque jour ceux dont le délai est passé (fichiers chiffrés compris).
- Sauvegarde automatique (`VaultAutoBackup`, réglage du menu du coffre) : chaque jour ou chaque
  semaine, dans un dossier choisi par `ACTION_OPEN_DOCUMENT_TREE` (autorisation persistante, dossier
  en lecture seule refusé), avec un mot de passe saisi deux fois qui n'est pas conservé : les clés
  d'archive en sont tirées une fois (`VaultArchive.deriveKeys`, soit `PasswordArchive.deriveKeys`
  commun avec la sauvegarde des données : PBKDF2 à sel aléatoire puis HKDF, témoin et clé du flux)
  et gardées dans le DataStore du coffre, chiffrées par une AEAD Tink dont le jeu de clés est
  protégé par le Keystore (`VaultAutoBackupKeys`, sur `AutoBackupKeys`). Les archives restent au format
  `.fvault` v1 (en-tête commun, restaurables avec le mot de passe) : Tink tire un sel et un préfixe
  de nonce par flux, réutiliser la clé est sûr. `VaultAutoBackupWorker` (WorkManager périodique,
  batterie et stockage non faibles, coffre verrouillé) crée `focale-coffre-auto-AAAA-MM-JJ.fvault`
  et l'écrit en flux par l'export de la sauvegarde manuelle (document supprimé s'il échoue), ne
  garde que les 3 dernières (reconnues à leur nom), saute la sauvegarde si l'empreinte du coffre
  (identifiants, albums, rangements) et des clés n'a pas changé, et met à jour la date de dernière
  sauvegarde. Échec (dossier inaccessible, autorisation retirée, plus de place) : erreur dans le
  réglage et notification, sans nom d'album ni de fichier. Planification, dossier, clés, rotation,
  empreinte et échecs sont la logique commune des sauvegardes automatiques (`AutoBackupEngine`),
  partagée avec celle des données (§ 6.11) ; chacune garde son réglage, ses clés et ses fichiers.
- Format `.fvault` (`VaultArchive`) : en-tête de `PasswordArchive` (signature `FOCALE-VAULT`,
  version, dérivation, itérations, sel, témoin du mot de passe), puis flux Tink AES-256-GCM-HKDF
  (segments de 1 Mio, en-tête en donnée associée) : manifeste JSON, puis média et miniature de
  chaque élément. Primitives communes avec la sauvegarde des données, dont la signature diffère :
  chaque restauration refuse le fichier de l'autre avant le mot de passe, en disant où le restaurer.
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
- Album entier (menu de `CollectionScreen` ou appui long sur sa carte dans l'onglet Albums,
  même pipeline d'import) : un dossier rejoint l'album du coffre lié à son `BUCKET_ID`
  (`source_bucket_id`, `source_path`), créé au besoin ; un album de l'application devient un
  nouvel album du même nom, puis il est supprimé s'il a été vidé (il ne resterait dans la
  galerie que son nom, indice de ce qu'il contenait).
- Nouveaux médias d'un album-dossier (bannière dans l'album, « Ajouter ») : médias du dossier,
  hors corbeille, dont `date_added` suit `last_import_at` à une minute près (`DATE_ADDED` est à
  la seconde et l'index suit MediaStore avec retard). Les originaux importés ayant quitté
  MediaStore, la marge ne repropose que ceux dont la suppression a échoué. `last_import_at` est
  l'instant pris juste avant l'instantané du dossier (après une synchronisation), retenu dès
  qu'un média est entré.
- Notification des nouveaux médias (`VaultNewMediaWatch`, `VaultNewMediaAlerts`) : une tâche
  WorkManager déclenchée par les changements de MediaStore (images et vidéos, regroupés : 30 s,
  2 min au plus), puis réarmée, compte les nouveaux médias de chaque album-dossier (même requête
  que la bannière) et tient à jour une notification par album, « N nouvelles photos dans
  « Dossier » à mettre au coffre », qui ne sonne que si le nombre augmente. Son action « Mettre au
  coffre » ouvre Focale (confirmation du système) et lance l'import sans déverrouiller le coffre ;
  un toucher ouvre le coffre. Écran verrouillé : ni dossier ni nombre. Réglage dans le menu du
  coffre ; permission de notification demandée en déplaçant un dossier dans le coffre. Sans
  album-dossier, rien n'est surveillé.
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
  `keepScreenOn` ; diffusé sur un écran externe ou une TV (§ 6.12).
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
  médias géolocalisés, seuils mesurés sur les photos de YFCC100M (`tools/trips/`). Lieux
  familiers : les cellules d'une grille de ~25 km photographiées pendant au moins 4 mois
  distincts, étalés sur 90 jours, dans les 12 mois autour du média (domicile, résidence
  secondaire, travail d'un frontalier, nouvelle adresse dès un déménagement ; un séjour, même
  long, tient en deux ou trois mois) ; chez soi : le plus familier. Un mois sans lieu familier
  reprend ceux du mois le plus proche. Un voyage enchaîne les jours avec un média à plus de
  60 km de tout lieu familier (deux jours sans média géolocalisé tolérés, une journée près d'un
  lieu familier l'interrompt), 5 médias lointains au moins et une nuit sur place, sauf au-delà
  de 150 km (un aller-retour dans la journée est une sortie) ; il apparaît quand son premier
  jour tombe dans la fenêtre. Requête : du premier au dernier média lointain, sans les photos
  de la maison du départ et du retour. Titre : la ville qui rassemble au moins la moitié des
  médias lointains (nom traduit), sinon le pays.
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
(cosinus ≥ 0,50, un visage par photo et par personne) ou crée une personne quand trois
visages en attente sur trois photos forment un groupe cohérent (voisins ≥ 0,50, chacun
proche du centroïde des autres). Seuils mesurés sur LFW (`tools/faces/`) : chaque visage
étant comparé à toutes les personnes, 0,42 laissait des inconnus rejoindre des personnes,
dont le centroïde dérivait alors vers le « visage moyen » (26 % de visages mal rattachés
dans une bibliothèque simulée de 150 personnes, 0,9 % à 0,50). Les rattachements manuels (`face.manual`) ne sont jamais
modifiés ; une personne sans nom n'est proposée qu'à partir de trois photos. Vignettes
rondes : `FaceThumbnailFetcher` Coil (découpe dans la miniature MediaStore, sinon décodage
réduit de la région utile de l'original). Recherche : `SearchTerm.personIds`. La télémétrie
d'ONNX Runtime est neutralisée (fournisseur retiré du manifeste, `setTelemetry(false)`) ; dans
les tests sur la JVM, où elle part dès la création de l'environnement, par `ORT_DISABLE_TELEMETRY`.

#### Liste des personnes
- **Tri** (réglage `peopleSort`) : plus de photos, nom (`Collator` primaire : ni casse ni
  accents, personnes sans nom à la fin) ou photo la plus récente. `FaceDao.observePeopleList`
  fournit l'épinglage et la date la plus récente, `PeopleRepository` trie ; les personnes
  épinglées (`person.pinned`, appui long) restent en tête, dans l'écran comme dans la rangée
  de l'onglet Albums.
- **« Est-ce la même personne ? »** (`MergeSuggestions`) : paires de personnes visibles dont
  les centroïdes (moyenne normalisée des empreintes de leurs visages) ont une similarité d'au
  moins 0,55 : au plus une fausse suggestion pour 5 000 paires de personnes distinctes, 99 %
  des personnes coupées en deux retrouvées (LFW). Moyenner les visages rapproche aussi deux
  inconnus (composante commune des empreintes) : 0,38 proposait 0,3 à 0,6 % des paires. Une carte en tête de l'écran Personnes les propose une à une, les plus
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

### 6.11 Sauvegarde des données
Section « Sauvegarde des données » des réglages (`DataBackupSection`, `DataBackupRepository`,
package `backup/`) : ce que l'utilisateur a construit dans Focale, pour changer de téléphone sans
rien perdre. `android:allowBackup="false"` et les règles d'extraction restent : rien ne part dans
la sauvegarde Google ni dans un transfert d'appareil, le coffre encore moins ; seule cette
sauvegarde, chiffrée par un mot de passe, emporte les données de l'application.

- **Contenu** : favoris ; albums virtuels (nom, date, couverture, contenu) ; personnes (nom,
  épinglée, masquée, vignette) avec leurs visages (cadre, empreinte SFace, rattachement, décision
  manuelle), le passage de la détection sur chaque photo (`face_scan`, même sans visage) et les
  refus de fusion ; dossiers masqués de Photos ; réglages de la liste blanche
  `SettingsRepository.BACKED_UP` (toute clé des réglages y est inscrite ou écartée dans
  `NOT_BACKED_UP`, ce que vérifie `SettingsBackupTest`) ; dates d'origine (`media_date`) ;
  historique de recherche. **Pas** : le coffre (sa propre sauvegarde), la corbeille (médias
  `is_trashed`, sur le point de disparaître), ce que l'analyse recalcule (étiquettes, texte,
  empreintes TinyCLIP, lieux, netteté, doublons), l'état propre à l'appareil (autorisations,
  application par défaut, mises à jour téléchargées, nouveautés vues, synchronisation : système ou
  `StateStore`, hors des réglages).
- **Format** (`DataArchive`, `focale-AAAA-MM-JJ.focale`, version 1) : en-tête en clair de
  `PasswordArchive`, commun avec le coffre (§ 6.6) mais de signature `FOCALE-DATA` (suivie d'un
  octet nul) et de domaine de clés `focale-data-backup` : un même mot de passe ne donne pas les
  mêmes clés aux deux formats, et chaque restauration refuse le fichier de l'autre. Le flux chiffré
  (Tink AES-256-GCM-HKDF, segments de 1 Mio authentifiés, dernier marqué) porte un contenu zlib :
  enregistrements typés (type sur 1 octet, longueur sur 4, JSON), puis une fin explicite. Les
  médias sont des enregistrements numérotés (dossier, nom, taille, date de tri), désignés par leur
  numéro ; albums, personnes et visages, par leur identifiant d'origine. Types et champs inconnus
  sont ignorés : une version ultérieure complète le format sans en changer la version.
- **En flux** : la sauvegarde lit l'index par tranches, dans l'ordre des clés primaires, sans
  transaction (ce qu'elle n'a pas vu est laissé de côté ou détaché : l'archive reste cohérente) ;
  la restauration ne garde en mémoire que les clés des médias et des lots de 500 visages. 50 000
  médias et 20 000 visages : archive de 12 Mio, sauvegardée en 2 s, copiée et vérifiée en 0,4 s, restaurée
  en 2,3 s, moins de 20 Mio de mémoire retenue (`DataBackupLoadTest`, [PERFORMANCE.md](PERFORMANCE.md) § 4.6).
- **Appariement des médias** (`MediaMatcher`) : les `_ID` changent d'un téléphone à l'autre. L'index
  est d'abord synchronisé avec MediaStore (`MediaSyncManager.syncNow`, attendue jusqu'au bout),
  puis chaque média de la sauvegarde est cherché par `relative_path` et `display_name` (sans casse
  ni différence de normalisation Unicode, en empreintes de 64 bits), départagés par la taille puis
  la date de prise de vue ; à défaut (dossier déplacé), par nom, taille et date. Une date décalée
  d'un fuseau horaire (multiple d'un quart d'heure, moins de 24 h) compte comme égale. Un média du
  téléphone ne sert qu'une fois ; ceux qui manquent sont comptés et annoncés au bilan.
- **Copie locale** (`DataBackupRepository.open`, `BackupCopies`) : le document choisi (souvent chez un
  fournisseur en ligne, lent à relire) n'est lu qu'une fois, dès qu'il est choisi, pendant que le
  mot de passe se tape : copié tel quel, donc toujours chiffré, dans le cache privé
  (`cache/restore/<uuid>.focale`), son en-tête vérifié aux premiers octets (un fichier étranger ou une
  sauvegarde du coffre est signalé aussitôt, sans être copié plus loin). Vérification (un mot de
  passe faux se réessaie sur la copie) et restauration lisent la copie ; elle est supprimée dès
  qu'elle ne sert plus (`StagedBackup.close`, avec `InspectedBackup` : bilan, échec, annulation,
  résumé ou dialogue refermé, écran quitté ; une copie écartée par une annulation tardive, à la
  copie suivante), et au démarrage celles qu'un processus arrêté aurait laissées (`AppInitializer`) ;
  une copie en service n'est jamais supprimée. La restauration du coffre (§ 6.6) lisait déjà son
  archive une seule fois (vérification, rechiffrement et fin authentifiée en un passage) : inchangée.
- **Restauration** (`DataRestore`) : la copie est d'abord vérifiée de bout en bout (mot de passe,
  intégrité, fin) et résumée (date, favoris, albums, personnes, dossiers masqués ; personnes du
  téléphone qui seront remplacées) ; confirmée, elle est relue et appliquée en une seule
  transaction, validée une fois sa fin vérifiée : annulée, tronquée ou modifiée entre-temps, rien ne
  change. Favoris réunis ; albums réunis par nom (contenu ajouté, couverture prise si l'album n'en a
  pas) ; dossiers masqués réunis (même `BUCKET_ID`, sinon même chemin) ; historique réuni, les
  personnes des recherches désignées par celles recréées ; dates d'origine reprises ; réglages de
  la liste remplacés. Personnes remplacées : sur chaque photo retrouvée, les visages de la
  sauvegarde remplacent ceux du téléphone ; ses personnes sont recréées avec leurs refus ; une
  personne du téléphone qui n'a plus de visage hors corbeille disparaît (ses visages de la
  corbeille, détachés, reviennent au regroupement). Les photos absentes de la sauvegarde gardent
  leurs visages. Restaurer deux fois donne le même résultat ; restaurer plus tard ajoute les médias
  arrivés entre-temps.
- **Restauration partielle** (`DataCategory`) : le résumé propose une case par catégorie présente
  dans la sauvegarde (favoris ; albums ; personnes, avec visages, passages de la détection et
  refus ; dossiers masqués ; réglages ; historique de recherche ; dates d'origine), toutes cochées
  d'abord, chacune avec ce que la restauration en fait ; la note sur les photos à copier ne
  vient que si une catégorie cochée désigne des médias, et « Restaurer » attend au moins une case.
  `DataRestore` passe sans les décoder les enregistrements des catégories écartées et ne touche
  à rien de ce qui les concerne (pas même aux personnes vides du téléphone si les personnes ne
  sont pas restaurées) ; index synchronisé, médias appariés et bibliothèque lue seulement si une
  catégorie cochée en a besoin. Chaque catégorie s'applique exactement comme dans une restauration
  complète, dans la même transaction unique ; une recherche de personne restaurée sans les
  personnes est retrouvée par son nom. Le bilan ne compte que les médias désignés par ce qui est
  restauré et n'annonce que les catégories choisies. La sauvegarde automatique, elle, emporte
  toujours tout.
- **Analyse des visages** : un lot de l'analyse et la restauration ne s'entrecroisent pas
  (`PeopleEditions.writes`) ; ensuite, le regroupement recharge son état. Une photo retrouvée n'est
  pas relue, sauf modifiée depuis son analyse. Sauvegarde d'une version antérieure de l'étape
  (`FaceAnalysisStep.VERSION`) : la photo est relue, ses visages retrouvés au même endroit gardent
  leur personne ; d'une version ultérieure (peut-être un autre modèle d'empreintes) : personnes et
  cadres gardés, empreintes nulles jusqu'à la relecture.
- **Sauvegarde automatique** (`DataAutoBackup`, `DataAutoBackupWorker`) : même logique que celle du
  coffre (`AutoBackupEngine`, `AutoBackupKeys`, `WorkAutoBackupSchedule` : chaque jour ou chaque
  semaine dans un dossier choisi, mot de passe non conservé, clés scellées par le Keystore, batterie
  et stockage non faibles, 3 dernières gardées, sautée si rien n'a changé, échec noté dans le
  réglage et notifié sans rien des données) ; réglage, clés, jeu de clés du Keystore, DataStore
  (`data_backup`) et fichiers (`focale-auto-AAAA-MM-JJ.focale`) propres : elle fonctionne sans
  coffre, et aucune des deux ne touche aux fichiers de l'autre.

### 6.12 Diffusion vers une TV ou un écran externe
Package `cast/` (logique), `ui/cast/` (bouton, feuille, présentation). Ni SDK Google Cast ni
bibliothèque tierce : API du système et protocoles ouverts, écrits dans l'application.
`CastManager` (singleton) réunit les appareils, la cible et le média en cours ; la visionneuse et
le diaporama y proposent leur média (`present`/`withdraw`, le temps de leur composition), et
seuls les médias de MediaStore sont diffusés (`CastMediaPreparer.isCastable`) : le coffre, dont
les éléments n'ont pas d'URI MediaStore, ne l'est jamais, ni servi ni présenté (ses écrans gardent
`FLAG_SECURE`, noirs sur un écran recopié).

- **Écran externe** (deux variantes) : `ExternalDisplays` suit `DisplayManager`
  (`DISPLAY_CATEGORY_PRESENTATION`, arrivée et départ) ; `PresentationDisplays` retient les écrans
  de présentation publics et allumés, hors écran de l'appareil (HDMI, USB-C, Miracast, « Caster
  l'écran »), et prend le premier que l'utilisateur n'a pas arrêté (« Arrêter » : retour à la
  recopie jusqu'au départ de l'écran). `CastPresentation` y ouvre une `Presentation` dont la
  `ComposeView` partage la composition de l'écran appelant (`setParentCompositionContext`), à la
  densité de l'écran externe : photo à sa taille (`ViewerPresentationContent`), vidéo du lecteur du
  téléphone (`ContentFrame`, le téléphone n'affichant que l'affiche et « Lecture sur … »), diaporama
  avec ses fondus et son zoom. Le téléphone garde pager, commandes vidéo et diaporama. En
  arrière-plan, la présentation est masquée (observateur du cycle de vie : la composition, elle,
  est suspendue) et la vidéo mise en pause ; elle reparaît au retour.
- **TV DLNA** (variante `standard`, `BuildConfig.TV_CAST_AVAILABLE`) :
  - réseau (`LocalNetworks`, `LinkAddresses`) : Wi-Fi ou Ethernet, même sous VPN ; l'IPv4 privée
    du téléphone, sinon (réseau IPv6 seul) ses IPv6 locales, unique locale (fc00::/7) d'abord puis
    de lien local, à laquelle l'interface du réseau est donnée (index de `LinkProperties.interfaceName`) ;
  - découverte (`DlnaDiscovery`) : M-SEARCH `urn:schemas-upnp-org:device:MediaRenderer:1` envoyé
    trois fois au groupe SSDP sous verrou multicast Wi-Fi (239.255.255.250, ou FF02::C de lien local
    depuis l'adresse de lien local quand le réseau n'a pas d'IPv4), sans `USER-AGENT`, réponses écoutées
    4 s (`Ssdp`), puis description de chaque appareil (`DeviceDescription` : lecteur racine ou
    imbriqué offrant AVTransport, `URLBase` ou adresse de la description, RenderingControl s'il
    existe). Automatique à l'ouverture de la visionneuse ou du diaporama, au plus une fois par
    minute (réglage « Rechercher les TV automatiquement »), sinon à la demande (menu « Diffuser sur
    une TV… », feuille) ; une recherche remplace la liste, sauf la TV en cours ;
  - commandes (`RendererClient`, `Soap`, `Didl`) : SetAVTransportURI avec métadonnées DIDL-Lite
    (profil `JPEG_LRG` pour une photo), Play (répété tant que la TV répond 701), Pause, Seek
    (`REL_TIME`), Stop, GetTransportInfo et GetPositionInfo, SetVolume/GetVolume. Les requêtes SOAP
    et les descriptions passent par `LanHttpClient`, client HTTP/1.1 minimal sur socket brute : la
    politique de sécurité réseau n'autorise pas le trafic en clair, et ce client n'accepte que des
    adresses privées (`LanAddresses` : 10/8, 172.16/12, 192.168/16, lien local, IPv6 locales) de la
    famille d'une adresse du téléphone, par le réseau Wi-Fi même sous VPN (`Network.socketFactory`).
    Une TV qui annonce un nom (« bravia.local », « tv-salon ») plutôt qu'une adresse : nom bien formé
    (RFC 1123) résolu par le système sur ce réseau (`Network.getAllByName` : DNS du réseau, mDNS des
    noms en « .local » quand Android le fait), 3 s au plus, résultat gardé une minute par client ;
    seule une adresse locale obtenue est contactée (IPv4 d'abord), sans seconde résolution, et un nom
    qui ne mène qu'ailleurs est refusé comme une adresse publique. La seule donnée qui sort alors
    est la requête DNS, adressée au résolveur du réseau Wi-Fi. Une IPv6 de lien local (écrite sans
    zone, ou avec celle de la TV, ignorée) reçoit l'interface du réseau ;
  - serveur local (`MediaServer`) : démarré avec la diffusion, lié à l'adresse Wi-Fi de la famille
    de celle de la TV (`LinkAddresses.serverFor` : IPv4, unique locale ou lien local ; pour un nom,
    l'adresse principale) sur un port
    tiré par le système, il ne sert que le média publié, sous `/<jeton>/media.<ext>` (128 bits de
    `SecureRandom`, neuf à chaque média, comparé en temps constant), GET et HEAD, une plage `Range`,
    en-têtes DLNA, 8 transferts au plus ; clients hors réseau local refusés (403) ; arrêté (socket
    et transferts coupés) à la fin. Photo décodée par `ImageDecoder` (orientation EXIF appliquée),
    réduite à 3 840 px et réencodée en JPEG (aucune métadonnée) ; vidéo servie telle quelle depuis
    MediaStore, lue à la position demandée ;
  - session (`TvSession`, une par diffusion, testée contre une TV factice en boucle locale) :
    média en cours envoyé à chaque changement (`collectLatest`), TV interrogée chaque seconde
    pendant une vidéo (toutes les 3 s pour une photo) ; trois échecs de suite : « Connexion
    perdue », diffusion arrêtée. La page vidéo prend un `RemotePlayer` (`SimpleBasePlayer` qui
    reflète la lecture de la TV) : lecture, pause, curseur et sauts de 10 s inchangés, position
    passée du lecteur local à la TV et retour (`PlaybackMemory`, relevée sur le lecteur remplacé
    au moment même de la relève). Écran quitté : TV arrêtée au bout de 3 s (le temps d'ouvrir le
    diaporama), diffusion close après 5 min sans média ;
  - repli sur le téléphone : une vidéo refusée (erreur UPnP, état `ERROR_OCCURRED`, jamais lue en
    10 s), impossible à préparer ou à envoyer revient au téléphone (`TvSession.phoneVideos`,
    `CastManager.phoneVideos`) : la TV est arrêtée (elle ne garde pas le média précédent), la page
    reprend le lecteur du téléphone à la position demandée à la TV (`RemotePlayer` garde position
    et lecture quand la TV lâche la vidéo), avec « La TV ne lit pas ce format de vidéo : lecture
    sur le téléphone » ; la diffusion continue pour les médias suivants, et une vidéo refusée n'est
    plus proposée à la TV de toute la diffusion (un échec d'envoi passager sera retenté). TV perdue
    en pleine vidéo : la diffusion s'arrête et la vidéo reprend sur le téléphone à la dernière
    position mesurée sur la TV (« Connexion à … perdue : la vidéo reprend sur le téléphone ») ;
  - arrière-plan : `CastService`, service au premier plan `connectedDevice` démarré depuis
    l'écran, garde le processus actif tant qu'une TV est servie, avec la notification « Diffusion
    sur … » et « Arrêter » ; fermer Focale depuis les applications récentes arrête la diffusion. Le
    diaporama continue d'avancer sur la TV écran éteint (sa boucle présente elle-même la photo
    suivante, la composition étant suspendue) ;
  - touches de volume (`TvVolumeKeys`, `TvRemoteVolume`) : dès que le volume d'une TV qui a
    RenderingControl est connu, une session média du framework en lecture distante
    (`MediaSession.setPlaybackToRemote`, fournisseur de volume absolu de 0 à 50, pas de 2 sur
    l'échelle de 0 à 100 de la TV ; état « en lecture » sans aucune action, que les boutons d'un
    casque ne commandent pas) est confiée à `MainActivity` (`Activity.setMediaController`) : le
    système lui passe les touches de volume (et les montre sur le curseur du volume du système), qui
    deviennent des SetVolume, regroupés comme ceux du curseur de la feuille (150 ms). Hors de Focale,
    la session active les reçoit quand le système le permet (selon l'appareil). Le volume de la TV
    est relu toutes les 10 s (changé à la télécommande, la touche suivante part de lui), sauf dans
    les 3 s qui suivent une demande. Sans RenderingControl, ou la diffusion finie : plus de session,
    les touches règlent le téléphone.
- **Interface** : bouton « Diffuser » dans la barre de la visionneuse et du diaporama, visible
  seulement si un écran externe ou une TV est là, pendant une recherche ou une diffusion
  (`CastState.available`) ; feuille (`CastSheet`) : diffusion en cours avec « Arrêter » et volume,
  écrans externes, TV et état de leur recherche (aucune, Wi-Fi absent, accès au réseau local à
  autoriser sur Android 17+).

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
├── Collage(mediaIds, truncated)
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
s'empile sur l'écran courant (`openOnTop`), sauf sur un écran du même type, remplacé : la
visionneuse ne lit sa photo dans sa route qu'à la création de son ViewModel, que
`launchSingleTop` aurait gardé avec l'ancienne photo. `REVIEW_SECURE` n'est pas déclarée : l'activité unique, qui donne
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
  `insertSeparators` (jour / mois / année, ou automatique selon le zoom). Pages lues dans
  l'ordre d'un index (§ 5.1), sans tri : quelques millisecondes par page de 120 médias sur
  50 000, même tout au bout (mesures : [PERFORMANCE.md](PERFORMANCE.md)).
- Pincement : 2 à 7 colonnes (décalées sur tablette), ancrage sur l'élément visé.
- Défilement rapide : poignée + bulle de date calculée depuis un histogramme
  mensuel — exact et sans charger les pages : mois bornés en Kotlin dans le fuseau courant
  (`DateGrouping.bounds`, heure d'été comprise, mêmes périodes que les en-têtes), comptés par
  plages de dates dans l'index. La position dans la grille au retour de la visionneuse compte
  de même les périodes non vides avant le média.
- Sélection : appui long puis glissement (calcul de l'élément sous le doigt +
  défilement automatique en bord d'écran), retour haptique à chaque ajout.

### 7.4 Visionneuse
- `HorizontalPager` sur la `PagingSource` de la grille ; photos zoomables par telephoto
  (sous-échantillonnage des grandes images), glisser vers le bas pour fermer.
- Vidéos (galerie, fichiers d'autres applications, coffre : `VideoPlayerPage`) : un appui
  affiche ou masque les commandes une fois le délai du double appui écoulé, comme sur une
  photo ; un double appui sur la moitié droite avance de 10 s, sur la gauche recule d'autant
  (côtés physiques, même en écriture de droite à gauche), puis chaque appui qui suit le
  précédent de moins de 700 ms saute aussitôt (+20, +30 s… ; de l'autre côté, le cumul
  repart de 10 s). Position bornée à [0, durée], un appui contre une borne n'ajoute rien
  (`VideoSeekMath`, `SeekSeries`, logique pure). Détecteur maison (`videoTapGestures`) :
  seuil de déplacement, un seul doigt, appuis déjà pris par les commandes (boutons,
  curseur) ignorés, glissements laissés au pager et au glisser pour fermer. Cumul dans un
  demi-ovale translucide du côté touché, hors des barres système et des commandes
  (`ViewerChromeMetrics`) ; mêmes sauts en actions TalkBack sur la surface vidéo.
- Vitesse de lecture (`VideoSpeedButton`) : bouton « 1× », « 1,5× »… à l'autre bout du
  curseur que le son, teinté hors de 1× ; menu Material 3 Expressive de 0,25× à 2× par
  quarts (`VideoSpeeds`, logique pure ; virgule décimale en français) au-dessus du bouton,
  ou, en paysage, calé sur son bas sans couvrir la barre d'outils, défilant jusqu'à la
  vitesse en cours. `Player.setPlaybackSpeed` : Media3 ne change que le tempo, la hauteur
  du son reste celle de la vidéo. Vitesse retenue par l'écran de la visionneuse
  (`rememberVideoSpeed`, comme l'affichage des commandes) : la même pour les vidéos
  suivantes, gardée après une rotation ou une recréation de l'activité, oubliée à la sortie
  (1× à la prochaine ouverture) ; les sauts restent de 10 s de vidéo. Progression jamais
  plus étroite que la barre d'outils de la galerie : sous une barre plus courte (fichier
  d'une autre application, coffre), le curseur garde sa place. TalkBack : « Vitesse de
  lecture : 1,5× », relu à chaque choix ; options en cases d'option, celle en cours cochée,
  sous le titre du menu.
- Rotation à deux doigts : geste observé à la passe initiale sans rien consommer (zoom,
  balayage, double appui restent à telephoto), rotations parasites d'un pincement ignorées
  sous 15° (`RotationMath`). Au relâchement, ressort vers le quart de tour le plus proche,
  puis cadre aux dimensions de l'écran échangées pour que zoom et bords restent justes.
  Barre « Enregistrer la rotation » / « Annuler » ; changer de photo ou quitter annule.
- Enregistrement (`PhotoRotator`) : JPEG et WebP sans perte, orientation EXIF composée avec
  l'actuelle, miroirs compris (`ExifOrientation`) ; deux octets réécrits sur place si la
  balise existe (carte de gain Ultra HDR, vidéo de photo animée intactes), sinon segment
  EXIF refait ; consentement de « remplacer l'original ». L'orientation XMP
  (`tiff:Orientation`, que lit Lightroom) du paquet de l'image principale (APP1 du JPEG,
  bloc « XMP » du WebP ; attribut ou élément, préfixe quelconque) prend la même valeur : un
  chiffre remplacé à longueur égale (`XmpOrientationSlot`), donc aucun décalage MPF ; le XMP
  de la carte de gain n'est pas lu, une orientation XMP absente n'est pas ajoutée.
- HEIF / HEIC et AVIF (`HeifRotation`, même conteneur ISOBMFF) : Android y suit les
  propriétés `irot` / `imir` de l'image, pas l'EXIF. La boîte `meta` est lue entière (quelques
  Ko : `hdlr`, `pitm`, `iinf`, `iref`, `iloc`, `iprp/ipco/ipma`), puis chaque image à tourner
  (l'image principale, grille comprise, et ses miniatures `thmb`) reçoit l'angle qui compose sa
  transformation actuelle avec le quart de tour (miroirs compris : `imir` mode 0 haut-bas, 1
  gauche-droite, comme libheif et libavif). Propriété `irot` existante, qui ne sert qu'à ces
  images ou à leurs images auxiliaires (alpha, profondeur, gainmap Apple) : un octet réécrit sur
  place (`operations.patch`). Absente, ou partagée avec une autre image (une tuile) : une
  propriété de 9 octets est ajoutée à la fin de `ipco` et associée en dernier (appliquée après
  `clap` et `imir`, marquée essentielle) ; `meta` grandit, les tailles des boîtes qui la
  contiennent et les positions `iloc` des données qui la suivent sont décalées, et le fichier
  est recopié depuis l'original dans un fichier temporaire, relu (`HeifRotation.verify` :
  orientation de chaque image tournée, longueur, traîne et morceaux de la vidéo à leur place),
  puis remplace l'original (`operations.overwrite`, avec copie de secours). Orientation EXIF de
  l'élément `Exif` et `tiff:Orientation` du XMP (`HeifXmp`, `XmpOrientation`) mis à la valeur
  affichée, sur place : MediaStore les lit (ExifInterface).
  - Ce qui suit la boîte `meta` se décale d'un seul tenant : `mdat`, `free`, `skip`, `mpvd`
    (vidéo d'une photo animée Samsung, fichier MP4 complet aux positions relatives) ; traîne
    Samsung (`SamsungTrailer` : blocs « Image_UTC_Data », « MCC_Data », « MotionPhoto_Data »…,
    répertoire `SEFH`, longueur et `SEFT` à la fin, chaque bloc désigné par sa distance avant le
    répertoire), reconnue seulement si chaque entrée désigne un bloc de la traîne qui commence par
    le même type et si les boîtes finissent exactement où elle commence ; `moov` d'une photo
    animée ou d'une séquence d'images (`MovieChunks`), gardée telle quelle (vidéo ni réencodée ni
    tournée) sauf ses positions `stco` / `co64` des morceaux rangés après `meta`, décalées comme
    celles de `iloc` ; chaque morceau doit tomber dans un `mdat` du fichier, chaque référence de
    données être dans le fichier (`dref` autonome), et rien dans `moov` ne doit désigner de position
    absolue que le décalage ne suivrait pas (`saio`, `iloc`) ni annoncer de fragments (`mvex`, `moof`).
  - Carte de gain ISO 21496-1 (`tmap`, HDR des iPhone sous iOS 18 et d'Android 16) : l'image
    `tmap` (dérivée de l'image principale et de l'image de gain, `dimg`), que les lecteurs HDR
    montrent à la place de l'image principale (groupe `altr`), tourne avec elle (ou, si l'image
    `tmap` est l'image principale, son image de base) ; l'image de gain aussi quand elle porte la
    même orientation que l'image de base (elle doit lui rester superposée), et reste telle quelle
    quand elle n'en porte aucune (orientation portée par l'image dérivée, comme l'écrit libavif).
  - Toute structure inattendue renonce et garde le réencodage d'avant : rotation suivie d'un
    `clap`, `irot` en double, `tmap` sans ses deux images, dérivée d'une autre image ou dont
    l'image de gain est orientée autrement que l'image de base, et, pour une `meta` qui grandit,
    boîte de premier niveau autre que celles ci-dessus, seconde `ftyp` (fichier ajouté à la suite,
    aux positions relatives à son début), deux `moov`, données après la dernière boîte qui ne sont
    pas une traîne Samsung reconnue, donnée `iloc` dans la boîte ou position qui ne tient plus
    dans son champ. Le résultat est revérifié avant écriture (affichage voulu de chaque image,
    mêmes données aux positions décalées). Les options de la barre (`PhotoRotator.options`,
    suspendue) lisent cet en-tête en arrière-plan : « Enregistrer la rotation » attend leur
    réponse. Fichiers de test réels (libheif / x265 et FFmpeg / libaom, libx265) produits par
    `tools/heif/make_fixtures.py`, dont les structures des Galaxy (traîne avec la vidéo de la
    photo animée), d'une photo animée à `moov` et des cartes de gain des iPhone, reproduites
    autour d'un HEIC de libheif ; chaque fichier tourné se relit avec libheif (orientation de
    l'image) et FFmpeg (mêmes images de la vidéo).
- Autres formats (PNG, dont Android ignore l'orientation EXIF ; HEIF et AVIF à la structure
  inattendue) : rendu de l'éditeur, en copie ou à la place (jamais un JPEG à la place d'un
  HEIF). Clé mémoire Coil des images pleine taille liée à `date_modified`.
- Photos panoramiques (`panorama/`) : détection à l'ouverture dans la visionneuse
  (`PanoramaRepository`), sans analyse en arrière-plan ni colonne dans la base. Propriétés
  GPano lues dans l'en-tête seul (`PanoramaHeaders`) : paquet XMP principal d'un JPEG (APP1)
  ou d'un WebP (bloc « XMP »), comme l'orientation XMP (`XmpPackets`), ou élément `mime`
  `application/rdf+xml` d'un HEIF / AVIF (`HeifXmp` : boîtes `meta`, `iinf`, `iloc`, données
  dans le fichier ou dans `idat`). Lecture SAX tolérante (`XmpPanorama`, attributs ou
  éléments, préfixe quelconque, ni DTD ni entité externe) ; résultat, absence comprise, gardé
  en mémoire par média et date de modification (`Panoramas`, LRU de 1 024). Choix de la vue
  (`PanoramaRules`, logique pure) : sphère si le GPano est équirectangulaire, sans
  `UsePanoramaViewer="False"`, et couvre au moins 90° de haut (zone tirée de
  `CroppedArea*` / `FullPano*`, proportions seules ; sans tailles, sphère complète au format
  2:1) ; à plat pour un autre GPano sur une image plus large que haute (panorama horizontal
  partiel d'un Pixel, projection cylindrique) ou, sans GPano, à partir de 2,5:1 (couverture
  estimée à 60° de haut) ; GPano aux proportions différentes de l'image de plus de 5 %
  (photo recadrée) ignoré. Bouton « 360° » ou « Vue panoramique » au-dessus de la barre
  d'outils ; la vue s'ouvre par-dessus la visionneuse, intacte dessous (zoom compris), et
  partage l'affichage de ses commandes ; ouverte, elle survit à la recréation de l'activité.
- Vue 360° (`SphereViewer`, `SphereRenderer`) : `GLSurfaceView` OpenGL ES 2, rendu à la
  demande ; maillage de la seule zone couverte (`SphereMesh`, 96 × 48 segments pour la sphère
  entière, texture étirée d'un coin à l'autre, fond noir ailleurs). Caméra au centre
  (`SphereCamera`, Kotlin pur, matrices maison `Mat4`) : glisser fait suivre le doigt au
  centre de l'écran, pincer règle le champ (30 à 100° le long du petit côté, 70° par défaut ou
  `InitialHorizontalFOVDegrees`), le centre de la vue reste dans une zone partielle ; « Vue
  initiale » revient au cap `InitialViewHeadingDegrees` lu sur la boussole du fichier (centre
  du panorama à `PoseHeadingDegrees`, 0 à défaut), sinon au centre de la zone. « Bouger le
  téléphone » (désactivé par défaut, absent sans capteur) : vecteur de rotation « jeu », à
  défaut ordinaire (`MotionSensor`), axes de l'écran selon `Display.getRotation()`, cap décalé
  pour reprendre la vue en cours ; le doigt ne change plus que le cap. Texture : puissances de
  deux (mipmaps), plafonnées à `GL_MAX_TEXTURE_SIZE` et 8 192, côtés équilibrés, 8, 16 ou
  32 Mpx selon la mémoire (`SphereTextureSize`) ; décodée hors du fil principal par bandes de
  128 lignes (`BitmapRegionDecoder` sous-échantillonné, une bande de l'original en mémoire à
  la fois ; `ImageDecoder` si l'orientation EXIF tourne la photo), envoyée au contexte puis
  libérée ; texture et contexte disparaissent avec la vue, rechargés si le contexte est perdu.
- Horizon redressé (`SpherePose`) : `PosePitchDegrees` (centre de l'image au-dessus de
  l'horizon réel) et `PoseRollDegrees` (l'horizon tourne d'autant, dans le sens inverse des
  aiguilles d'une montre, dans l'image) font tourner la sphère, comme Photo Sphere Viewer et
  Pannellum : roulis autour de l'axe du centre de l'image, puis inclinaison (matrice du modèle,
  `SphereRenderer`, `VideoSphereRenderer`). Glisser, « Bouger le téléphone » et vue initiale
  sont dans le repère redressé : le capteur garde l'horizon à l'horizontale. Le cap
  (`PoseHeadingDegrees`) ne fait que déplacer la vue initiale. Panoramas à plat : pas redressés.
  Sphère partielle inclinée : sa zone est bornée dans le repère de l'image (approximation).
- Cap initial (`PanoramaRules.initialView`), vérifié sur des métadonnées réelles : la
  spécification GPano compte `InitialViewHeadingDegrees` « depuis le nord réel, pas depuis le
  centre du panorama », et `PoseHeadingDegrees` est le cap du centre : la vue initiale est à
  leur différence (exemple Photo Sphere de Google, 350 et 90 : 100° à droite du centre) ; c'est
  la lecture de Photo Sphere Viewer, Pannellum comptant au contraire depuis le centre. RICOH
  THETA Z1 : `PoseHeadingDegrees` (boussole, 68,8 dans l'exemple publié par Trek View), pose
  nulle si l'appareil a redressé l'image (l'API RICOH dit d'utiliser `PosePitchDegrees` /
  `PoseRollDegrees` sinon), pas de vue initiale : on part du centre (objectif avant). GoPro MAX
  et Samsung Gear 360 (exemples réels de Trek View et de PhotoPrism) : pose et vue initiale à 0.
  Pas d'exemple Insta360 ni Google Camera récent vérifié : à contrôler sur appareil.
- Vidéos 360° (`SphericalVideos`, `SphericalVideoHeaders`, `SphericalVideoRules`) : détection à
  l'ouverture comme pour les photos (même `PanoramaRepository`, résultat en mémoire par média et
  date de modification, LRU de 512 entrées, un maillage comptant pour une entrée par 16 Ko, rien
  dans la base), pour les MP4, MOV et 3GP. Seuls les en-têtes des boîtes menant à la piste vidéo
  (`hdlr` `vide`) sont lus, `moov` au début ou à la fin, jamais les données : V2 (`st3d` et
  `sv3d` → `proj` → `prhd`, `equi` / `cbmp` / `mshp` dans l'entrée `stsd`, dont la largeur et la
  hauteur), sinon V1 (boîte `uuid` `ffcc8263-…` de la piste ou de `moov`, XML GSpherical lu par
  `XmpPanorama.properties`). Fichier mal formé (taille trop petite, qui déborde, fichier
  tronqué) : rien, sans erreur. Stéréoscopie `st3d` : mono, haut-bas, gauche-droite,
  droite-gauche, ou propre au fichier (décrite par les maillages seulement) ; seule l'image de
  l'œil gauche est montrée (`VideoFrame`). Proposée en 360° : projection équirectangulaire aux
  bornes cohérentes (`equi`, ou recadrage GSpherical façon GPano), sphère complète ou partielle ;
  cube `cbmp` de disposition 0 (la seule de la RFC, que FFmpeg seul accepte aussi) dont la marge
  laisse quelque chose de chaque face ; maillage `mshp` lisible. Projection inconnue, autre
  disposition de cube, maillage illisible : pas proposés. Pose de `prhd` lue comme FFmpeg
  (`spherical.h`) : cap positif, sphère tournée vers la droite (vue initiale à gauche du centre) ;
  inclinaison et roulis redressent l'horizon comme ceux d'une photo. VLC lit au contraire le cap
  comme la direction de la vue initiale.
- Cube (`CubeMesh`) : six faces planes de deux triangles (la perspective d'OpenGL est exacte sur
  un plan), dans l'ordre de la RFC (droite, gauche, haut / bas, avant, arrière). Orientation des
  faces du haut et du bas : celle qu'écrit FFmpeg (`v360`, `c3x2`) et que lit VLC (haut de la
  face du haut vers l'arrière, de la face du bas vers l'avant), l'inverse de la phrase de la RFC.
  Marge de `cbmp` (pixels autour de chaque face de l'image d'un œil, dimensions de l'entrée
  vidéo) hors du rendu ; sans marge, la face s'arrête au centre des pixels du bord, le filtrage
  ne prend rien à la face voisine de l'image.
- Maillage (`MeshProjection`) : `mshp` (ou `ytmp` des premiers fichiers de YouTube) lu en entier
  (1 Mo au plus), `raw ` ou `dfl8` (deflate brut, 8 Mo décompressés au plus), première boîte
  `mesh` (œil gauche) ; coordonnées distinctes, sommets en index zigzag de ⌈log₂(2n)⌉ bits,
  listes en triangles, bande ou éventail dépliées en triangles, listes d'une image fixe (texture
  autre que 0) ignorées ; 65 535 sommets au plus (index de 16 bits d'OpenGL ES 2). Positions
  ramenées au rayon le plus grand (image inchangée, entre les plans de coupe), coordonnées de
  texture d'OpenGL retournées (v = 0 en haut). CRC non vérifié, comme ExoPlayer. C'est ainsi que
  YouTube décrit ses cubes équi-angulaires (EAC, marges comprises) : la RFC ne définit pour
  `cbmp` que la disposition 0, sans variante équi-angulaire.
- Lecture d'une vidéo 360° : bouton « 360° » au-dessus des commandes de la vidéo (pas pendant une
  diffusion, qu'il referme) ; la vue s'ouvre dans la page même (`rememberSphereVideo`, cadre et
  habillage passés à `VideoPlayerPage`) : le lecteur de la page dessine dans une texture externe
  (`SurfaceTexture`, `GL_TEXTURE_EXTERNAL_OES`, `VideoTarget`) au lieu de son cadre, sans être
  recréé (position, vitesse, son gardés), sur la zone de sphère, le cube ou le maillage
  (`Panorama.sphereMesh`, `VideoSphereRenderer`, `SphereBuffers`, `SphereCamera`). Commandes vidéo
  habituelles (lecture, curseur, vitesse, son, double appui ±10 s), barre « Bouger le
  téléphone » / « Vue initiale » à la place de la barre d'outils, pager figé (glisser regarde
  autour). Fichiers de test réels (FFmpeg / libx264, métadonnées injectées comme le fait Spatial
  Media, ou écrites par FFmpeg, vérifiées par ffprobe ; images de cubes `c3x2` et `eac` du
  filtre `v360`, dont chaque pixel a pour couleur sa direction : la géométrie se vérifie point
  par point) produits par `tools/spherical/make_fixtures.py`.
- Autres sources : fichier reçu d'une autre application et hors index (`StandaloneViewer`,
  `ExternalMediaViewModel.panorama`, `PanoramaRepository.find(ExternalMedia)`) : en-têtes lus par
  accès aléatoire au descripteur de fichier (un fournisseur qui n'en donne pas ne propose rien),
  dimensions et orientation dans l'en-tête de la photo (`PhotoHeader`) ; mêmes boutons et vues
  que la galerie, photo par son URI (`UriPanoramaPhoto`). Coffre (`VaultViewerPages`,
  `VaultViewerViewModel.panorama`, `VaultPanoramas`) : en-têtes lus dans le fichier chiffré par le
  canal déchiffrant à accès aléatoire de Tink (celui de la lecture vidéo), dimensions par un flux
  déchiffrant ; texture décodée du flux déchiffré, en mémoire (`PhotoStreams` : le décodeur par
  régions garde l'image compressée en mémoire, `ImageDecoder` lit un `ByteBuffer`), panorama à
  plat décodé par Coil en mémoire au plus à 4 096 px de long, donné tout fait à telephoto (qui
  demanderait sinon l'écriture d'un cache disque pour découper l'image) ; vidéo lue par
  `EncryptedFileDataSource`. Jamais de copie en clair sur le disque, résultat gardé avec la
  visionneuse seulement ; surfaces OpenGL `setSecure(true)` en plus de `FLAG_SECURE` de la
  fenêtre.
- Vue panoramique (`FlatPanoramaViewer`) : telephoto en `ContentScale.FillHeight`, image à la
  hauteur de l'écran, centrée, parcourue au doigt avec l'inertie et le sous-échantillonnage de
  la visionneuse ; « Bouger le téléphone » fait défiler d'un degré du panorama par degré
  tourné (`HeadingTracker`, `panoramaShift`, couverture GPano ou estimée) ; « Vue initiale »
  revient au zoom de base et recentre. Hors de cette vue, le zoom de la visionneuse ne change
  pas.
- Impression (`print/`) : « Imprimer » dans le menu de la visionneuse (photos) et de la
  sélection (`MediaActions.print` : vidéos ignorées, message si la sélection n'en contient que),
  rien pour le coffre (le contenu déchiffré partirait vers le spouleur). `PhotoPrinter` ouvre
  l'aperçu d'Android (`PrintManager`) ; `PhotoPrintAdapter` écrit un PDF (`PdfDocument`), une
  page par photo dans le sens de la photo (`PrintLayout` : page à l'italienne pour une photo
  plus large que haute, photo entière et centrée dans la page moins la plus grande marge
  minimale de l'imprimante), photo décodée à la résolution de l'imprimante (300 ppp au plus,
  jamais plus grande que l'original, 24 Mpx au plus) par `ImageDecoder` (orientation EXIF
  appliquée), désaturée en noir et blanc, libérée avant la suivante ; hors du fil principal,
  arrêtée à l'annulation, pages demandées seules écrites (`PrintPages`).

### 7.5 Éditeur
- `EditState` appliqué dans l'ordre : quarts de tour, miroir, redressement, recadrage,
  couleurs (mêmes matrices pour l'aperçu et l'export), zones floutées, dessin, textes.
  L'aperçu tourné est rendu en arrière-plan ; le redressement est appliqué à l'affichage,
  instantané. Historique (`EditHistory`, 50 étapes) partagé par tous les outils : une action
  ponctuelle forme une étape, un geste (glisser, pincer, curseur) une seule, créée à son début.
  Une sélection (texte, zone) appartient à son outil et disparaît avec l'élément.
- « Tout annuler » (bouton de la barre du haut, entre le titre et annuler) : `resetAll` rend
  l'original (recadrage et format, rotation, miroir, redressement, filtre, réglages, traits,
  textes, zones floutées) en une étape, qu'« Annuler » défait et « Rétablir » refait ; une
  saisie de texte en cours est d'abord validée, elle revient donc avec le reste. Actif dès une
  modification (`EditState.changeCount` : géométrie, filtre et chaque réglage pour une, chaque
  trait, texte et zone aussi) ; à partir de 5, une confirmation légère (`ResetAllDialog` : le
  nombre de modifications, et qu'« Annuler » les rétablit), en deçà il agit aussitôt. Le titre se
  réduit au besoin (`TextAutoSize`) pour que les quatre boutons et « Enregistrer » tiennent.
- Redressement à angle libre (±45°, au dixième de degré) : règle graduée (retour haptique à
  chaque degré, marqué à 0° ; double appui ou valeur affichée pour revenir à 0°) ou rotation
  à deux doigts sur l'aperçu en mode recadrage, rotations parasites d'un pincement ignorées
  (seuil de 5°, doigts d'abord écartés ou rapprochés). Grille fine pendant le geste.
- Géométrie pure (`StraightenMath`) : l'image pivote dans son cadre, le recadrage reste
  normalisé dans ce cadre et doit tenir dans l'image tournée. Recadrage automatique : plus
  grand rectangle de la proportion voulue (format imposé compris), en forme close (la zone
  permise est symétrique : un rectangle qui y tient quelque part y tient centré), rapproché
  du centre seulement si besoin ; il retrouve sa taille en revenant vers 0°. Les poignées
  s'arrêtent au bord de l'image tournée. Quart de tour : même angle ; miroir : angle opposé.
- Export (`EditRenderer`, `ImageEditor`) : même rotation autour du centre du cadre, image
  agrandie de 3 px (`bleed`) pour qu'aucun coin ne soit à demi transparent. Les traits
  restent alignés sur le cadre : une forme tracée sur la photo redressée reste droite.
- Texte (`TextItem`, `TextController`, `TextOverlay`, `TextPanel`) : un appui hors des textes
  ouvre la saisie d'un nouveau texte à cet endroit (plusieurs lignes ; vide, il n'est pas
  ajouté), un appui sur un texte la sienne (vidé, il est supprimé), un glisser le déplace, un
  pincement l'agrandit et le fait tourner ; corbeille du panneau.
  Couleur (palette du dessin), taille (curseur en racine carrée), fond (aucun, encadré :
  pavé de la couleur et lettres noires ou blanches selon le contraste, contour), gras ; un
  réglage changé vaut pour le texte sélectionné et les suivants.
- Saisie en place (`TextInputBar`, `TextDraft`) : la saisie remplace la rangée d'outils, au-dessus
  du clavier (`imePadding` : la photo rapetisse mais reste visible), le panneau Texte au-dessus.
  Le texte en cours (`TextDraft.preview`) s'affiche sur la photo à chaque frappe
  (`TextController.displayed` : à la place du texte modifié, ou par-dessus pour un nouveau ;
  encore vide, son cadre de sélection fait office de curseur), avec les réglages que le panneau
  change en direct ; il se déplace et se pince aussi. Rien n'entre dans l'historique avant la
  validation (bouton, appui ailleurs sur la photo, retour, changement d'outil, enregistrement),
  qui forme une seule étape, réglages compris ; la croix ou « Annuler » l'abandonnent, la
  corbeille retire le texte. L'identité d'un nouveau texte est réservée à l'ouverture de la
  saisie. Centre normalisé dans le
  cadre comme les traits, corps en fraction du petit côté, angle propre : un quart de tour le
  tourne avec l'image, un miroir le déplace et inverse son inclinaison sans retourner ses
  lettres, le redressement fait pivoter la photo sous lui.
- Rendu des textes (`TextRenderer`, `TextMath`) : Google Sans Flex de l'application (axes
  `wght` 500 ou 800, `ROND` 100), lignes centrées composées une fois au corps de référence
  (100 px, `StaticLayout` sans coupure automatique) puis mises à l'échelle : l'aperçu (canevas
  natif, mises en page en cache tant que texte, couleur, fond et graisse ne changent pas) et
  l'export pleine résolution ont la même mise en page.
- Flou (`BlurRegion`, `BlurController`, `BlurOverlay`, `BlurPanel`) : rectangle ou ellipse du
  point de départ au doigt, ou trait de pinceau (épaisseur en dp à l'écran convertie en
  fraction de l'image) ; un appui sélectionne une zone ; avec le rectangle et l'ellipse, un glisser
  parti de la zone sélectionnée la déplace (une zone nouvelle n'est pas sélectionnée : un second
  trait peut partir d'elle), alors qu'avec le pinceau un glisser peint toujours, même depuis la zone
  sélectionnée. Avec tous les outils, un appui long sur une zone (doigt immobile jusqu'au délai du
  système, retour haptique, comme l'échange des photos d'un collage) la sélectionne, et le glisser
  qui suit la déplace, en une étape d'historique (aucune si le doigt ne bouge pas) ; style « Flou »
  ou « Pixels » et intensité pour la zone sélectionnée et les
  suivantes. Les zones sont attachées à la photo (`BlurArea` : repère du cadre avant
  redressement, inclinaison propre) : contrairement aux traits, elles pivotent avec le
  redressement, et ce qu'elles cachent reste caché à tout angle ; un cadre tracé sur une photo
  redressée est droit à l'écran (`PhotoPlacement`).
- Irréversibilité (`BlurMath`, `BlurRenderer`) : la zone est découpée en blocs égaux sur sa
  boîte (entre 8 blocs dans son petit côté à l'intensité minimale et 3 à la maximale, jamais
  sous 0,6 % du petit côté de l'image ni 3 px) ; seule la moyenne de chaque bloc est gardée,
  affichée telle quelle (mosaïque) ou interpolée puis floutée sur une grille quatre fois plus
  fine (flou) : rien de plus fin qu'un bloc ne subsiste. La taille des blocs suit celle de la
  zone : un visage, petit ou grand, ne compte jamais plus de 8 blocs de large. Tracé sans
  lissage ni fondu, en mode `SRC` (opacité comprise) : un pixel dont le centre est dans la zone
  est entièrement remplacé, les autres restent intacts. Filtre et réglages de la photo
  appliqués aux zones ; traits et textes par-dessus, nets.
- Mémoire : la source n'est lue que dans la boîte de chaque zone, par bandes de 64 lignes,
  vers des sommes par bloc ; la grille des blocs (quelques dizaines, seize fois plus de cellules
  pour le flou lissé) est étirée par un `BitmapShader` : aucune copie de l'image entière, même
  pour une zone qui la couvre (photo de
  50 Mpx décodée à 24 Mpx au plus, comme avant). L'aperçu calcule ses zones en arrière-plan
  sur l'image réduite, seulement les zones nouvelles ou modifiées ; une zone qu'on déplace
  garde sa grille, qui la suit, jusqu'au calcul suivant.
- « Visages » (`FaceLocator`) : détection ML Kit seule (modèle embarqué, sans repères ni filtre
  de qualité, visages dès 2 % de la largeur) sur l'aperçu de l'original ; une ellipse par
  visage (cadre agrandi de 40 %) que ne couvre pas déjà une zone, en une étape d'historique.
- Accessibilité : outils, formes, styles, fonds, gras et curseurs nommés ; chaque texte et
  chaque zone est un élément TalkBack placé sur l'aperçu (activer : modifier le texte ou
  sélectionner la zone ; action « Supprimer »), atteignable au clavier (Entrée, Suppr).

### 7.6 Options des albums
- Appui long sur la carte d'un album de l'application ou d'un dossier (onglet Albums) : feuille
  d'options (`OptionsSheet`) avec en tête la couverture, le nom et le nombre d'éléments, et le
  retour haptique de l'appui long (`combinedClickable`) ; l'appui simple ouvre l'album. Le
  diaporama (album non vide), puis les options du menu de l'album ouvert, même liste et mêmes
  conditions (`albumOptions`) : renommer, masquer dans Photos ou y afficher, déplacer dans le
  coffre (album non vide), supprimer. Mêmes dialogues (`RenameAlbumDialog`, `DeleteAlbumDialog`)
  et même logique (`AlbumActions`, propre à chaque ViewModel) ; le passage au coffre suit le flux
  de `MediaActions` (confirmation, progression, permission de notification, bilan), dont le
  bilan s'affiche en snackbar quand aucun écran ne se ferme. Une option à dialogue l'ouvre une
  fois la feuille refermée.
- Albums du coffre : même appui long sur leurs tuiles (renommer, sortir du coffre, supprimer,
  mêmes dialogues que le menu de l'album, `VaultAlbumActions`), feuille protégée des captures.
  L'appui long des éléments sélectionne toujours ; albums automatiques et personnes inchangés.
- Accessibilité : la carte entière est un seul nœud (nom, nombre, dossier masqué), l'ondulation
  restant sur la couverture ; appui long nommé « Options de l'album » et action personnalisée du
  même nom pour TalkBack (`clickableWithOptions`).

### 7.7 Collages
- Entrée : « Créer un collage » en tête du menu « Plus » de la barre de sélection
  (`SelectionActionsBar`, dès deux éléments), donc dans toutes les grilles de `MediaGridScaffold` :
  timeline, albums et dossiers, favoris, résultats de recherche, personnes. `MediaActions.createCollage`
  prend la sélection dans l'ordre des choix, écarte les vidéos et garde les neuf premières photos
  (`CollageSelection`) ; moins de deux photos : message, sélection conservée ; sinon
  `CollageRoute(mediaIds, truncated)`, écran plein qui signale par une snackbar une sélection tronquée.
- Dispositions (`CollageLayouts`, logique pure) : 4 à 6 par nombre de photos (2 à 9) — grilles,
  rangées et colonnes, une grande et des petites, rangées ou colonnes inégales, mosaïques décalées,
  moulinet (5 photos ; seule disposition qu'aucune découpe d'un bord à l'autre ne produit). Arbre de
  découpes pondérées : un bord partagé est la même valeur pour les deux cases, le dernier morceau
  finit sur le bord du parent. Les photos remplissent les cases dans l'ordre de la sélection, la
  première prenant la grande case.
- Géométrie (`CollageGeometry`) : chaque case normalisée est placée dans le collage réduit d'un
  demi-espacement, puis réduite d'un demi-espacement ; les cases sont donc à un espacement l'une de
  l'autre et du bord, et, agrandies d'un demi-espacement, pavent exactement le collage réduit. Même
  calcul en pixels flottants (aperçu) et entiers (export : bord partagé arrondi une fois, puis décalé
  de l'espacement entier, identique partout). Espacement (0 à 5 %) et arrondi (0 à 8 %, au plus la
  moitié d'une case) en fraction du petit côté : l'aperçu a les proportions de l'export.
- Cadrage (`CellFraming`, logique pure) : la photo remplit sa case, centrée ; agrandissement de 1 à 4
  et centre de la partie visible normalisé, gardés par photo (ils la suivent dans un échange, un
  changement de disposition ou de format) et ramenés dans leurs bornes à chaque usage : jamais de vide
  dans une case. Glisser : la photo suit le doigt jusqu'à son bord ; pincer : le point sous les doigts
  y reste.
- Écran (`CollageScreen`, `CollageCanvas`, `CollagePanels`), aux couleurs de l'application : aperçu à
  la proportion choisie, aide aux gestes, panneaux Disposition (vignettes à la proportion du
  collage), Format (pastilles), Bordures (curseurs) et Fond (blanc, noir, couleurs principale,
  secondaire, tertiaire du schéma courant et leurs teintes atténuées ; couleur résolue au choix, celle
  qu'exporte le rendu). Une seule couche capte les gestes : un glisser ou un second doigt avant le
  délai de l'appui long cadre la photo ; un appui long immobile (retour haptique) emmène une vignette
  de la photo sous le doigt, la case visée est entourée, le relâchement échange les deux photos.
  Historique (`CollageEditor`, 50 étapes, comme l'éditeur) : un choix ou un échange forme une étape, un
  geste ou un glissement de curseur une seule ; fermer un collage modifié demande confirmation.
- Lecture des photos (`CollageMaker`, `CollageRenderer`), la même pour l'aperçu et l'export :
  `BitmapRegionDecoder` (JPEG, PNG, WebP, HEIF), orientation EXIF appliquée par le rendu
  (`ImageOrientation`, quarts de tour et miroirs ; ignorée pour PNG, GIF et BMP, comme Android), repli
  sur `ImageDecoder` réduit à 16 Mpx pour les autres formats. Aperçus de 1 280 px (960 au-delà de
  quatre photos), deux décodés à la fois : le cadrage réglé à l'écran est celui du collage enregistré.
- Détail des cases agrandies (`CollageDetail`, `CollageRenderer.detail`, `CollageMaker.loadDetail`) :
  une case dont l'aperçu est étiré à plus de 1,25 pixel de l'écran par pixel demande le détail de sa
  partie visible, plus un quart de marge de chaque côté (un petit glisser reste net), à la résolution
  de l'écran. Mémoire bornée : 4 Mpx (16 Mo, une bande de l'export) pour toutes les cases, partagés
  selon leur surface ; jamais plus fin que la photo ; lecture réduite par le plus grand facteur qui
  garde un pixel décodé par pixel du détail, 4 Mpx décodés au plus (plutôt un peu moins fin). Demandé
  à chaque changement de cadrage, décodé hors du fil principal 300 ms après le dernier (un geste en
  cours ne décode rien), case par case ; posé sur l'aperçu à la zone qu'il couvre vraiment (bornes
  arrondies au pixel stocké), il suit le glisser jusqu'au détail suivant, et disparaît quand la case
  revient à son aperçu. Formats sans décodeur de zones : l'aperçu seul (il faudrait décoder la photo
  entière).
- Export : JPEG qualité 95, 4 096 px sur le grand côté (4 096 × 4 096, 3 277 × 4 096, 3 072 × 4 096,
  2 304 × 4 096, 4 096 × 2 304). Pour chaque case, seule la zone stockée visible (plus 3 pixels décodés
  de marge pour le filtrage) est lue, réduite par la plus grande puissance de 2 qui garde au moins un
  pixel décodé par pixel du collage, par bandes de 4 Mpx décodés au plus : en mémoire, le collage
  (64 Mo) et, le temps d'une case, le fichier compressé de sa photo (que le décodeur copie) et une
  bande (16 Mo), même pour neuf photos de 50 Mpx. Bornes des zones alignées sur le
  facteur de réduction (même grille de pixels pour toutes les bandes), chaque bande ne peignant que
  son cœur arrondi au pixel : aucune couture. Les coins arrondis sont repeints du fond en une fois,
  hors du rectangle arrondi : leur lissage ne dépend pas des bandes. EXIF : dates de création avec
  fuseau horaire (MediaStore en tire la date de prise de vue), logiciel, orientation normale.
- Collage Ultra HDR (Android 14+, `CollageGainmap`, `CollageGainLayer`) : si des photos portent une
  gainmap (`UltraHdr.params`, décodage au seizième), le collage en reçoit une, au quart de sa taille
  (1 024 px pour 4 096), à un seul jeu de paramètres : bornes de gain couvrant celles de toutes les
  photos et le gain neutre, gamma 1, décalages de la première photo HDR, marges d'écran couvrant
  celles de toutes. Le gain de chaque photo y est réécrit valeur par valeur (table de 256 entrées
  par canal : même logarithme de gain), puis peint au même cadrage que ses pixels, bande par bande
  (le décodeur de zones rend la gainmap de chaque zone, `decodeWithGainmap`) ; cases SDR, fond,
  espacements et coins arrondis au gain neutre. ALPHA_8 si toutes les gainmaps n'ont qu'un canal,
  ARGB sinon. `Bitmap.setGainmap` puis la compression JPEG écrivent un JPEG Ultra HDR (MPF, que
  l'EXIF ajouté ensuite ne décale pas : positions relatives). Avant Android 14 ou sans photo HDR,
  rien ne change.
- Enregistrement par `MediaOperations.insertAndWrite` dans `Pictures/Focale/`
  (`Collage_AAAAMMJJ_HHMMSS.jpg` : entrée en attente, écrite puis publiée, comme une copie d'édition ;
  le fichier appartient à l'application, sans consentement à demander sous Android 10 comme 11+),
  synchronisation de l'index, puis visionneuse sur le collage parmi les médias de son dossier, à la
  place de l'écran de collage (le retour ramène à la grille). « Enregistrer et partager » ouvre en plus
  le partage (`MediaSharer`).
- Accessibilité : dispositions (« Grille 2 × 2 », « Une grande en haut, 3 en bas »…), formats
  (« Portrait (4:5) »), curseurs et fonds nommés ; chaque case est un élément TalkBack placé sur
  l'aperçu, lu avec sa photo (« Case 2 sur 4 : IMG_1234.jpg, 12 mars 2025 »), avec ses actions :
  échanger avec la case précédente ou suivante, agrandir, réduire, recentrer.

### 7.8 Langue
- Français et anglais ; ressources non qualifiées en anglais (`res/resources.properties` :
  `unqualifiedResLocale=en`), `values-fr` en français. AGP génère `android:localeConfig`
  (`generateLocaleConfig`) : Android 13+ propose Focale dans « Langue des applications ». Seules ces
  deux langues sont gardées des bibliothèques (`localeFilters`) : leurs textes suivent aussi.
- Réglages → Langue (`LanguageSection`) : Système, Français, English, pour toutes les versions.
  `AppLocales` est la seule porte : depuis Android 13, `LocaleManager` (le réglage du système, qui
  le garde) ; avant, `AppCompatDelegate.setApplicationLocales`,
  appliqué par `MainActivity` (une `AppCompatActivity`, thème `Theme.AppCompat.*.NoActionBar`) et
  gardé par AppCompat dans un fichier privé (`AppLocalesMetadataHolderService` et
  `autoStoreLocales` dans le manifeste), relu par `LocaleManagerCompat` et repris par le système au
  passage à Android 13. Le choix garde la région du téléphone quand il parle cette langue
  (« fr-CA », « en-GB »). Ni l'un ni l'autre n'est une clé de `SettingsRepository` : la
  sauvegarde des données ne reprend pas la langue.
- Changement sans redémarrage : l'activité est recréée (par le système, ou par AppCompat), l'état
  des écrans conservé (navigation, `rememberSaveable`, ViewModels). `LocaleChangeReceiver`
  (`LOCALE_CHANGED`, annoncé aussi pour un choix fait dans les réglages d'Android 13+, même Focale
  fermée) et le choix dans Focale préviennent les `LocaleChangeListener` (`LocaleRefresh`) : canaux
  de notification renommés, widget redessiné.
- Hors des activités, le contexte de l'application reste dans la langue du système avant Android
  13 : notifications et canaux (créés par les tâches de fond), widget et ses légendes passent par
  `AppLocales.localized`, et `AppLocales.locale` remplace `Locale.getDefault()` pour la langue de
  l'interface : dates et nombres (`Formatters`, résolution des photos), noms des lieux
  (`PlaceName.display`) et des pays, étiquettes (`LabelDictionary.displayName`), ordre
  alphabétique (personnes, coffre), analyse de la saisie (`SearchQueryParser` : ordre du jour et du
  mois des dates numériques, « 07/14/2021 » en anglais des États-Unis, l'autre ordre s'il est le
  seul valable ; noms de pays dans la langue de l'application, `SearchChipDetector`). Les écrans
  lisent la langue de leur configuration (`LocalConfiguration`, ressources de l'activité).
- Notes de version : `CHANGELOG.md` (français) et sa traduction `CHANGELOG.en.md`, aux mêmes
  sections dans le même ordre (`ChangelogTest`), sont embarqués dans les assets (`changelogAssets`,
  `app/build.gradle.kts`) ; « Nouveautés » lit celui de la langue de Focale (`Changelog.asset` :
  français, sinon anglais ; `WhatsNewRepository`). La CI écrit la description de la version GitHub
  avec la section française, puis, après la marque `<!-- en -->` (invisible sur GitHub), la
  section anglaise ; l'écran de mise à jour n'en affiche que la partie de sa langue
  (`ReleaseNotes.forLanguage`), les notes entières sans marque (versions antérieures, notes
  écrites sur GitHub). Une version déjà installée, antérieure à cette marque, affiche les deux.
- Limites : un titre déjà passé en argument de navigation (souvenir, étiquette ouverte depuis la
  recherche) et une notification déjà affichée gardent leur langue.

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
| `INTERNET` (variante `standard` uniquement) | fond de carte OSM (sur activation explicite), mises à jour ; sockets de la diffusion DLNA |
| `REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (`standard`) | installation des mises à jour, sans confirmation sur Android 12+ |
| `ACCESS_NETWORK_STATE` (`standard`) | réseau Wi-Fi et son adresse pour la diffusion DLNA (déjà déclarée par WorkManager, retirée de `strict`) |
| `CHANGE_WIFI_MULTICAST_STATE` (`standard`) | verrou multicast pendant la recherche SSDP des TV |
| `ACCESS_LOCAL_NETWORK` (`standard`, Android 17+, à l'exécution) | joindre les TV du réseau local ; demandée dans la feuille « Diffuser », jamais à l'ouverture de la visionneuse |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` (`standard`) | service au premier plan tant qu'une TV lit un média servi par Focale |

`ACCESS_WIFI_STATE` n'est pas demandée : l'adresse vient de `ConnectivityManager` et le verrou
multicast ne demande que `CHANGE_WIFI_MULTICAST_STATE`. Aucune autorisation globale du trafic en
clair (`cleartextTrafficPermitted`) : seuls les sockets bruts de la diffusion, limités au réseau
local, parlent HTTP. La variante `strict` n'a aucune permission réseau (`INTERNET`, état du réseau
et du Wi-Fi, multicast, réseau local) ni le service de diffusion : `tools/release/check_privacy.py`
le vérifie sur chaque APK release ; l'écran externe n'en demande aucune.
