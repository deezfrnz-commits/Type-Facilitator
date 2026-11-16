#!/usr/bin/env python3
"""Builds the keyboard's offline data files in app/src/main/assets from the sources in data/.

  dict.bin   English word list with frequencies (from the AOSP LatinIME en_US word list, Apache 2.0)
  fixes.txt  built-in typo -> correction pairs from the same list
  emoji.txt  every fully-qualified emoji from Unicode emoji-test.txt, grouped, skin tones as variants

Run from the TypeFacilitator folder: python3 scripts/gen_assets.py
"""
import gzip, os, re, struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "data")
OUT = os.path.join(ROOT, "app", "src", "main", "assets")
os.makedirs(OUT, exist_ok=True)


def lower(b):
    """Latin-1 lowercase of one byte; must match Dictionary.lower() in the app."""
    if 65 <= b <= 90 or (0xC0 <= b <= 0xDE and b != 0xD7):
        return b + 32
    return b


# built-in corrections that misfire too often on real words and abbreviations
SKIP_FIXES = {"i", "os", "un", "mot", "mote", "il", "hed", "wel", "wer", "shel", "bern", "amd", "uf"}


def build_dict():
    words, fixes, cur = {}, [], None
    for line in gzip.open(os.path.join(DATA, "en_US_wordlist.combined.gz"), "rt", encoding="utf-8"):
        m = re.match(r" word=(.*?),f=(\d+)(,flags=([^,]*))?(,.*)?$", line.rstrip("\n"))
        if m:
            w, f, flags, rest = m[1], int(m[2]), m[4] or "", m[5] or ""
            cur = w if "not_a_word=true" in rest else None
            if cur is None:
                try:
                    w.encode("latin-1")
                except UnicodeEncodeError:
                    continue
                if not re.fullmatch(r"[^\W\d_]+(['-][^\W\d_]+)*'?", w):
                    continue
                fb = 0 if "offensive" in flags else min(f, 254) + 1  # 0 = valid but never suggested
                words[w] = max(words.get(w, 0), fb)
            continue
        m = re.match(r"\s+shortcut=(.*?),f=whitelist", line)
        if m and cur is not None and cur not in SKIP_FIXES:
            fixes.append((cur, m[1]))
            cur = None
    # possessives are derived by rule in the app; keep only those whose base is missing
    keep = {w: f for w, f in words.items() if not (w.endswith("'s") and w[:-2] in words)}
    items = sorted(keep.items(), key=lambda kv: (bytes(lower(b) for b in kv[0].encode("latin-1")), -kv[1], kv[0]))
    blob = "\n".join(w for w, _ in items).encode("latin-1") + b"\n"
    with open(os.path.join(OUT, "dict.bin"), "wb") as f:
        f.write(struct.pack(">ii", len(items), len(blob)))
        f.write(bytes(fr for _, fr in items))
        f.write(blob)
    with open(os.path.join(OUT, "fixes.txt"), "w", encoding="utf-8") as f:
        for a, b in sorted(set(fixes)):
            f.write(f"{a}={b}\n")
    print(f"dict.bin: {len(items)} words, {len(blob) + len(items) + 8} bytes; fixes: {len(set(fixes))}")


TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}


def build_emoji():
    groups, order, variants = [], {}, {}
    group = None
    for line in open(os.path.join(DATA, "emoji-test.txt"), encoding="utf-8"):
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            if group != "Component":
                groups.append((group, []))
            continue
        if line.startswith("#") or ";" not in line or group == "Component":
            continue
        cps, rest = line.split(";", 1)
        if not rest.strip().startswith("fully-qualified"):
            continue
        cp = [int(x, 16) for x in cps.split()]
        s = "".join(map(chr, cp))
        key = tuple(c for c in cp if c not in TONES and c != 0xFE0F)
        if any(c in TONES for c in cp):
            variants.setdefault(key, []).append(s)
        else:
            order[key] = s
            groups[-1][1].append(key)
    # mixed-skin-tone sequences whose plain form is a different, single code point
    P, M, W, Z = 0x1F9D1, 0x1F468, 0x1F469, 0x200D
    alias = {
        (0x1FAF1, Z, 0x1FAF2): (0x1F91D,),
        (W, Z, 0x1F91D, Z, W): (0x1F46D,), (W, Z, 0x1F91D, Z, M): (0x1F46B,), (M, Z, 0x1F91D, Z, M): (0x1F46C,),
        (P, Z, 0x2764, Z, 0x1F48B, Z, P): (0x1F48F,), (P, Z, 0x2764, Z, P): (0x1F491,),
        (P, Z, 0x1F430, Z, P): (0x1F46F,), (M, Z, 0x1F430, Z, M): (0x1F46F, Z, 0x2642), (W, Z, 0x1F430, Z, W): (0x1F46F, Z, 0x2640),
        (P, Z, 0x1FAEF, Z, P): (0x1F93C,), (M, Z, 0x1FAEF, Z, M): (0x1F93C, Z, 0x2642), (W, Z, 0x1FAEF, Z, W): (0x1F93C, Z, 0x2640),
    }
    for k, base in alias.items():
        if k in variants and base in order:
            variants.setdefault(base, []).extend(variants.pop(k))
    n = 0
    with open(os.path.join(OUT, "emoji.txt"), "w", encoding="utf-8") as f:
        for name, keys in groups:
            f.write("#" + name.split(" ")[0] + "\n")
            for k in keys:
                row = [order[k]] + variants.get(k, [])
                n += len(row)
                f.write(" ".join(row) + "\n")
    orphan = [k for k in variants if k not in order]
    for k in orphan[:20]:
        print("  orphan", variants[k][0], [hex(c) for c in k])
    print(f"emoji.txt: {n} emoji in {len(groups)} groups; orphan variants: {len(orphan)}")


build_dict()
build_emoji()
