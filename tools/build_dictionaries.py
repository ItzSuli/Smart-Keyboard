#!/usr/bin/env python3
"""Builds the on-device language packs shipped in app/src/main/assets/dict/.

Sources (downloaded into tools/.cache/ on first run):
  * Word frequencies: hermitdave/FrequencyWords, 2018 OpenSubtitles lists (CC-BY-SA 4.0).
    Subtitles are everyday spoken language, so slang and swear words are already in there.
  * Context (bigrams / trigrams) and capitalisation: Tatoeba sentences (CC-BY 2.0 FR).
  * Curated slang / swear words: tools/wordlists/<lang>.txt (this repo).

Output format (gzip, big-endian), read by engine/.../BinaryDictionary.kt:
  magic 'SKD2', u8 version
  u16+utf8  language code
  u32 nWords, then per word: u8 byteLen, utf8 bytes (display casing), u8 q
  u32 nBigramHeads, then per head: u16 headId (0xFFFF = sentence start), u8 k, k * (u16 id, u8 q)
  u32 nTrigramHeads, then per head: u16 id1, u16 id2, u8 k, k * (u16 id, u8 q)
where q = round(-log10(p) * 16) clamped to 0..255.

Also writes app/src/main/assets/emoji/emoji.txt from the Unicode emoji-test.txt.
"""
import bz2
import collections
import gzip
import math
import os
import re
import statistics
import struct
import sys
import unicodedata
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, "tools", ".cache")
OUT_DICT = os.path.join(ROOT, "app", "src", "main", "assets", "dict")
OUT_EMOJI = os.path.join(ROOT, "app", "src", "main", "assets", "emoji")

FW_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{l}/{l}_full.txt"
TATOEBA_URL = "https://downloads.tatoeba.org/exports/per_language/{t}/{t}_sentences.tsv.bz2"
EMOJI_URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"

# size: vocabulary size (ids must stay below 0xFFFF).
# singles: one-letter words that are real words (everything else one-letter is a fragment).
# apostrophe: keep whole apostrophe tokens (contractions) from Tatoeba.
# elisions: prefixes split off at runtime (French/Italian l', d', ...); not stored as words.
LANGS = {
    "en": dict(tatoeba="eng", script="LATIN", size=60000, singles={"a", "i"}, apostrophe=True),
    "de": dict(tatoeba="deu", script="LATIN", size=62000, singles=set(), apostrophe=True),
    "ar": dict(tatoeba="ara", script="ARABIC", size=65000, singles={"و"}, apostrophe=False),
    "fr": dict(tatoeba="fra", script="LATIN", size=60000, singles={"a", "à", "y", "ô"}, apostrophe=True,
               elisions=["jusqu'", "lorsqu'", "puisqu'", "quoiqu'", "qu'", "l'", "d'", "j'", "n'", "s'", "c'", "m'", "t'"]),
    "es": dict(tatoeba="spa", script="LATIN", size=60000, singles={"a", "e", "o", "u", "y"}, apostrophe=False),
    "it": dict(tatoeba="ita", script="LATIN", size=60000, singles={"a", "e", "è", "i", "o"}, apostrophe=True,
               elisions=["dell'", "nell'", "all'", "dall'", "sull'", "un'", "quest'", "quell'", "l'", "c'", "d'", "m'", "t'", "s'", "v'"]),
    "pt": dict(tatoeba="por", script="LATIN", size=60000, singles={"a", "e", "o", "à", "é"}, apostrophe=False),
    "tr": dict(tatoeba="tur", script="LATIN", size=62000, singles={"o"}, apostrophe=False),
    "nl": dict(tatoeba="nld", script="LATIN", size=60000, singles={"u"}, apostrophe=True),
    "ru": dict(tatoeba="rus", script="CYRILLIC", size=62000, singles={"а", "в", "и", "к", "о", "с", "у", "я"}, apostrophe=False),
    "pl": dict(tatoeba="pol", script="LATIN", size=60000, singles={"a", "i", "o", "u", "w", "z"}, apostrophe=False),
}

