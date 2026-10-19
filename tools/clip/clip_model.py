"""TinyCLIP reconstruit d'après son state_dict, prétraitement et tokeniseur de référence.

Les modèles TinyCLIP « auto » (Microsoft, licence MIT, https://github.com/microsoft/Cream)
sont élagués couche par couche : la largeur est commune à la tour, mais le nombre de
têtes d'attention et la largeur du MLP varient d'un bloc à l'autre, et un bloc peut avoir
perdu toute son attention. L'architecture est donc déduite des formes des poids, sans
dépendre du code d'entraînement.

Le prétraitement d'image et le tokeniseur sont les implémentations de référence de ceux
de l'application (app.focale.ml.ClipPreprocessor et ClipTokenizer) : toute modification
doit être faite des deux côtés.
"""
import gzip
import html
import math
from functools import lru_cache

import numpy as np
import regex
import torch
import torch.nn as nn
import torch.nn.functional as F

HEAD_DIM = 64
IMAGE_SIZE = 224
CONTEXT = 77
MEAN = (0.48145466, 0.4578275, 0.40821073)
STD = (0.26862954, 0.26130258, 0.27577711)


# ── Modèle ─────────────────────────────────────────────────────────────────────

class Attention(nn.Module):
    def __init__(self, width, heads):
        super().__init__()
        self.heads = heads
        self.in_proj = nn.Linear(width, 3 * heads * HEAD_DIM)
        self.out_proj = nn.Linear(heads * HEAD_DIM, width)

    def forward(self, x, mask=None):
        n, length, _ = x.shape
        q, k, v = self.in_proj(x).chunk(3, dim=-1)
        q = q.reshape(n, length, self.heads, HEAD_DIM).transpose(1, 2) * HEAD_DIM ** -0.5
        k = k.reshape(n, length, self.heads, HEAD_DIM).transpose(1, 2)
        v = v.reshape(n, length, self.heads, HEAD_DIM).transpose(1, 2)
        scores = q @ k.transpose(-1, -2)
        if mask is not None:
            scores = scores + mask
        out = scores.softmax(dim=-1) @ v
        return self.out_proj(out.transpose(1, 2).reshape(n, length, self.heads * HEAD_DIM))


class Block(nn.Module):
    """Bloc pré-normalisé ; attention ou MLP absents s'ils ont été entièrement élagués."""

    def __init__(self, width, heads, mlp_width):
        super().__init__()
        self.ln_1 = nn.LayerNorm(width) if heads else None
        self.attn = Attention(width, heads) if heads else None
        self.ln_2 = nn.LayerNorm(width) if mlp_width else None
        self.c_fc = nn.Linear(width, mlp_width) if mlp_width else None
        self.c_proj = nn.Linear(mlp_width, width) if mlp_width else None

    def forward(self, x, mask=None):
        if self.attn is not None:
            x = x + self.attn(self.ln_1(x), mask)
        if self.c_fc is not None:
            x = x + self.c_proj(F.gelu(self.c_fc(self.ln_2(x))))
        return x


def _blocks(sd, prefix):
    """Blocs déduits des poids : têtes = lignes de in_proj / (3 × 64), MLP = lignes de c_fc."""
    blocks, i = [], 0
    while any(key.startswith(f"{prefix}{i}.") for key in sd):
        p = f"{prefix}{i}."
        width = sd[p + "ln_2.weight"].shape[0] if p + "ln_2.weight" in sd else sd[p + "ln_1.weight"].shape[0]
        heads = sd[p + "attn.in_proj_weight"].shape[0] // (3 * HEAD_DIM) if p + "attn.in_proj_weight" in sd else 0
        mlp = sd[p + "mlp.c_fc.weight"].shape[0] if p + "mlp.c_fc.weight" in sd else 0
        block = Block(width, heads, mlp)
        state = {}
        for name, value in sd.items():
            if not name.startswith(p):
                continue
            local = name[len(p):].replace("attn.in_proj_weight", "attn.in_proj.weight")
            local = local.replace("attn.in_proj_bias", "attn.in_proj.bias").replace("mlp.", "")
            state[local] = value
        block.load_state_dict(state, strict=True)
        blocks.append(block)
        i += 1
    return nn.ModuleList(blocks)


