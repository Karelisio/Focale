# Images du test de fumée

Poussées dans `/sdcard/Pictures/` de l'émulateur par [`../smoke_test.sh`](../smoke_test.sh).

- `facture.png` (1000 × 560, ~20 Ko) : texte net « FACTURE 2024 » / « TOTAL 42,00 », pour la
  lecture du texte. Image générée, sans droits de tiers :

  ```python
  from PIL import Image, ImageDraw, ImageFont
  police = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 96)
  image = Image.new("RGB", (1000, 560), "white")
  dessin = ImageDraw.Draw(image)
  dessin.text((70, 110), "FACTURE 2024", font=police, fill="black")
  dessin.text((70, 330), "TOTAL 42,00", font=police, fill="black")
  image.save("facture.png", optimize=True)
  ```

- Photo d'un visage réel, pour les étiquettes et les visages : non versionnée, téléchargée par
  le script au moment du test. Portraits officiels de la NASA, **domaine public** (œuvres du
  gouvernement fédéral des États-Unis), essayés dans l'ordre :
  1. Eileen Collins (512 × 512, visage de face), copie de scikit-image
     (`skimage/data/astronaut.png`, tag v0.19.3) : « No known copyright restrictions, released
     into the public domain » (base NASA Great Images) ;
  2. Neil Armstrong : <https://commons.wikimedia.org/wiki/File:Neil_Armstrong_pose.jpg> ;
  3. Buzz Aldrin : <https://commons.wikimedia.org/wiki/File:Buzz_Aldrin.jpg>.

  Sans téléchargement possible, le contrôle des visages est sauté (avertissement) et
  l'étiquetage n'est contrôlé que par l'enregistrement de l'analyse principale.
