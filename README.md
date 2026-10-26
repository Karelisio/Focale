# Focale

Galerie photo Android **100 % hors ligne**, en Kotlin et Jetpack Compose, habillée de
**Material 3 Expressive** et de couleurs Material You.

> Aucune donnée ne quitte l'appareil : pas de compte, pas de statistiques, pas de cloud.
> L'étiquetage des photos et la lecture de leur texte (ML Kit), le regroupement des visages,
> la recherche en langage naturel, la détection de doublons et le géocodage tournent
> localement.

## Fonctionnalités

- **Timeline** paginée (Paging 3) pensée pour 50 000+ médias, regroupée par jour,
  mois ou année ; grille zoomable par pincement (2 à 7 colonnes) ; barre de défilement
  rapide avec bulle de date.
- **Visionneuse** plein écran : swipe, zoom et double appui (sous-échantillonnage des
  très grandes images), glisser vers le bas pour fermer, transition partagée depuis la
  grille, vidéos (Media3) aux commandes Material You, vitesse de lecture de 0,25× à 2×
  (gardée d'une vidéo à l'autre, hauteur du son conservée) et double appui pour avancer ou
  reculer de 10 s (appuis rapprochés cumulés), photos animées (Motion Photos
  Google et Samsung, appui long pour les lire), GIF animés, photos Ultra HDR affichées
  en HDR (Android 14+), rotation à deux doigts (enregistrée sans perte dans l'orientation
  EXIF des JPEG et WebP, et dans leur orientation XMP que lisent Lightroom et d'autres
  logiciels ; dans la propriété `irot` des HEIF / HEIC et AVIF, sans réencodage, EXIF et XMP
  mis d'accord, photos animées, traîne des Galaxy et carte de gain HDR des iPhone comprises),
  panneau d'infos EXIF / localisation, texte de la photo sélectionnable et
  copiable. Photos 360° (métadonnées GPano des JPEG, WebP et HEIF, sphères complètes ou
  partielles) en vue immersive OpenGL : glisser pour regarder autour, pincer pour le champ,
  « Bouger le téléphone » (capteur de rotation), retour à la vue initiale du fichier ; vidéos 360°
  (MP4 et MOV aux métadonnées sphériques V1 ou V2, équirectangulaires, mono ou stéréoscopiques)
  dans la même vue, avec leurs commandes habituelles (lecture, position, vitesse, ±10 s) ; panoramas
  (GPano à plat, ou 2,5:1 et plus) en « Vue panoramique », à la hauteur de l'écran, parcourus
  au doigt ou en tournant sur soi. Impression des photos (aperçu d'Android, une photo par page
  dans son sens, orientation EXIF respectée).
- **Application par défaut** : photos et vidéos des autres applications (« Ouvrir avec »,
  revue de l'appareil photo) ouvertes parmi celles de leur dossier, ou seules (messageries,
  téléchargements) ; Réglages → Fichiers indique l'application par défaut et aide à choisir Focale.
- **Material You** : couleurs du fond d'écran (Android 12+), thème clair / sombre /
  système, noir pur AMOLED, palette « couleur depuis la photo », police variable
  Google Sans Flex, formes et morphing Expressive, retours haptiques.
- **Adaptatif** : barre de navigation sur téléphone, rail sur tablette et pliable,
  edge-to-edge, icône adaptative et thématique (Android 13+).
- **Français ou anglais** : Réglages → Langue (celle du système, Français, English), sur toutes
  les versions d'Android, appliquée sans redémarrer ; depuis Android 13, c'est aussi le réglage
  « Langue des applications » d'Android. Dates, nombres, lieux, étiquettes, recherche (ordre des
  dates saisies), notifications et widget suivent la langue choisie.
- **Organisation** : albums par dossier et albums virtuels, favoris, corbeille
  30 jours, sélection multiple (appui long + glisser), copier / déplacer / renommer /
  supprimer / partager / imprimer, tri et filtres (photos, vidéos, captures, GIF), dossiers
  masqués de Photos (WhatsApp, mèmes…) mais toujours dans Albums. Appui long sur un album
  pour ses options sans l'ouvrir : diaporama, renommer, masquer dans Photos, déplacer dans
  le coffre-fort, supprimer (dans le coffre : renommer, sortir du coffre, supprimer).
  Albums automatiques tirés de l'analyse : documents, reçus, animaux, nourriture,
  paysages, fêtes, véhicules, sport.
