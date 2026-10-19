# Modèles de la recherche en langage naturel

`export_clip.py` produit les modèles embarqués dans `app/src/main/assets/models/` pour
la recherche en langage naturel (« chien sur la plage »), ainsi que les références des
tests JVM (`app/src/test/resources/clip/`). Il n'est pas nécessaire à la compilation.

## Sources

- Modèle : **TinyCLIP** (Microsoft, licence MIT, <https://github.com/microsoft/Cream>),
  checkpoint `TinyCLIP-ViT-8M-16-Text-3M-YFCC15M.pt` du dépôt
  <https://github.com/wkcn/TinyCLIP-model-zoo/releases/tag/checkpoints>.
- Vocabulaire BPE : `bpe_simple_vocab_16e6.txt.gz` (OpenAI CLIP, licence MIT),
  <https://raw.githubusercontent.com/openai/CLIP/main/clip/bpe_simple_vocab_16e6.txt.gz>.

L'architecture est reconstruite d'après le `state_dict` (`clip_model.py`) : les modèles
TinyCLIP « auto » ont un nombre de têtes et une largeur de MLP propres à chaque bloc, et un
bloc peut avoir perdu son attention. Le checkpoint est lu en poids seuls
(`torch.load(weights_only=True)`), sans le code d'entraînement.

## Usage

Python 3.10+ avec `torch`, `onnx`, `onnxruntime` (1.31), `numpy`, `regex` et `pillow` :

```bash
python3 tools/clip/export_clip.py \
    --checkpoint TinyCLIP-ViT-8M-16-Text-3M-YFCC15M.pt --vocab bpe_simple_vocab_16e6.txt.gz \
    --assets app/src/main/assets/models --test-resources app/src/test/resources/clip \
    [--imagenet <dossier> --labels imagenet-simple-labels.json] [--photos <dossier>]
```

- `--imagenet` : les 1000 images d'exemple d'ImageNet, une par classe, nommées
  `0000.jpg` à `0999.jpg` (par exemple celles de
  <https://github.com/EliSchwartz/imagenet-sample-images>), avec les noms de classes de
  <https://github.com/anishathalye/imagenet-simple-labels> : exactitude zéro-shot et
  calibrage des seuils de la recherche (`SimilarityThreshold.Default`).
- `--photos` : photos supplémentaires pour le contrôle des écarts de quantification.
- `--references-only` : régénère seulement les références des tests, avec les modèles
  déjà exportés.

## Étapes

1. Export ONNX (opset 18) : `tinyclip_image.onnx` (`pixels` float32 `[1, 3, 224, 224]`)
   et `tinyclip_text.onnx` (`tokens` int64 `[1, 77]`), sortie `embedding` float32
   `[1, 512]` de norme 1. La convolution d'entrée est écrite comme un produit matriciel
   sur les carreaux, pour être quantifiée comme le reste.
2. Quantification : poids des produits matriciels sur 8 bits par blocs de 64
   (`MatMulNBits`, activations quantifiées par blocs au calcul). La quantification
   dynamique classique (activations par tenseur) déforme trop l'encodeur de texte
   (cosinus 0,55 au pire sur TinyCLIP 22M). Table des jetons : 8 bits par ligne pour les 20 512 jetons les
   plus fréquents, 4 bits par blocs de 32 pour les autres, les trois colonnes aux valeurs
   extrêmes restant sur 8 bits. Réduire le vocabulaire (fusions BPE tronquées) dégrade
   fortement la qualité et n'est pas retenu.
3. Contrôles : cosinus entre empreintes torch float32 et ONNX quantifiées sur des images
   et des phrases d'essai (échec sous 0,98), zéro-shot sur des images de synthèse.
4. Références des tests : jetons du tokeniseur (identiques à `clip/simple_tokenizer.py`
   d'OpenAI, hors réparation ftfy), images PNG de synthèse et empreintes attendues.

Le prétraitement (`preprocess`) et le tokeniseur (`ClipTokenizer`) de `clip_model.py` sont
les références de `ClipPreprocessor.kt` et `ClipTokenizer.kt` ; `ClipModelsTest` et
`ClipTokenizerTest` vérifient qu'ils restent identiques. Toute modification du modèle ou
du prétraitement impose d'incrémenter `ClipModels.VERSION` (les empreintes sont alors
recalculées).

## Résultats de la version embarquée

Mesures sur les 1000 images d'exemple d'ImageNet, 31 photos et 5 images de synthèse ;
temps sur un cœur x86 (l'ordre de grandeur relatif vaut pour ARM).

| | ViT-8M/16 Text-3M (YFCC-15M), **embarqué** | auto ViT-22M/32 Text-10M (LAION-400M) |
|---|---|---|
| Taille : image + texte + vocabulaire | 9,2 + 12,7 + 1,4 = **23,2 Mo** | 24,4 + 30,9 + 1,4 = 56,7 Mo |
| Cosinus torch / ONNX, 86 images (min / moyenne) | 0,998 / 0,999 | 0,993 / 0,998 |
| Cosinus torch / ONNX, 1023 phrases (min / moyenne) | 0,996 / 0,9995 | 0,994 / 0,999 |
| Zéro-shot ImageNet top-1, float32 → quantifié | 40,3 % → 40,2 % | 54,3 % → 53,9 % |
| Recherche de 20 concepts larges (mAP, float32) | 0,649 | 0,674 |
| Empreinte d'une image / d'un texte | 39 ms / 5 ms | 26 ms / 19 ms |

Le modèle léger est retenu : 2,5 fois plus petit, il est presque aussi bon pour les
recherches de galerie (chien, oiseau, plage, voiture…) ; l'écart se creuse sur les
distinctions fines (races, espèces), que les étiquettes ML Kit couvrent en partie.

Seuils de la recherche (`SimilarityThreshold.Default`) : minimum 0,37 (98e centile des
scores sans rapport) et écart de 0,06 au meilleur score ; sur 15 concepts larges, précision
0,57 et rappel 0,72 (mAP 0,716 du modèle quantifié), et un concept absent de la
bibliothèque ne ramène qu'environ 1 % des photos.