class VisionTower(nn.Module):
    """ViT : la convolution d'entrée (pas = noyau) est écrite comme un produit matriciel sur
    les carreaux, ce qui la rend quantifiable comme les autres couches."""

    def __init__(self, sd):
        super().__init__()
        conv = sd["conv1.weight"]
        self.width = conv.shape[0]
        self.patch = conv.shape[-1]
        self.patch_weight = nn.Parameter(conv.reshape(self.width, -1).t().contiguous())
        self.class_embedding = nn.Parameter(sd["class_embedding"])
        self.positional_embedding = nn.Parameter(sd["positional_embedding"])
        self.ln_pre = nn.LayerNorm(self.width)
        self.ln_pre.load_state_dict({"weight": sd["ln_pre.weight"], "bias": sd["ln_pre.bias"]})
        self.blocks = _blocks(sd, "transformer.resblocks.")
        self.ln_post = nn.LayerNorm(self.width)
        self.ln_post.load_state_dict({"weight": sd["ln_post.weight"], "bias": sd["ln_post.bias"]})
        self.proj = nn.Parameter(sd["proj"])

    def forward(self, pixels):
        n, p = pixels.shape[0], self.patch
        grid = IMAGE_SIZE // p
        patches = pixels.reshape(n, 3, grid, p, grid, p).permute(0, 2, 4, 1, 3, 5)
        x = patches.reshape(n, grid * grid, 3 * p * p) @ self.patch_weight
        x = torch.cat([self.class_embedding.expand(n, 1, self.width), x], dim=1) + self.positional_embedding
        x = self.ln_pre(x)
        for block in self.blocks:
            x = block(x)
        return F.normalize(self.ln_post(x[:, 0]) @ self.proj, dim=-1)


class TextTower(nn.Module):
    def __init__(self, sd):
        super().__init__()
        self.token_embedding = nn.Parameter(sd["token_embedding.weight"])
        self.positional_embedding = nn.Parameter(sd["positional_embedding"])
        self.blocks = _blocks(sd, "transformer.resblocks.")
        width = self.token_embedding.shape[1]
        self.ln_final = nn.LayerNorm(width)
        self.ln_final.load_state_dict({"weight": sd["ln_final.weight"], "bias": sd["ln_final.bias"]})
        self.text_projection = nn.Parameter(sd["text_projection"])
        self.register_buffer("mask", torch.full((CONTEXT, CONTEXT), float("-inf")).triu(1), persistent=False)

    def forward(self, tokens):
        x = self.token_embedding[tokens] + self.positional_embedding
        for block in self.blocks:
            x = block(x, self.mask)
        x = self.ln_final(x)
        # Fin de texte : le jeton de plus grand identifiant de chaque séquence.
        eot = tokens.to(torch.float32).argmax(dim=-1)
        x = torch.gather(x, 1, eot.reshape(-1, 1, 1).expand(-1, 1, x.shape[-1])).squeeze(1)
        return F.normalize(x @ self.text_projection, dim=-1)


def load_tinyclip(path):
    """Charge un checkpoint TinyCLIP (poids seuls, aucune exécution de code) en float32."""
    checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    state = checkpoint.get("state_dict", checkpoint)
    vision, text = {}, {}
    for key, value in state.items():
        key = key.replace("module.", "")
        # Préfixes selon la génération du checkpoint (« _image_encoder. » ou « image_encoder_without_ddp. »).
        for prefix, target in (("image_encoder_without_ddp.visual.", vision), ("_image_encoder.visual.", vision),
                               ("text_encoder_without_ddp.", text), ("_text_encoder.", text)):
            if key.startswith(prefix):
                target[key[len(prefix):]] = value.float()
    return VisionTower(vision).eval(), TextTower(text).eval()


# ── Prétraitement d'image ──────────────────────────────────────────────────────

def _bicubic(x):
    a = -0.5
    x = abs(x)
    if x < 1.0:
        return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
    if x < 2.0:
        return (((x - 5.0) * x + 8.0) * x - 4.0) * a
    return 0.0


def resample_weights(in_size, in0, in1, out_size):
    """Coefficients de rééchantillonnage bicubique avec anticrénelage (calcul de Pillow) :
    le support du filtre s'élargit avec le facteur de réduction. Renvoie, pour chaque
    pixel de sortie, le premier pixel source et les poids normalisés."""
    scale = (in1 - in0) / out_size
    filter_scale = max(scale, 1.0)
    support = 2.0 * filter_scale
    starts, weights = [], []
    for xx in range(out_size):
        center = in0 + (xx + 0.5) * scale
        xmin = max(int(center - support + 0.5), 0)
        xmax = min(int(center + support + 0.5), in_size)
        w = [_bicubic((x + xmin - center + 0.5) / filter_scale) for x in range(xmax - xmin)]
        total = sum(w)
        starts.append(xmin)
        weights.append([v / total for v in w] if total != 0.0 else w)
    return starts, weights


