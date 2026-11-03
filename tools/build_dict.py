#!/usr/bin/env python3
"""
Build Mixboard's dictionary assets from open-source data.

Outputs (app/src/main/assets/):
  quick.tsv   速成 code -> candidate chars, most common first
  assoc.tsv   char -> 聯想 suffixes, Cantonese-friendly order
  en2zh.tsv   english word -> Traditional (HK) Chinese translations

Sources (downloaded into tools/cache on first run):
  rime-cangjie   cangjie5.base.dict.yaml        Cangjie 5 codes
  rime-essay     essay.txt                      word frequencies (Traditional)
  rime-cantonese jyut6ping3.words.dict.yaml     Cantonese vocabulary
  ECDICT         ecdict.csv                     English -> Chinese
  HKCanCor       via pycantonese                spoken HK Cantonese word frequencies

Requires: pip install opencc pycantonese
"""
import csv
import os
import re
import sys
import urllib.request
from collections import defaultdict

import opencc
import pycantonese

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets")

SOURCES = {
    "cangjie5.base.dict.yaml": "https://raw.githubusercontent.com/rime/rime-cangjie/master/cangjie5.base.dict.yaml",
    "essay.txt": "https://raw.githubusercontent.com/rime/rime-essay/master/essay.txt",
    "jyut6ping3.words.dict.yaml": "https://raw.githubusercontent.com/rime/rime-cantonese/main/jyut6ping3.words.dict.yaml",
    "ecdict.csv": "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv",
}

# Characters that mark a word as colloquial Cantonese. Cantonese-dictionary words
# containing these (but missing from both corpora) still get a fair 聯想 score.
COLLOQUIAL = set(
    "嘅啲咗佢喺嘢咁冇嚟嗰哋乜咩噉係俾畀諗睇攞揾搵㗎啦囉喇呀嘛唔嘥嬲攰瞓郁咪啱嗌"
    "嚿掂掟揸撳㩒冚哂晒喎噃吖啩咋嘞嚹靚嘈揼劏氹黐餸嗮識搞傾食飲返"
)
CANTO_WEIGHT = 2500      # per Cantonese-dictionary word containing a char (char ranking)
HK_CHAR_WEIGHT = 600     # per HKCanCor occurrence of a char (char ranking)
HK_WORD_WEIGHT = 1200    # per HKCanCor occurrence of a word (聯想 ranking); puts 我哋 above 我們
ASSOC_MAX = 40           # 聯想 suffixes kept per char
QUICK_MAX = 160          # candidates kept per 速成 code
EN_MAX = 8               # translations kept per English word

s2hk = opencc.OpenCC("s2hk")

# rime-essay and OpenCC use some forms HK keyboards don't produce. Map them to the
# Big5 forms that Windows 速成 users expect (為 not 爲, 說 not 説).
HK_FORMS = str.maketrans({
    "爲": "為", "眞": "真", "僞": "偽", "衞": "衛", "敎": "教", "峯": "峰", "羣": "群",
    "綫": "線", "啓": "啟", "鷄": "雞", "牀": "床", "衆": "眾", "説": "說", "脱": "脫",
    "悦": "悅", "鋭": "銳", "閲": "閱", "税": "稅", "兑": "兌", "蜕": "蛻", "䝼": "貢",
})


def hk(text):
    return text.translate(HK_FORMS)


def fetch(name):
    path = os.path.join(CACHE, name)
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        print(f"downloading {name} ...", file=sys.stderr)
        urllib.request.urlretrieve(SOURCES[name], path)
    return path


def is_han(ch):
    o = ord(ch)
    return 0x3400 <= o <= 0x9FFF or 0xF900 <= o <= 0xFAFF or 0x20000 <= o <= 0x3134F


def rime_rows(path):
    body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line == "...":
                body = True
                continue
            if body and line and not line.startswith("#") and "\t" in line:
                yield line.split("\t")