- **Recherche intelligente** sur l'appareil : étiquettes ML Kit en arrière-plan
  (« plage », « chien »…, en français ou en anglais), texte des photos et des captures
  d'écran (« facture », « wifi »…), dates, lieux (géocodage hors ligne, noms français et
  anglais : « Lisbonne » comme « Lisbon »). Filtres combinés en pastilles, avec ou sans
  texte : personnes (photos ensemble), lieu, période (années, mois, plage de dates), type ;
  résultats dans l'onglet, mis à jour à chaque pastille. Nettoyage (onglet Plus) :
  doublons, photos floues, captures d'écran et grosses vidéos.
- **Recherche en langage naturel** : « chien sur la plage », « voiture rouge dans la
  neige »… Un modèle CLIP compact (TinyCLIP, 23 Mo, ONNX Runtime) calcule l'empreinte de
  chaque photo en arrière-plan ; la requête, traduite en anglais hors ligne, est comparée
  à toutes les empreintes et les photos les plus proches s'ajoutent aux résultats.
- **Personnes** : visages détectés (ML Kit) et regroupés sur l'appareil (empreintes SFace
  exécutées par ONNX Runtime) ; section dans l'onglet Albums, nom, vignette, masquage,
  fusion, retrait d'une photo ; tri (photos, nom, récentes) et personnes épinglées en
  tête ; suggestions « Est-ce la même personne ? » ; revue des visages d'une personne
  (les moins ressemblants d'abord, retirer ou déplacer une sélection) ; photos où
  plusieurs personnes figurent ensemble ; recherche par nom et personnes listées dans les
  infos.
- **Édition** : recadrage libre ou au format, rotation, miroir, redressement à angle libre
  (règle graduée ou deux doigts, recadré automatiquement sans coin vide), filtres,
  luminosité / contraste / saturation / chaleur, dessin (stylo, surligneur, ligne, flèche,
  rectangle, ellipse, gomme), texte (plusieurs lignes, affiché sur la photo à mesure qu'on le
  tape, réglages compris, la saisie restant au-dessus du clavier ; déplacé au doigt, agrandi et
  tourné à deux doigts ; couleur, taille, fond encadré ou contour, gras), zones floutées pour
  cacher un visage ou une plaque (rectangle, ellipse ou pinceau ; flou fort ou mosaïque,
  irréversibles ; « Visages » floute ceux que détecte ML Kit) ; annuler / rétablir, et « Tout
  annuler » qui rend l'original d'un coup (confirmation au-delà de quelques modifications, lui
  aussi annulable) ; enregistrement en copie ou à la place de
  l'original. Vidéos : coupe du début et de la fin, son coupé, enregistrées en copie (Media3
  Transformer). Collages de 2 à 9 photos depuis la sélection multiple (vidéos ignorées) :
  plusieurs dispositions par nombre de photos (grilles, une grande et des petites, mosaïques
  décalées, moulinet), format 1:1, 4:5, 3:4, 9:16 ou 16:9, espacement, coins arrondis, fond blanc,
  noir ou aux couleurs Material You ; chaque photo se cadre au doigt et s'agrandit en pinçant, un
  appui long l'échange avec une autre case ; nouveau JPEG de 4 096 px dans `Pictures/Focale`, ouvert
  dans la visionneuse ou partagé aussitôt, Ultra HDR si des photos le sont (Android 14+ : gainmap
  composée case par case, cases SDR au gain neutre).