# Tatoeba is full of example sentences about "Tom" and "Mary"; keep them out of the context model.
NGRAM_STOP = {"tom", "mary", "maria", "marie", "sami", "ziri", "yanni", "rima", "layla", "skura", "mennad",
              "baya", "fadil", "salima", "algeria", "boston", "australia", "tomás", "maría",
              "том", "мэри", "توم", "ماري", "سامي", "ليلى"}


def is_stop(token):
    return token.split("'")[0] in NGRAM_STOP
ARABIC_MARKS = re.compile("[ؐ-ًؚ-ٰٟۖ-ۭـ]")
TOKEN = re.compile(r"[^\W\d_]+(?:['’\-][^\W\d_]+)*", re.UNICODE)
SENTENCE_BREAK = re.compile(r"[.!?;:…\"«»“”„()\[\]]+")

BIGRAM_K, BIGRAM_MIN = 24, 2
TRIGRAM_K, TRIGRAM_MIN, TRIGRAM_HEAD_MIN = 10, 3, 6


def fetch(url, name):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        print(f"  downloading {url}", file=sys.stderr)
        tmp = path + ".part"
        urllib.request.urlretrieve(url, tmp)
        os.replace(tmp, path)
    return path


def quantize(p):
    if p <= 0:
        return 255
    return max(0, min(255, int(round(-math.log10(p) * 16))))


def normalize(text, lang):
    text = unicodedata.normalize("NFC", text).replace("’", "'").replace("ʼ", "'")
    if lang == "ar":
        text = ARABIC_MARKS.sub("", text)
    return text


def lower(word, lang):
    if lang == "tr":
        word = word.replace("I", "ı").replace("İ", "i")
    return word.lower()


def valid_word(word, cfg):
    script = cfg["script"]
    letters = [c for c in word if c not in "'-"]
    if not letters or word[0] in "'-" or word[-1] in "'-":
        return False
    if "--" in word or "''" in word:
        return False
    for c in letters:
        if not unicodedata.category(c).startswith("L"):
            return False
        try:
            if script not in unicodedata.name(c):
                return False
        except ValueError:
            return False
    if len(letters) == 1 and word not in cfg["singles"]:
        return False
    return True


def strip_elision(token, cfg):
    for prefix in cfg.get("elisions", []):
        if token.startswith(prefix) and len(token) > len(prefix):
            return token[len(prefix):]
    return token


def load_frequency_words(lang, cfg):
    path = fetch(FW_URL.format(l=lang), f"fw_{lang}.txt")
    counts = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").rsplit(" ", 1)
            if len(parts) != 2:
                continue
            word, count = normalize(parts[0], lang), int(parts[1])
            if count < 3:
                break
            word = lower(word, lang)
            if valid_word(word, cfg) and "'" not in word:
                counts[word] = counts.get(word, 0) + count
    return counts


def read_tatoeba(lang, cfg):
    """Returns (token lists per sentence chunk, casing counters)."""
    path = fetch(TATOEBA_URL.format(t=cfg["tatoeba"]), f"tatoeba_{cfg['tatoeba']}.tsv.bz2")
    chunks = []
    casing = collections.defaultdict(collections.Counter)
    with bz2.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            text = normalize(parts[2], lang)
            if text.isupper():
                continue
            for piece in SENTENCE_BREAK.split(text):
                tokens = TOKEN.findall(piece)
                if not tokens:
                    continue
                chunk = []
                elided_start = False
                for i, tok in enumerate(tokens):
                    low = lower(tok, lang)
                    if cfg.get("elisions"):
                        stripped = strip_elision(low, cfg)
                        if stripped != low:
                            tok = tok[len(tok) - len(stripped):]
                            low = stripped
                            elided_start = elided_start or i == 0
                    if i > 0:
                        casing[low][tok] += 1
                    chunk.append(low)
                # "J'ai ..." must not teach that sentences start with "ai".
                chunks.append((chunk, elided_start))
    return chunks, casing