# ---------------------------------------------------------------- frequencies
def load_essay():
    words = {}
    with open(fetch("essay.txt"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 2 and parts[1].isdigit():
                w = hk(parts[0])
                words[w] = words.get(w, 0) + int(parts[1])
    return words


def load_hkcancor():
    freq = defaultdict(int)
    for w in pycantonese.hkcancor().words():
        if w and all(is_han(c) for c in w):
            freq[hk(w)] += 1
    return freq


def char_scores(essay, canto_words, spoken):
    score = defaultdict(float)
    for w, f in essay.items():
        for ch in w:
            score[ch] += f
    for w in canto_words:
        for ch in w:
            score[ch] += CANTO_WEIGHT
    for w, f in spoken.items():
        for ch in w:
            score[ch] += HK_CHAR_WEIGHT * f
    return score


# ---------------------------------------------------------------- 速成
def build_quick(score):
    codes = defaultdict(list)
    for row in rime_rows(fetch("cangjie5.base.dict.yaml")):
        if len(row) >= 2 and row[1].isalpha() and row[1].islower():
            codes[row[0]].append(row[1])

    table = defaultdict(set)
    for ch, cs in codes.items():
        if len(ch) != 1:
            continue
        # A simplified-only form nobody in HK types: skip it.
        if score.get(ch, 0) == 0 and s2hk.convert(ch) != ch:
            continue
        normal = [c for c in cs if not c.startswith(("x", "z"))]
        for c in (normal or cs):
            quick = c if len(c) == 1 else c[0] + c[-1]
            table[quick].add(ch)

    out = {}
    for code, chars in table.items():
        ranked = sorted(chars, key=lambda c: (-score.get(c, 0), ord(c)))
        out[code] = ranked[:QUICK_MAX]
    return out


# ---------------------------------------------------------------- 聯想
def build_assoc(essay, canto_words, spoken):
    word_score = defaultdict(float)
    for w, f in essay.items():
        if f >= 20:
            word_score[w] += f
    for w, f in spoken.items():
        word_score[w] += HK_WORD_WEIGHT * f
    for w in canto_words:
        if w not in word_score:
            word_score[w] = 400 if any(c in COLLOQUIAL for c in w) else 20

    by_first = defaultdict(list)
    for w, sc in word_score.items():
        if 2 <= len(w) <= 4 and all(is_han(c) for c in w):
            by_first[w[0]].append((sc, w[1:]))

    out = {}
    for ch, items in by_first.items():
        items.sort(key=lambda t: (-t[0], len(t[1])))
        out[ch] = [sfx for _, sfx in items[:ASSOC_MAX]]
    return out


# ---------------------------------------------------------------- English -> 中文
POS = re.compile(r"^(?:n|v|vt|vi|a|adj|ad|adv|pron|prep|conj|int|interj|num|art|aux|abbr|pl|det)\.\s*")
BRACKETS = re.compile(r"（[^）]*）|\([^)]*\)|\[[^\]]*\]|【[^】]*】|〈[^〉]*〉|<[^>]*>")
SPLIT = re.compile(r"[；;，,、/]")


def clean_meanings(text):
    regular, network = [], []
    for line in text.replace("\\n", "\n").split("\n"):
        line = line.strip()
        target = network if line.startswith("[网络]") else regular
        line = BRACKETS.sub("", POS.sub("", line))
        for part in SPLIT.split(line):
            part = part.strip()
            if not part or not all(is_han(c) for c in part):
                continue
            if len(part) >= 3 and part.endswith("的"):
                part = part[:-1]
            if 1 <= len(part) <= 6:
                target.append(part)
    return regular + network


def build_english():
    csv.field_size_limit(10**9)
    out = {}
    with open(fetch("ecdict.csv"), encoding="utf-8") as f:
        for row in csv.DictReader(f):
            word = row["word"]
            if not (word.isascii() and word.isalpha() and 2 <= len(word) <= 20):
                continue
            frq = int(row["frq"] or 0)
            bnc = int(row["bnc"] or 0)
            common = (
                0 < frq <= 40000 or 0 < bnc <= 40000
                or row["collins"] not in ("", "0") or row["oxford"] == "1" or row["tag"].strip()
            )
            if not common:
                continue
            key = word.lower()
            seen = out.setdefault(key, [])
            for m in clean_meanings(row["translation"]):
                m = hk(s2hk.convert(m))
                if m not in seen:
                    seen.append(m)
    for word, zh in load_overrides().items():
        out[word] = zh + [m for m in out.get(word, []) if m not in zh]
    return {k: v[:EN_MAX] for k, v in out.items() if v}


def load_overrides():
    """tools/en_overrides.tsv: `word<TAB>中文 中文 ...`, shown before ECDICT's translations."""
    path = os.path.join(HERE, "en_overrides.tsv")
    out = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "\t" in line:
                    word, zh = line.split("\t", 1)
                    out[word.strip().lower()] = zh.split()
    return out


def write(name, table, keyorder=None):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for k in sorted(table, key=keyorder):
            f.write(f"{k}\t{' '.join(table[k])}\n")
    print(f"{name}: {len(table)} entries, {os.path.getsize(path) // 1024} KB", file=sys.stderr)


def main():
    essay = load_essay()
    canto = sorted({hk(row[0]) for row in rime_rows(fetch("jyut6ping3.words.dict.yaml"))})
    spoken = load_hkcancor()
    score = char_scores(essay, canto, spoken)
    write("quick.tsv", build_quick(score))
    write("assoc.tsv", build_assoc(essay, canto, spoken))
    write("en2zh.tsv", build_english())


if __name__ == "__main__":
    main()