- **Coffre-fort** : photos et vidéos chiffrées (Tink, clé dans l'Android Keystore),
  retirées de MediaStore et des autres applications, déverrouillage par code ou
  biométrie, reverrouillage automatique, captures d'écran interdites ; sauvegarde
  chiffrée par mot de passe (`.fvault`, PBKDF2 + AES-256-GCM en flux) à restaurer sur un
  autre téléphone, manuelle ou automatique (chaque jour ou chaque semaine, dans un dossier
  choisi : carte SD, clé USB… ; les 3 dernières gardées, mot de passe non conservé). Albums
  du coffre : un album entier y entre d'un geste, et ses nouvelles photos s'y ajoutent sans
  rien déchiffrer (chaque élément est chiffré à part), signalées par une notification qui
  les y met d'un geste. Mise au coffre en tâche de fond (service au premier plan, reprise
  sans rien rechiffrer, état réparé au lancement), tri mémorisé des éléments et des albums,
  corbeille de 30 jours. Une photo qui en sort retrouve sa date.
- **Sauvegarde des données** (Réglages) : favoris, albums, personnes et leurs visages, dossiers
  masqués, réglages et historique de recherche dans un fichier `.focale` chiffré par mot de passe
  (même chiffrement que le coffre), à restaurer sur un nouveau téléphone, où les photos copiées à
  part sont retrouvées par leur dossier et leur nom ; manuelle ou automatique (chaque jour ou
  chaque semaine, dans un dossier choisi, les 3 dernières gardées). À la restauration, une case
  par catégorie (favoris, albums, personnes, dossiers masqués, réglages, historique, dates
  d'origine) pour n'en reprendre qu'une partie ; le fichier choisi (Drive ou autre) n'est lu
  qu'une fois, copié chiffré dans le cache de Focale le temps de la restauration. Rien ne part dans
  la sauvegarde Google.
- **Vie privée** : option de partage sans EXIF ni localisation (photos nettoyées
  sans recompression, vidéos remultiplexées).
- **Souvenirs** « il y a X ans » en tête de la timeline (carrousel Material 3), à partir
  des médias pris la même semaine les années précédentes, avec les moments partagés avec
  une personne (« Il y a 2 ans avec Alice ») et les voyages loin de chez soi
  (« Lisbonne · juin 2023 »), détectés sur l'appareil.
- **Carte** des photos géolocalisées (osmdroid), regroupées en vignettes selon le zoom,
  sur un fond vectoriel hors ligne aux couleurs du thème ; fond OpenStreetMap en ligne
  en option (variante `standard`).
- **Diaporama** en fondu enchaîné avec effet Ken Burns, depuis la visionneuse ou
  n'importe quel album.
- **Diffusion vers une TV**, sans Google Cast ni bibliothèque tierce : la visionneuse et le
  diaporama s'affichent en plein écran sur un écran externe (HDMI, USB-C, Miracast, « Caster
  l'écran » du système), le téléphone gardant les commandes ; dans la variante `standard`, vers
  les TV du réseau local (DLNA/UPnP : Samsung, LG, Sony, Kodi…), servies par un petit serveur
  local démarré le temps de la diffusion (photo réduite en JPEG sans métadonnées, vidéo telle
  quelle, lecture, pause, position et volume depuis le téléphone, touches de volume du téléphone
  comprises, notification « Arrêter » en arrière-plan). Les TV qui se désignent par leur nom
  (« bravia.local ») sont trouvées aussi, tout comme celles d'un réseau sans IPv4 (IPv6 locales).
  Une vidéo que la TV refuse, ou qu'elle lâche en perdant la connexion, reprend
  sur le téléphone à la même position, la diffusion continuant pour les médias suivants. Rien ne
  sort du réseau local (hormis, pour un nom, la question au résolveur DNS du réseau) ; le
  coffre-fort n'est jamais diffusé.
- **Widget** « Souvenir » (Glance, couleurs Material You) : une photo du jour, renouvelée
  toutes les quatre heures, qui s'ouvre dans la visionneuse.
- **Nouveautés** présentées au premier lancement après chaque mise à jour, tirées de
  [`CHANGELOG.md`](CHANGELOG.md).

Le détail de l'architecture et du découpage est dans
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Compiler

Prérequis : **JDK 21** et le SDK Android (plateforme 37). Le wrapper Gradle télécharge
la bonne version de Gradle.

```bash
# APK de debug (variante standard)
./gradlew assembleStandardDebug

# APK release (R8) ; signé avec la clé de debug si aucune clé n'est configurée
./gradlew assembleStandardRelease

# Tests unitaires et lint
./gradlew testStandardDebugUnitTest lintStandardDebug
```

Les APK sont produits dans `app/build/outputs/apk/<variante>/<type>/`, un par
architecture (`arm64-v8a` pour la plupart des téléphones récents) plus un APK
`universal`.

### Variantes

| Variante | Réseau |
|---|---|
| `standard` | `INTERNET` pour le fond de carte OpenStreetMap (**désactivé par défaut**) et les mises à jour intégrées (recherche quotidienne sur GitHub, désactivable) ; réseau local pour la diffusion vers les TV (DLNA : recherche SSDP à l'ouverture de la visionneuse, désactivable, et petit serveur local pendant la diffusion) |
| `strict` | Aucune permission réseau : carte hors ligne, mises à jour manuelles, diffusion vers un écran externe seulement |

### Publier une version

1. Ajouter en tête de [`CHANGELOG.md`](CHANGELOG.md) une section `## X.Y.Z` (puces
   courtes, en français) : l'application l'affiche après la mise à jour et la CI la
   reprend comme description de la version GitHub.
2. Au choix : Actions → Android → Run workflow avec la version `X.Y.Z` (la CI crée la
   version `vX.Y.Z` avec les APK signés et les notes), ou Releases → Draft a new release
   → tag `vX.Y.Z` → Publish (la CI y ajoute les APK signés).

### Tests

`./gradlew testStandardDebugUnitTest` exécute les tests JVM (Robolectric), dont des
captures d'interface comparées aux références de `app/src/test/screenshots` quand
`-Proborazzi.test.verify=true` est passé (c'est le cas en CI). Après un changement
d'interface voulu : `./gradlew recordRoborazziStandardDebug`.

### Mises à jour intégrées

La variante `standard` interroge l'API publique des versions GitHub (au plus une fois par
jour, au passage au premier plan, ou depuis Réglages → Mises à jour). L'APK de
l'architecture de l'appareil est téléchargé, contrôlé (taille et SHA-256 publiés par
GitHub) puis installé par `PackageInstaller` : Android exige la même signature et une
version plus récente. Le numéro de version vient du tag (`v1.2.3` → `versionCode` 1002003) ;
publier une mise à jour revient donc à créer une version GitHub `vX.Y.Z` plus élevée.

### Signature release

Créez `keystore.properties` à la racine (jamais versionné) :

```properties
STORE_FILE=/chemin/vers/focale.jks
STORE_PASSWORD=…
KEY_ALIAS=…
KEY_PASSWORD=…
```

ou définissez les variables d'environnement `FOCALE_STORE_FILE`,
`FOCALE_STORE_PASSWORD`, `FOCALE_KEY_ALIAS`, `FOCALE_KEY_PASSWORD`.

### Intégration continue

Le workflow [`.github/workflows/android.yml`](.github/workflows/android.yml) exécute tests
et lint puis assemble les APK (standard et strict) à chaque push ; ils sont téléchargeables
dans les artefacts de l'exécution. Une version GitHub publiée (tag `vX.Y.Z`) reçoit les
APK release signés. Pour signer en CI, ajoutez les secrets `FOCALE_KEYSTORE_BASE64` (keystore encodé
en base64), `FOCALE_STORE_PASSWORD`, `FOCALE_KEY_ALIAS` et `FOCALE_KEY_PASSWORD`.

Un test de fumée ([`tools/release/smoke_test.sh`](tools/release/smoke_test.sh), une dizaine
de minutes) exerce ensuite l'APK release universel (standard) sur un émulateur Android 14,
l'interface étant pilotée par uiautomator ; la publication d'une version en dépend :

1. **Mise à jour** : la dernière version GitHub que l'APK peut remplacer (même signature, pas
   plus récente ; [`version_precedente.sh`](tools/release/version_precedente.sh)) analyse deux
   images de test et reçoit un favori, un album et le réglage « Noir pur », puis l'APK est
   installé par-dessus : démarrage sans plantage, base migrée vers le dernier schéma de
   `app/schemas` (version et empreinte Room), données intactes. `FOCALE_PREVIOUS_TAG`
   (`vX.Y.Z`) impose la version de départ.
2. **Langue** : anglais, français puis anglais (langue par application) ; l'interface suit.
3. **Impression** d'une photo depuis la visionneuse, par « Enregistrer en PDF ».
4. **Écran externe** simulé : la visionneuse y présente sa photo, et une photo demandée
   par-dessus (widget) remplace celle affichée.
5. **Sauvegarde des données** par Réglages et le sélecteur du système, effacement des données
   de Focale (`pm clear`), nouvelle analyse (étiquettes, texte, visages), puis restauration :
   favori, album et réglage reviennent.

En cas d'échec, captures d'écran des étapes et journal complet de l'émulateur sont publiés en
artefact (`fumee-<commit>`). En local, avec un émulateur `google_apis` en anglais démarré
(Focale y est réinstallé, ses données effacées ; l'APK précédent est facultatif) :
`tools/release/smoke_test.sh app/build/outputs/apk/standard/release/app-standard-universal-release.apk [APK_PRÉCÉDENT]`.

## Stack

Kotlin 2.4 · AGP 9 · Compose (Material 3 1.5, adaptive) · Hilt · Coroutines/Flow ·
Room · Paging 3 · DataStore · WorkManager · Coil 3 · telephoto · Media3 · MaterialKolor ·
ML Kit (modèles embarqués) · ONNX Runtime · osmdroid · Tink · Glance.

## Licences des ressources

- Police **Google Sans Flex** : SIL Open Font License 1.1
  (`app/src/main/assets/licenses/google_sans_flex_OFL.txt`).
- Noms de lieux du géocodage hors ligne : **GeoNames**, CC BY 4.0
  (<https://www.geonames.org>), compilés par `tools/geonames/build_cities.py` ; noms français et
  anglais : noms alternatifs de GeoNames (**alternateNames**, CC BY 4.0), lus dans la base
  compilée par Open-Meteo (<https://github.com/open-meteo/geocoding-api>).
- Fond de carte hors ligne : **Natural Earth**, domaine public
  (<https://www.naturalearthdata.com>), compilé par `tools/basemap/build_basemap.py`.
- Tuiles en ligne (optionnelles) : © contributeurs **OpenStreetMap**, données sous
  licence ODbL (<https://www.openstreetmap.org/copyright>) ; l'attribution est affichée
  sur la carte.
- Étiquetage d'images, reconnaissance de texte (alphabet latin) et détection des visages :
  modèles **ML Kit** embarqués dans l'APK, sans téléchargement, soumis aux conditions
  d'utilisation de ML Kit.
- Empreintes de visages : modèle **SFace** d'OpenCV Zoo (`face_recognition_sface_2021dec_int8`),
  licence Apache 2.0 (`app/src/main/assets/licenses/sface_APACHE-2.0.txt`), redistribué sans
  modification dans `app/src/main/assets/models/sface_int8.onnx`.
- Recherche en langage naturel : modèle **TinyCLIP** (Microsoft, licence MIT,
  <https://github.com/microsoft/Cream>), converti et quantifié par
  `tools/clip/export_clip.py` ; tokeniseur et vocabulaire BPE de **CLIP** (OpenAI,
  licence MIT, <https://github.com/openai/CLIP>).
- Exécution des modèles : **ONNX Runtime**, licence MIT (`app/src/main/assets/licenses/onnxruntime_MIT.txt`) ;
  sa télémétrie Android est neutralisée (fournisseur retiré du manifeste, `setTelemetry(false)`,
  contrôlé par la CI dans chaque APK release), et coupée dans les tests sur la JVM
  (`ORT_DISABLE_TELEMETRY`).

Les scripts de `tools/` régénèrent les ressources dérivées (icône, lieux, fond de
carte, modèles de la recherche en langage naturel) à partir des données sources, ou
calibrent des seuils sur des données publiques (`tools/faces/` : regroupement des visages,
sur LFW ; `tools/trips/` : voyages, sur YFCC100M) ; ils ne sont pas nécessaires à la compilation.