def load_extra(lang):
    path = os.path.join(ROOT, "tools", "wordlists", f"{lang}.txt")
    words = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                for w in line.split():
                    words.append(lower(normalize(w, lang), lang))
    return words


def build_language(lang, cfg):
    print(f"[{lang}] reading sources", file=sys.stderr)
    fw = load_frequency_words(lang, cfg)
    chunks, casing = read_tatoeba(lang, cfg)

    tat = collections.Counter()
    for chunk, _ in chunks:
        tat.update(chunk)

    # Bring Tatoeba counts onto the FrequencyWords scale using shared common words.
    common = [w for w, _ in tat.most_common(3000) if w in fw and fw[w] > 0]
    scale = statistics.median(fw[w] / tat[w] for w in common) if common else 1.0

    counts = dict(fw)
    if cfg["apostrophe"]:
        for w, c in tat.items():
            if "'" in w and c >= 5 and valid_word(w, cfg) and not is_stop(w):
                counts[w] = max(counts.get(w, 0), int(c * scale))
        if lang == "en":
            # FrequencyWords splits contractions ("don" + "'t"); drop the leftover halves.
            for frag in ["don", "isn", "doesn", "didn", "wasn", "aren", "couldn", "wouldn", "shouldn",
                         "hasn", "haven", "hadn", "weren", "ll", "ve", "re", "mustn", "needn"]:
                counts.pop(frag, None)

    # Rare words that only appear in subtitles are mostly typos and name soup.
    ranked = sorted(counts.items(), key=lambda kv: -kv[1])
    keep_rank = 25000 if lang != "ar" else 10**9
    vocab = []
    for rank, (w, c) in enumerate(ranked):
        if rank < keep_rank or tat.get(w, 0) > 0:
            vocab.append((w, c))
        if len(vocab) >= cfg["size"]:
            break

    # Curated slang/swear words get at least the frequency of the ~3000th word.
    floor = vocab[min(3000, len(vocab) - 1)][1]
    present = {w: i for i, (w, _) in enumerate(vocab)}
    for w in load_extra(lang):
        if not valid_word(w, dict(cfg, singles=cfg["singles"] | {w})):
            continue
        if w in present:
            i = present[w]
            vocab[i] = (w, max(vocab[i][1], floor))
        else:
            present[w] = len(vocab)
            vocab.append((w, floor))
    vocab.sort(key=lambda kv: -kv[1])
    vocab = vocab[: min(len(vocab), 0xFFFE)]

    total = float(sum(c for _, c in vocab))
    ids = {w: i for i, (w, _) in enumerate(vocab)}

    curated = set(load_extra(lang))

    def display(w):
        if w in curated and lang != "de":
            return w  # slang is written the way people type it
        variants = casing.get(w)
        if lang == "en" and (w == "i" or w.startswith("i'")):
            return "I" + w[1:]
        if not variants:
            return w
        form, n = variants.most_common(1)[0]
        if form.isupper() and len(form) > 1:
            return w  # "OK", "BC": chat writes these lowercase
        need = 3 if lang == "de" else 5
        if form != w and n >= need and n / sum(variants.values()) >= 0.6:
            return form
        return w

    # Context counts.
    START = 0xFFFF
    uni_ctx = collections.Counter()
    bi = collections.Counter()
    tri = collections.Counter()
    for chunk, elided_start in chunks:
        seq = [-1 if elided_start else START] + [-1 if is_stop(t) else ids.get(t, -1) for t in chunk]
        for i in range(1, len(seq)):
            a, b = seq[i - 1], seq[i]
            if b < 0 or a < 0:
                continue
            bi[(a, b)] += 1
            uni_ctx[a] += 1
            if i >= 2 and seq[i - 2] >= 0:
                tri[(seq[i - 2], a, b)] += 1

    heads = collections.defaultdict(list)
    for (a, b), c in bi.items():
        if c >= BIGRAM_MIN:
            heads[a].append((c, b))
    bigram_table = []
    for a, lst in heads.items():
        lst.sort(reverse=True)
        bigram_table.append((a, [(b, quantize(c / uni_ctx[a])) for c, b in lst[:BIGRAM_K]]))
    bigram_table.sort()

    pair_total = collections.Counter()
    for (a, b, _), c in tri.items():
        pair_total[(a, b)] += c
    tri_heads = collections.defaultdict(list)
    for (a, b, d), c in tri.items():
        if c >= TRIGRAM_MIN and pair_total[(a, b)] >= TRIGRAM_HEAD_MIN:
            tri_heads[(a, b)].append((c, d))
    trigram_table = []
    for (a, b), lst in tri_heads.items():
        lst.sort(reverse=True)
        trigram_table.append((a, b, [(d, quantize(c / pair_total[(a, b)])) for c, d in lst[:TRIGRAM_K]]))
    trigram_table.sort()

    os.makedirs(OUT_DICT, exist_ok=True)
    out = os.path.join(OUT_DICT, f"{lang}.dict")
    with gzip.open(out, "wb", compresslevel=9) as f:
        f.write(b"SKD2")
        f.write(struct.pack(">B", 1))
        code = lang.encode("utf-8")
        f.write(struct.pack(">H", len(code)) + code)
        f.write(struct.pack(">I", len(vocab)))
        for w, c in vocab:
            b = display(w).encode("utf-8")
            f.write(struct.pack(">B", len(b)) + b + struct.pack(">B", quantize(c / total)))
        f.write(struct.pack(">I", len(bigram_table)))
        for a, lst in bigram_table:
            f.write(struct.pack(">HB", a, len(lst)))
            for b, q in lst:
                f.write(struct.pack(">HB", b, q))
        f.write(struct.pack(">I", len(trigram_table)))
        for a, b, lst in trigram_table:
            f.write(struct.pack(">HHB", a, b, len(lst)))
            for d, q in lst:
                f.write(struct.pack(">HB", d, q))
    size = os.path.getsize(out)
    n_bi = sum(len(l) for _, l in bigram_table)
    n_tri = sum(len(l) for _, _, l in trigram_table)
    print(f"[{lang}] {len(vocab)} words, {n_bi} bigrams, {n_tri} trigrams -> {size / 1024:.0f} KiB", file=sys.stderr)


