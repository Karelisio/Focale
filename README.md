# Focale

Galerie photo Android **100 % hors ligne**, en Kotlin et Jetpack Compose, habillée de
**Material 3 Expressive** et de couleurs Material You.

> Aucune donnée ne quitte l'appareil : pas de compte, pas de statistiques, pas de cloud.
> L'étiquetage des photos (ML Kit), la détection de doublons et le géocodage tournent
> localement.

## Fonctionnalités

- **Timeline** paginée (Paging 3) pensée pour 50 000+ médias, regroupée par jour,
  mois ou année ; grille zoomable par pincement (2 à 7 colonnes) ; barre de défilement
  rapide avec bulle de date.
- **Visionneuse** plein écran : swipe, zoom et double appui (sous-échantillonnage des
  très grandes images), glisser vers le bas pour fermer, transition partagée depuis la
  grille, vidéos (Media3), GIF animés, panneau d'infos EXIF / localisation.
- **Material You** : couleurs du fond d'écran (Android 12+), thème clair / sombre /
  système, noir pur AMOLED, palette « couleur depuis la photo », police variable
  Google Sans Flex, formes et morphing Expressive, retours haptiques.
- **Adaptatif** : barre de navigation sur téléphone, rail sur tablette et pliable,
  edge-to-edge, icône adaptative et thématique (Android 13+).
- **Organisation** : albums par dossier et albums virtuels, favoris, corbeille
  30 jours, sélection multiple (appui long + glisser), copier / déplacer / renommer /
  supprimer / partager, tri et filtres (photos, vidéos, captures, GIF).
- **Recherche intelligente** sur l'appareil : étiquettes ML Kit en arrière-plan
  (« plage », « chien »…, en français ou en anglais), dates, lieux (géocodage hors
  ligne), écran de nettoyage des doublons et des photos floues.
- **Édition** : recadrage libre ou au format, rotation, miroir, filtres, luminosité /
  contraste / saturation / chaleur ; enregistrement en copie ou à la place de
  l'original.
- **Coffre-fort** : photos et vidéos chiffrées (Tink, clé dans l'Android Keystore),
  retirées de MediaStore et des autres applications, déverrouillage par code ou
  biométrie, reverrouillage automatique, captures d'écran interdites.
- **Vie privée** : option de partage sans EXIF ni localisation (photos nettoyées
  sans recompression, vidéos remultiplexées).

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
| `standard` | Permission `INTERNET` uniquement pour le fond de carte OpenStreetMap, **désactivé par défaut** |
| `strict` | Aucune permission `INTERNET` : la carte n'utilise que le fond hors ligne |

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
dans les artefacts de l'exécution. Un tag `v*` crée une version GitHub avec les APK
release. Pour signer en CI, ajoutez les secrets `FOCALE_KEYSTORE_BASE64` (keystore encodé
en base64), `FOCALE_STORE_PASSWORD`, `FOCALE_KEY_ALIAS` et `FOCALE_KEY_PASSWORD`.

## Stack

Kotlin 2.4 · AGP 9 · Compose (Material 3 1.5, adaptive) · Hilt · Coroutines/Flow ·
Room · Paging 3 · DataStore · WorkManager · Coil 3 · telephoto · Media3 · MaterialKolor ·
ML Kit (modèle embarqué) · osmdroid · Tink · Glance.

## Licences des ressources

- Police **Google Sans Flex** : SIL Open Font License 1.1
  (`app/src/main/assets/licenses/google_sans_flex_OFL.txt`).
