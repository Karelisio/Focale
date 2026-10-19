#!/usr/bin/env python3
"""Exporte TinyCLIP en deux modèles ONNX quantifiés pour la recherche en langage naturel.

Modèle : TinyCLIP (Microsoft, licence MIT), version embarquée TinyCLIP-ViT-8M-16-Text-3M-YFCC15M,
https://github.com/wkcn/TinyCLIP-model-zoo/releases/download/checkpoints/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M.pt
(TinyCLIP-auto-ViT-22M-32-Text-10M-LAION400M, plus précis mais 2,5 fois plus lourd, s'exporte de même).
Vocabulaire : bpe_simple_vocab_16e6.txt.gz (OpenAI, licence MIT),
https://raw.githubusercontent.com/openai/CLIP/main/clip/bpe_simple_vocab_16e6.txt.gz

Sorties (dossier --assets) :
  tinyclip_image.onnx  pixels float32 [1, 3, 224, 224] (prétraitement de clip_model.py)
                       → embedding float32 [1, 512], norme 1
  tinyclip_text.onnx   tokens int64 [1, 77] → embedding float32 [1, 512], norme 1
  clip_bpe_vocab.txt.gz  copie du vocabulaire BPE

Quantification : poids des produits matriciels sur 8 bits par blocs de 64 (MatMulNBits,
activations quantifiées par blocs au calcul). La table des jetons (49 408 lignes, plus de
la moitié des poids) est découpée : 8 bits par ligne pour les 20 512 jetons les plus
fréquents, 4 bits par blocs de 32 pour les autres, les trois colonnes aux valeurs
extrêmes restant sur 8 bits.

Contrôles : cosinus entre les empreintes torch (float32) et ONNX (quantifiées) sur des
images et des phrases d'essai (seuil 0,98), zéro-shot simple, et, avec --imagenet, exactitude
zéro-shot sur les images d'exemple d'ImageNet et calibrage des seuils de la recherche.

Références des tests de l'application (dossier --test-resources) : jetons du tokeniseur,
images de synthèse et empreintes attendues.

Usage :
  python3 export_clip.py --checkpoint TinyCLIP-ViT-8M-16-Text-3M-YFCC15M.pt \\
      --vocab bpe_simple_vocab_16e6.txt.gz --assets ../../app/src/main/assets/models \\
      --test-resources ../../app/src/test/resources/clip \\
      [--imagenet <dossier 0000.jpg…0999.jpg> --labels imagenet-simple-labels.json] [--photos <dossier>]
"""
import argparse
import json
import logging
import os
import shutil
import sys
import tempfile

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnx import TensorProto, helper, numpy_helper
from onnxruntime.quantization import QuantFormat
from onnxruntime.quantization.matmul_nbits_quantizer import DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clip_model as cm  # noqa: E402

OPSET = 18
WEIGHT_BLOCK = 64
INT8_TOKENS = 512 + 20000
EMBEDDING_BLOCK = 32
OUTLIER_COLUMNS = 3
MIN_COSINE = 0.98
# Seuils de la recherche dans l'application (SimilarityThreshold.Default).
SEARCH_MINIMUM, SEARCH_MARGIN = 0.37, 0.06


def _span(a, b):
    return list(range(a, b + 1))


# Concepts larges d'une recherche de galerie, par indices de classes ImageNet.
BROAD_CONCEPTS = {
    "a dog": _span(151, 268), "a cat": _span(281, 285), "a bird": _span(7, 24) + _span(80, 100) + _span(127, 146),
    "a snake": _span(52, 68), "a butterfly": _span(321, 326), "a monkey": _span(369, 384),
    "a car": [407, 436, 468, 511, 609, 627, 656, 661, 717, 751, 817],
    "a boat": [472, 484, 510, 554, 576, 625, 628, 724, 780, 814, 833, 871, 914], "a bus": [654, 779, 874],
    "a fruit": _span(948, 957), "a vegetable": _span(936, 945), "a bear": _span(294, 297), "a big cat": _span(286, 293),
    "the sea shore": [975, 976, 977, 978], "a mountain landscape": [970, 979, 980],
}