def build_emoji():
    path = fetch(EMOJI_URL, "emoji-test.txt")
    groups = collections.OrderedDict()
    base_of = {}
    group = None
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("# group:"):
                group = line.split(":", 1)[1].strip()
                continue
            if not line.strip() or line.startswith("#") or group == "Component":
                continue
            data, _, comment = line.partition("#")
            if "fully-qualified" not in data:
                continue
            m = re.match(r"\s*(\S+)\s+E(\d+\.\d+)\s+(.*)", comment)
            if not m:
                continue
            emoji, version, name = m.group(1), m.group(2), m.group(3).strip()
            if ": " in name and "skin tone" in name.split(": ", 1)[1]:
                base_name = name.split(": ", 1)[0]
                if base_name in base_of:
                    base_of[base_name][2].append(emoji)
                continue
            entry = [emoji, version, [], name]
            groups.setdefault(group, []).append(entry)
            base_of[name] = entry
    os.makedirs(OUT_EMOJI, exist_ok=True)
    with open(os.path.join(OUT_EMOJI, "emoji.txt"), "w", encoding="utf-8") as f:
        for g, entries in groups.items():
            f.write(f"#{g}\n")
            for emoji, version, variants, name in entries:
                f.write(f"{emoji}\t{version}\t{','.join(variants)}\t{name}\n")
    print(f"[emoji] {sum(len(e) for e in groups.values())} emoji in {len(groups)} groups", file=sys.stderr)


def main():
    wanted = sys.argv[1:] or list(LANGS) + ["emoji"]
    for lang in wanted:
        if lang == "emoji":
            build_emoji()
        else:
            build_language(lang, LANGS[lang])


if __name__ == "__main__":
    main()