def _resample_axis(src, starts, weights, axis):
    """Passe séparable sur un axe, valeurs bornées à [0, 255] comme une image 8 bits."""
    out_shape = list(src.shape)
    out_shape[axis] = len(starts)
    out = np.zeros(out_shape, dtype=np.float64)
    for i, (start, w) in enumerate(zip(starts, weights)):
        w = np.asarray(w, dtype=np.float64)
        if axis == 0:
            out[i] = np.tensordot(w, src[start:start + len(w)], axes=(0, 0))
        else:
            out[:, i] = np.tensordot(src[:, start:start + len(w)], w, axes=(1, 0))
    return np.clip(out, 0.0, 255.0)


def preprocess(rgb):
    """Image RVB uint8 [H, W, 3] → tenseur [1, 3, 224, 224] float32 normalisé CLIP.

    Carré central (équivalent de Resize(224) + CenterCrop(224)), réduit en bicubique
    avec anticrénelage, horizontalement puis verticalement."""
    h, w = rgb.shape[:2]
    side = min(w, h)
    x0, y0 = (w - side) / 2.0, (h - side) / 2.0
    src = rgb.astype(np.float64)
    xs, xw = resample_weights(w, x0, x0 + side, IMAGE_SIZE)
    ys, yw = resample_weights(h, y0, y0 + side, IMAGE_SIZE)
    out = _resample_axis(_resample_axis(src, xs, xw, axis=1), ys, yw, axis=0)
    out = (out / 255.0 - np.asarray(MEAN)) / np.asarray(STD)
    return out.transpose(2, 0, 1)[None].astype(np.float32)


# ── Tokeniseur BPE de CLIP ─────────────────────────────────────────────────────
# Portage fidèle de clip/simple_tokenizer.py (OpenAI, licence MIT), sans ftfy :
# l'application ne lui transmet que du texte déjà normalisé.

SOT, EOT = "<|startoftext|>", "<|endoftext|>"


@lru_cache()
def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, [chr(c) for c in cs]))


class ClipTokenizer:
    """[merges] : nombre de fusions BPE conservées (48 894 pour le vocabulaire complet).
    Un vocabulaire réduit garde les premières fusions (les plus fréquentes) : un mot dont
    la fusion manque est découpé en sous-mots connus du modèle."""

    FULL_MERGES = 49152 - 256 - 2

    def __init__(self, vocab_path, merges=FULL_MERGES):
        lines = gzip.open(vocab_path).read().decode("utf-8").split("\n")
        pairs = [tuple(line.split()) for line in lines[1:1 + merges]]
        byte_chars = list(bytes_to_unicode().values())
        vocab = byte_chars + [c + "</w>" for c in byte_chars] + ["".join(p) for p in pairs] + [SOT, EOT]
        self.encoder = {token: i for i, token in enumerate(vocab)}
        self.ranks = {pair: i for i, pair in enumerate(pairs)}
        self.sot, self.eot = self.encoder[SOT], self.encoder[EOT]
        self.vocab_size = len(vocab)
        self.byte_encoder = bytes_to_unicode()
        self.pattern = regex.compile(
            r"""<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            regex.IGNORECASE,
        )
        # Les jetons spéciaux écrits dans le texte restent entiers, comme dans l'original.
        self.cache = {SOT: (SOT,), EOT: (EOT,)}

    def bpe(self, token):
        if token in self.cache:
            return self.cache[token]
        word = tuple(token[:-1]) + (token[-1] + "</w>",)
        while len(word) > 1:
            pairs = {(word[i], word[i + 1]) for i in range(len(word) - 1)}
            best = min(pairs, key=lambda p: self.ranks.get(p, math.inf))
            if best not in self.ranks:
                break
            merged, i = [], 0
            while i < len(word):
                if i < len(word) - 1 and (word[i], word[i + 1]) == best:
                    merged.append(word[i] + word[i + 1])
                    i += 2
                else:
                    merged.append(word[i])
                    i += 1
            word = tuple(merged)
        self.cache[token] = word
        return word

    def encode(self, text):
        text = html.unescape(html.unescape(text)).strip()
        text = regex.sub(r"\s+", " ", text).strip().lower()
        ids = []
        for piece in self.pattern.findall(text):
            piece = "".join(self.byte_encoder[b] for b in piece.encode("utf-8"))
            ids.extend(self.encoder[t] for t in self.bpe(piece))
        return ids

    def tokenize(self, text):
        """Séquence de 77 identifiants : début, texte (tronqué), fin, zéros."""
        ids = [self.sot] + self.encode(text)[:CONTEXT - 2] + [self.eot]
        return np.asarray(ids + [0] * (CONTEXT - len(ids)), dtype=np.int64)[None]
