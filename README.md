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
  grille, vidéos (Media3) aux commandes Material You, photos animées (Motion Photos
  Google et Samsung, appui long pour les lire), GIF animés, photos Ultra HDR affichées
  en HDR (Android 14+), panneau d'infos EXIF / localisation, texte de la photo
  sélectionnable et copiable.
- **Material You** : couleurs du fond d'écran (Android 12+), thème clair / sombre /
  système, noir pur AMOLED, palette « couleur depuis la photo », police variable
  Google Sans Flex, formes et morphing Expressive, retours haptiques.
- **Adaptatif** : barre de navigation sur téléphone, rail sur tablette et pliable,
  edge-to-edge, icône adaptative et thématique (Android 13+).
- **Organisation** : albums par dossier et albums virtuels, favoris, corbeille
  30 jours, sélection multiple (appui long + glisser), copier / déplacer / renommer /
  supprimer / partager, tri et filtres (photos, vidéos, captures, GIF), dossiers
  masqués de Photos (WhatsApp, mèmes…) mais toujours dans Albums.
- **Recherche intelligente** sur l'appareil : étiquettes ML Kit en arrière-plan
  (« plage », « chien »…, en français ou en anglais), texte des photos et des captures
  d'écran (« facture », « wifi »…), dates, lieux (géocodage hors ligne), écran de
  nettoyage : doublons, photos floues, captures d'écran et grosses vidéos.
- **Recherche en langage naturel** : « chien sur la plage », « voiture rouge dans la
  neige »… Un modèle CLIP compact (TinyCLIP, 23 Mo, ONNX Runtime) calcule l'empreinte de
  chaque photo en arrière-plan ; la requête, traduite en anglais hors ligne, est comparée
  à toutes les empreintes et les photos les plus proches s'ajoutent aux résultats.
- **Personnes** : visages détectés (ML Kit) et regroupés sur l'appareil (empreintes SFace
  exécutées par ONNX Runtime) ; section dans l'onglet Albums, nom, vignette, masquage,
  fusion, retrait d'une photo ; recherche par nom et personnes listées dans les infos.
- **Édition** : recadrage libre ou au format, rotation, miroir, filtres, luminosité /
  contraste / saturation / chaleur, dessin (stylo, surligneur, ligne, flèche, rectangle,
  ellipse, gomme) ; enregistrement en copie ou à la place de l'original. Vidéos :
  coupe du début et de la fin, son coupé, enregistrées en copie (Media3 Transformer).
- **Coffre-fort** : photos et vidéos chiffrées (Tink, clé dans l'Android Keystore),
  retirées de MediaStore et des autres applications, déverrouillage par code ou
  biométrie, reverrouillage automatique, captures d'écran interdites ; sauvegarde
  chiffrée par mot de passe (`.fvault`, PBKDF2 + AES-256-GCM en flux) à restaurer sur un
  autre téléphone.
- **Vie privée** : option de partage sans EXIF ni localisation (photos nettoyées
  sans recompression, vidéos remultiplexées).
- **Souvenirs** « il y a X ans » en tête de la timeline (carrousel Material 3), à partir
  des médias pris la même semaine les années précédentes.
- **Carte** des photos géolocalisées (osmdroid), regroupées en vignettes selon le zoom,
  sur un fond vectoriel hors ligne aux couleurs du thème ; fond OpenStreetMap en ligne
  en option (variante `standard`).
- **Diaporama** en fondu enchaîné avec effet Ken Burns, depuis la visionneuse ou
  n'importe quel album.
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
| `standard` | `INTERNET` pour le fond de carte OpenStreetMap (**désactivé par défaut**) et les mises à jour intégrées (recherche quotidienne sur GitHub, désactivable) |
| `strict` | Aucune permission `INTERNET` : carte hors ligne, mises à jour manuelles |

### Publier une version

1. Ajouter en tête de [`CHANGELOG.md`](CHANGELOG.md) une section `## X.Y.Z` (puces
   courtes, en français) : l'application l'affiche après la mise à jour et la CI la
   reprend comme description de la version GitHub.
2. Sur GitHub : Releases → Draft a new release → tag `vX.Y.Z` → Publish. La CI y
   ajoute les APK signés.

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

## Stack

Kotlin 2.4 · AGP 9 · Compose (Material 3 1.5, adaptive) · Hilt · Coroutines/Flow ·
Room · Paging 3 · DataStore · WorkManager · Coil 3 · telephoto · Media3 · MaterialKolor ·
ML Kit (modèles embarqués) · ONNX Runtime · osmdroid · Tink · Glance.

## Licences des ressources

- Police **Google Sans Flex** : SIL Open Font License 1.1
  (`app/src/main/assets/licenses/google_sans_flex_OFL.txt`).
- Noms de lieux du géocodage hors ligne : **GeoNames**, CC BY 4.0
  (<https://www.geonames.org>), compilés par `tools/geonames/build_cities.py`.
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
  sa télémétrie Android est neutralisée (fournisseur retiré du manifeste, `setTelemetry(false)`).

Les scripts de `tools/` régénèrent les ressources dérivées (icône, lieux, fond de
carte, modèles de la recherche en langage naturel) à partir des données sources ; ils ne
sont pas nécessaires à la compilation.