PHRASES = [
    "a photo of a dog on the beach", "a photo of a red car", "a photo of a birthday cake",
    "a photo of snowy mountains", "a photo of a cat sleeping on a sofa", "a photo of a sunset over the sea",
    "a photo of children playing in the snow", "a photo of a plate of pasta", "a photo of a bicycle",
    "a photo of the eiffel tower", "a photo of fireworks", "a photo of a flamingo", "a photo of a croissant",
    "a photo of a koala in a tree", "a photo of a kayak on a lake", "a photo of my grandmother",
    "a photo of a black and white dog", "a photo of a wedding", "a photo of a waterfall in the forest",
    "a photo of a screenshot", "a photo of people at a party", "a photo of a sailboat", "a photo of a hamster",
]

TOKENIZER_SAMPLES = [
    "a photo of a dog on the beach", "A Photo Of A DOG!", "the eiffel tower at night", "chien sur la plage",
    "Été 2023 à Saint-Malo", "l'enfant joue au ballon", "it's a cat, isn't it?", "we're they'll I'd you've",
    "rock'n'roll", "fish & chips", "12/07/2021 14h30", "co2 h2o 3.14", "a  lot   of\tspaces\n",
    "naïve café crème brûlée", "Ünïcödé Ωmega straße", "東京タワー", "🐶🏖️ beach day", "hello-world_foo.bar",
    "supercalifragilisticexpialidocious", "TinyCLIP ONNX MatMulNBits", "<|startoftext|> raw <|endoftext|>",
    "a " + "very " * 90 + "long caption",
]


# ── Export et quantification ───────────────────────────────────────────────────

def export(module, example, path, input_name):
    torch.onnx.export(module, (example,), path, input_names=[input_name], output_names=["embedding"],
                      opset_version=OPSET, do_constant_folding=True, dynamo=False)


def quantize_matmuls(model):
    config = DefaultWeightOnlyQuantConfig(block_size=WEIGHT_BLOCK, is_symmetric=True, accuracy_level=4, bits=8,
                                          quant_format=QuantFormat.QOperator, op_types_to_quantize=("MatMul",))
    quantizer = MatMulNBitsQuantizer(model, algo_config=config)
    quantizer.process()
    return quantizer.model.model


def int8_rows(table):
    scale = np.abs(table).max(axis=1) / 127.0
    scale[scale == 0] = 1.0
    codes = np.round(table / scale[:, None]).clip(-127, 127).astype(np.int8)
    return codes, scale.astype(np.float32)


def int4_blocks(table, block):
    """4 bits asymétriques par blocs, bornes réduites si l'erreur quadratique baisse."""
    rows, cols = table.shape
    padded = -(-cols // block) * block
    data = np.zeros((rows, padded), dtype=np.float64)
    data[:, :cols] = table
    blocks = data.reshape(rows, -1, block)
    lo = np.minimum(blocks.min(axis=2, keepdims=True), 0.0)
    hi = np.maximum(blocks.max(axis=2, keepdims=True), 0.0)
    best_codes = best_scale = best_zero = best_err = None
    for ratio in (1.0, 0.95, 0.9, 0.85, 0.8, 0.75):
        scale = (hi - lo) * ratio / 15.0
        scale[scale == 0] = 1.0
        zero = np.round(-lo * ratio / scale).clip(0, 15)
        codes = np.round(blocks / scale + zero).clip(0, 15)
        err = (((codes - zero) * scale - blocks) ** 2).sum(axis=2, keepdims=True)
        if best_err is None:
            best_codes, best_scale, best_zero, best_err = codes, scale, zero, err
        else:
            better = err < best_err
            best_codes = np.where(better, codes, best_codes)
            best_scale = np.where(better, scale, best_scale)
            best_zero = np.where(better, zero, best_zero)
            best_err = np.minimum(err, best_err)
    codes = best_codes.reshape(rows, padded).astype(np.uint8)
    packed = (codes[:, 0::2] | (codes[:, 1::2] << 4)).astype(np.uint8)
    return packed, best_scale[..., 0].astype(np.float16), best_zero[..., 0].astype(np.uint8)


def compress_token_embedding(model):
    """Remplace la table des jetons (Gather float32) par une table mixte 8 bits / 4 bits."""
    graph = model.graph
    init = next(i for i in graph.initializer if i.name == "token_embedding")
    table = numpy_helper.to_array(init).astype(np.float64)
    vocab, width = table.shape
    index = next(k for k, n in enumerate(graph.node) if n.op_type == "Gather" and n.input[0] == "token_embedding")
    node = graph.node[index]
    ids, out = node.input[1], node.output[0]

    frequent_codes, frequent_scale = int8_rows(table[:INT8_TOKENS])
    rare = table[INT8_TOKENS:]
    outliers = np.argsort(np.abs(table).max(axis=0))[-OUTLIER_COLUMNS:]
    outlier_codes, outlier_scale = int8_rows(rare[:, outliers].T)
    rare_main = rare.copy()
    rare_main[:, outliers] = 0.0
    packed, block_scale, block_zero = int4_blocks(rare_main, EMBEDDING_BLOCK)
    blocks = block_scale.shape[1]
    onehot = np.zeros((OUTLIER_COLUMNS, width), dtype=np.float32)
    onehot[np.arange(OUTLIER_COLUMNS), outliers] = 1.0

    def const(name, array):
        graph.initializer.append(numpy_helper.from_array(np.asarray(array), name))
        return name

    p = "tok_"
    graph.initializer.remove(init)
    c = {
        "split": const(p + "split", np.int64(INT8_TOKENS)),
        "last": const(p + "last", np.int64(INT8_TOKENS - 1)),
        "zero": const(p + "zero", np.int64(0)),
        "sixteen": const(p + "sixteen", np.int32(16)),
        "axis": const(p + "axis", np.asarray([-1], dtype=np.int64)),
        "f_codes": const(p + "frequent_codes", frequent_codes),
        "f_scale": const(p + "frequent_scale", frequent_scale),
        "r_packed": const(p + "rare_packed", packed),
        "r_scale": const(p + "rare_scale", block_scale),
        "r_zero": const(p + "rare_zero", block_zero),
        "o_codes": const(p + "outlier_codes", outlier_codes.T.copy()),
        "o_scale": const(p + "outlier_scale", outlier_scale),
        "onehot": const(p + "outlier_onehot", onehot),
        "blocks_shape": const(p + "blocks_shape", np.asarray([0, 0, blocks, EMBEDDING_BLOCK], dtype=np.int64)),
        "flat_shape": const(p + "flat_shape", np.asarray([0, 0, blocks * EMBEDDING_BLOCK], dtype=np.int64)),
        "pairs_shape": const(p + "pairs_shape", np.asarray([0, 0, blocks * EMBEDDING_BLOCK], dtype=np.int64)),
        "starts": const(p + "starts", np.asarray([0], dtype=np.int64)),
        "ends": const(p + "ends", np.asarray([width], dtype=np.int64)),
    }
    n = []

    def op(kind, inputs, name, **attrs):
        n.append(helper.make_node(kind, inputs, [p + name], name=p + name, **attrs))
        return p + name

    # Jetons fréquents : 8 bits, une échelle par ligne.
    is_frequent = op("Less", [ids, c["split"]], "is_frequent")
    f_ids = op("Min", [ids, c["last"]], "f_ids")
    f_rows = op("Cast", [op("Gather", [c["f_codes"], f_ids], "f_gather", axis=0)], "f_float", to=TensorProto.FLOAT)
    f_scale = op("Unsqueeze", [op("Gather", [c["f_scale"], f_ids], "f_scale_rows", axis=0), c["axis"]], "f_scale_col")
    frequent = op("Mul", [f_rows, f_scale], "frequent")
    # Jetons rares : 4 bits par blocs (deux codes par octet), plus les colonnes extrêmes.
    r_ids = op("Max", [op("Sub", [ids, c["split"]], "r_offset"), c["zero"]], "r_ids")
    bytes_ = op("Cast", [op("Gather", [c["r_packed"], r_ids], "r_gather", axis=0)], "r_int", to=TensorProto.INT32)
    high = op("Div", [bytes_, c["sixteen"]], "r_high")
    low = op("Sub", [bytes_, op("Mul", [high, c["sixteen"]], "r_high16")], "r_low")
    pairs = op("Concat", [op("Unsqueeze", [low, c["axis"]], "r_low1"), op("Unsqueeze", [high, c["axis"]], "r_high1")],
               "r_pairs", axis=-1)
    codes = op("Cast", [op("Reshape", [pairs, c["pairs_shape"]], "r_codes_int")], "r_codes", to=TensorProto.FLOAT)
    codes = op("Reshape", [codes, c["blocks_shape"]], "r_blocks")
    zero = op("Cast", [op("Gather", [c["r_zero"], r_ids], "r_zero_rows", axis=0)], "r_zero_f", to=TensorProto.FLOAT)
    scale = op("Cast", [op("Gather", [c["r_scale"], r_ids], "r_scale_rows", axis=0)], "r_scale_f", to=TensorProto.FLOAT)
    values = op("Mul", [op("Sub", [codes, op("Unsqueeze", [zero, c["axis"]], "r_zero_col")], "r_centered"),
                        op("Unsqueeze", [scale, c["axis"]], "r_scale_col")], "r_values")
    main = op("Slice", [op("Reshape", [values, c["flat_shape"]], "r_flat"), c["starts"], c["ends"], c["axis"]], "r_main")
    o_rows = op("Mul", [op("Cast", [op("Gather", [c["o_codes"], r_ids], "o_gather", axis=0)], "o_float",
                           to=TensorProto.FLOAT), c["o_scale"]], "o_values")
    rare_rows = op("Add", [main, op("MatMul", [o_rows, c["onehot"]], "o_spread")], "rare")
    n.append(helper.make_node("Where", [op("Unsqueeze", [is_frequent, c["axis"]], "is_frequent_col"), frequent, rare_rows],
                              [out], name=p + "select"))
    del graph.node[index]
    for k, new in enumerate(n):
        graph.node.insert(index + k, new)
    return model


def finalize(model, path):
    model = onnx.shape_inference.infer_shapes(model)
    onnx.checker.check_model(model)
    onnx.save(model, path)


def session(path):
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    return ort.InferenceSession(path, options, providers=["CPUExecutionProvider"])


def run(sess, batch):
    name = sess.get_inputs()[0].name
    return np.concatenate([sess.run(None, {name: batch[i:i + 1]})[0] for i in range(len(batch))])


# ── Images d'essai ─────────────────────────────────────────────────────────────

def synthetic_images():
    """Images de synthèse déterministes (créées ici, sans droits) : couleurs dominantes nettes,
    texture douce (PNG compact), formats variés pour exercer recadrage, réduction et
    agrandissement."""
    images = {}

    def field(h, w, color):
        yy, xx = np.mgrid[:h, :w].astype(np.float64)
        texture = 1.0 + 0.12 * np.sin(xx / 7.0) * np.cos(yy / 5.0) - 0.15 * (yy / h - 0.5)
        return np.clip(np.asarray(color, dtype=np.float64) * texture[..., None], 0, 255).astype(np.uint8)

    images["red"] = field(200, 300, (205, 30, 35))
    images["green"] = field(300, 200, (40, 160, 55))
    images["blue"] = field(97, 129, (35, 70, 200))
    sky = field(240, 320, (120, 170, 235))
    yy, xx = np.mgrid[:240, :320]
    sky[(xx - 230) ** 2 + (yy - 70) ** 2 < 32 ** 2] = (255, 225, 90)
    sky[180:] = field(60, 320, (60, 140, 50))
    images["landscape"] = sky
    board = ((xx // 40 + yy // 40) % 2 * 255).astype(np.uint8)
    images["checkerboard"] = np.repeat(board[..., None], 3, axis=2)
    return images


def load_rgb(path):
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"))


# ── Programme ──────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--vocab", required=True)
    parser.add_argument("--assets", required=True)
    parser.add_argument("--test-resources", required=True)
    parser.add_argument("--imagenet", help="images d'exemple ImageNet nommées 0000.jpg … 0999.jpg (indice de classe)")
    parser.add_argument("--labels", help="noms des 1000 classes (JSON), avec --imagenet")
    parser.add_argument("--photos", help="dossier de photos supplémentaires pour les contrôles")
    parser.add_argument("--work", help="dossier de travail (temporaire par défaut)")
    parser.add_argument("--references-only", action="store_true",
                        help="régénère seulement les références des tests, avec les modèles déjà exportés")
    args = parser.parse_args()
    logging.disable(logging.WARNING)
    torch.set_num_threads(max(1, (os.cpu_count() or 2) - 1))

    os.makedirs(args.assets, exist_ok=True)
    os.makedirs(args.test_resources, exist_ok=True)
    vision, text = cm.load_tinyclip(args.checkpoint)
    tokenizer = cm.ClipTokenizer(args.vocab)
    print(f"vision : largeur {vision.width}, {len(vision.blocks)} blocs ; texte : vocabulaire {tokenizer.vocab_size}")

    image_out = os.path.join(args.assets, "tinyclip_image.onnx")
    text_out = os.path.join(args.assets, "tinyclip_text.onnx")
    if args.references_only:
        write_references(args.test_resources, tokenizer, synthetic_images(), vision, text, session(image_out), session(text_out))
        return
    work = args.work or tempfile.mkdtemp(prefix="tinyclip-")
    os.makedirs(work, exist_ok=True)
    image_fp32, text_fp32 = os.path.join(work, "image_fp32.onnx"), os.path.join(work, "text_fp32.onnx")
    export(vision, torch.zeros(1, 3, cm.IMAGE_SIZE, cm.IMAGE_SIZE), image_fp32, "pixels")
    export(text, torch.from_numpy(tokenizer.tokenize("a photo of a dog")), text_fp32, "tokens")
    finalize(quantize_matmuls(onnx.load(image_fp32)), image_out)
    finalize(compress_token_embedding(quantize_matmuls(onnx.load(text_fp32))), text_out)
    shutil.copyfile(args.vocab, os.path.join(args.assets, "clip_bpe_vocab.txt.gz"))
    sizes = {name: os.path.getsize(os.path.join(args.assets, name)) for name in sorted(os.listdir(args.assets))}
    for name, size in sizes.items():
        print(f"{name} : {size / 1e6:.1f} Mo")
    print(f"total : {sum(sizes.values()) / 1e6:.1f} Mo")

    image_sess, text_sess = session(image_out), session(text_out)

    # Images : synthèse, photos et échantillon d'ImageNet.
    synthetic = synthetic_images()
    named = dict(synthetic)
    if args.photos:
        for name in sorted(os.listdir(args.photos)):
            named["photo:" + name] = load_rgb(os.path.join(args.photos, name))
    if args.imagenet:
        for i in range(0, 1000, 20):
            named[f"imagenet:{i:04d}"] = load_rgb(os.path.join(args.imagenet, f"{i:04d}.jpg"))
    pixels = np.concatenate([cm.preprocess(img) for img in named.values()])
    with torch.no_grad():
        ref_images = vision(torch.from_numpy(pixels)).numpy()
    int8_images = run(image_sess, pixels)
    cos_images = (ref_images * int8_images).sum(axis=1)
    worst = list(named)[int(np.argmin(cos_images))]
    print(f"images ({len(named)}) : cosinus torch/ONNX min {cos_images.min():.4f} ({worst}), moyenne {cos_images.mean():.4f}")

    phrases = PHRASES + ([f"a photo of a {label}." for label in json.load(open(args.labels))] if args.labels else [])
    tokens = np.concatenate([tokenizer.tokenize(p) for p in phrases])
    with torch.no_grad():
        ref_texts = torch.cat([text(torch.from_numpy(tokens[i:i + 100])) for i in range(0, len(tokens), 100)]).numpy()
    int8_texts = run(text_sess, tokens)
    cos_texts = (ref_texts * int8_texts).sum(axis=1)
    print(f"phrases ({len(phrases)}) : cosinus torch/ONNX min {cos_texts.min():.4f} "
          f"({phrases[int(np.argmin(cos_texts))]!r}), moyenne {cos_texts.mean():.4f}")
    assert cos_images.min() > MIN_COSINE and cos_texts.min() > MIN_COSINE, "écart de quantification trop grand"

    # Zéro-shot simple sur les images de synthèse.
    colors = ["red", "green", "blue"]
    prompts = [f"a photo of something {c}" for c in colors]
    color_texts = run(text_sess, np.concatenate([tokenizer.tokenize(p) for p in prompts]))
    color_images = run(image_sess, np.concatenate([cm.preprocess(synthetic[c]) for c in colors]))
    picked = [colors[i] for i in np.argmax(color_images @ color_texts.T, axis=1)]
    print(f"zéro-shot couleurs : {picked}")
    assert picked == colors, "zéro-shot des couleurs incorrect"

    if args.imagenet and args.labels:
        evaluate_imagenet(args, vision, image_sess, text_sess, tokenizer, ref_texts[len(PHRASES):], int8_texts[len(PHRASES):])

    write_references(args.test_resources, tokenizer, synthetic, vision, text, image_sess, text_sess)
    if not args.work:
        shutil.rmtree(work, ignore_errors=True)


def evaluate_imagenet(args, vision, image_sess, text_sess, tokenizer, ref_texts, int8_texts):
    """Exactitude zéro-shot (une image par classe) et distribution des scores pour le calibrage."""
    pixels = [cm.preprocess(load_rgb(os.path.join(args.imagenet, f"{i:04d}.jpg"))) for i in range(1000)]
    with torch.no_grad():
        ref_images = np.concatenate([vision(torch.from_numpy(np.concatenate(pixels[i:i + 50]))).numpy()
                                     for i in range(0, 1000, 50)])
    int8_images = run(image_sess, np.concatenate(pixels))
    truth = np.arange(1000)
    for tag, images, texts in (("torch float32", ref_images, ref_texts), ("ONNX quantifié", int8_images, int8_texts)):
        scores = images @ texts.T
        top5 = np.argsort(-scores, axis=1)[:, :5]
        print(f"ImageNet zéro-shot {tag} : top-1 {np.mean(top5[:, 0] == truth):.3f}, "
              f"top-5 {np.mean([t in row for t, row in zip(truth, top5)]):.3f}")
    # Calibrage, dans le sens de la recherche : une phrase, toutes les images.
    scores = int8_texts @ int8_images.T
    positive = scores[truth, truth]
    negatives = scores.copy()
    negatives[truth, truth] = -1
    best_negative = negatives.max(axis=1)
    rank_ok = positive > best_negative
    gap = scores.max(axis=1) - positive
    print("scores positifs : " + " ".join(f"p{q}={np.percentile(positive, q):.3f}" for q in (5, 10, 25, 50)))
    print("meilleur négatif : " + " ".join(f"p{q}={np.percentile(best_negative, q):.3f}" for q in (50, 75, 90, 95)))
    print("négatifs : " + " ".join(f"p{q}={np.percentile(negatives[negatives > -1], q):.3f}" for q in (50, 99, 99.9)))
    print(f"positif premier : {rank_ok.mean():.3f} ; écart au meilleur des positifs non premiers : "
          + " ".join(f"p{q}={np.percentile(gap[~rank_ok], q):.3f}" for q in (25, 50, 75, 90)))
    for q in (97, 98, 98.5, 99, 99.5):
        minimum = np.percentile(negatives[negatives > -1], q)
        kept = np.mean(positive >= minimum)
        noise = np.mean((negatives >= minimum).sum(axis=1))
        print(f"seuil {minimum:.3f} (p{q} des négatifs) : positifs gardés {kept:.3f}, "
              f"négatifs gardés par phrase {noise:.1f}")
    # Concepts larges : précision moyenne, puis précision et rappel avec les seuils de l'application.
    prompts = np.concatenate([tokenizer.tokenize(f"a photo of {concept}") for concept in BROAD_CONCEPTS])
    concept_texts = run(text_sess, prompts)
    aps, precisions, recalls = [], [], []
    for k, indices in enumerate(BROAD_CONCEPTS.values()):
        relevant = np.zeros(1000, dtype=bool)
        relevant[indices] = True
        concept_scores = int8_images @ concept_texts[k]
        hits = relevant[np.argsort(-concept_scores)]
        aps.append(float((np.cumsum(hits) / np.arange(1, 1001) * hits).sum() / hits.sum()))
        picked = concept_scores >= max(SEARCH_MINIMUM, concept_scores.max() - SEARCH_MARGIN)
        precisions.append(relevant[picked].mean() if picked.any() else 0.0)
        recalls.append(relevant[picked].sum() / relevant.sum())
    print(f"concepts larges ({len(aps)}) : mAP {np.mean(aps):.3f} ; seuils {SEARCH_MINIMUM}/{SEARCH_MARGIN} : "
          f"précision {np.mean(precisions):.3f}, rappel {np.mean(recalls):.3f}")


def write_references(folder, tokenizer, synthetic, vision, text, image_sess, text_sess):
    """Références des tests JVM : jetons, images PNG de synthèse et empreintes attendues."""
    with open(os.path.join(folder, "tokenizer_reference.tsv"), "w", encoding="utf-8") as out:
        out.write("# texte (échappé JSON)\tidentifiants (début, texte, fin)\n")
        for sample in TOKENIZER_SAMPLES:
            ids = [tokenizer.sot] + tokenizer.encode(sample)[:cm.CONTEXT - 2] + [tokenizer.eot]
            out.write(json.dumps(sample, ensure_ascii=False) + "\t" + " ".join(map(str, ids)) + "\n")
    reference = {"images": {}, "texts": {}}
    for name in ("red", "green", "blue", "landscape", "checkerboard"):
        Image.fromarray(synthetic[name]).save(os.path.join(folder, f"{name}.png"), optimize=True)
        with Image.open(os.path.join(folder, f"{name}.png")) as image:
            rgb = np.asarray(image.convert("RGB"))
        pixels = cm.preprocess(rgb)
        with torch.no_grad():
            torch_vec = vision(torch.from_numpy(pixels)).numpy()[0]
        onnx_vec = run(image_sess, pixels)[0]
        reference["images"][name] = {
            "onnx": [round(float(v), 6) for v in onnx_vec],
            "torch": [round(float(v), 6) for v in torch_vec],
            "pixels_sum": round(float(pixels.astype(np.float64).sum()), 3),
            "pixels_samples": {str(i): float(pixels.reshape(-1)[i]) for i in range(0, pixels.size, 4999)},
        }
    for prompt in ("a photo of something red", "a photo of something green", "a photo of something blue",
                   "a photo of a dog on the beach", "a photo of a checkerboard"):
        tokens = tokenizer.tokenize(prompt)
        with torch.no_grad():
            torch_vec = text(torch.from_numpy(tokens)).numpy()[0]
        onnx_vec = run(text_sess, tokens)[0]
        reference["texts"][prompt] = {
            "onnx": [round(float(v), 6) for v in onnx_vec],
            "torch": [round(float(v), 6) for v in torch_vec],
        }
    with open(os.path.join(folder, "embedding_reference.json"), "w", encoding="utf-8") as out:
        json.dump(reference, out, separators=(",", ":"))
    print(f"références écrites dans {folder}")


if __name__ == "__main__":
    main()
