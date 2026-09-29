#!/usr/bin/env python3
"""Generate the emoji palette data shipped in app/src/main/assets/emoji/.

Inputs (downloaded unless given as local paths):
  * Unicode emoji-test.txt: the canonical ordered list of fully-qualified
    emoji, grouped the same way every major keyboard groups them.
  * CLDR annotations (+ derived annotations): localized names and search
    keywords per emoji.

Outputs:
  * emoji.txt            one line per base emoji: "<emoji> <version> [<variant>...]"
                         preceded by "@<category>" header lines.
  * keywords_<lang>.txt  "<emoji>\t<name>\t<kw>|<kw>|..." for each base emoji.

Usage:
  tools/emoji/gen_emoji_data.py [--emoji-test FILE] [--cldr-dir DIR] [--lang en,de,...]
"""

import argparse
import json
import os
import re
import sys
import urllib.request

EMOJI_TEST_URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"
CLDR_URL = ("https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json/"
            "cldr-annotations{derived}-full/annotations{Derived}/{lang}/annotations.json")

# Unicode group name -> category id used by the app (order is display order).
CATEGORIES = [
    ("Smileys & Emotion", "smileys"),
    ("People & Body", "people"),
    ("Animals & Nature", "animals"),
    ("Food & Drink", "food"),
    ("Travel & Places", "travel"),
    ("Activities", "activities"),
    ("Objects", "objects"),
    ("Symbols", "symbols"),
    ("Flags", "flags"),
]
GROUP_TO_CATEGORY = dict(CATEGORIES)

SKIN_TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
VS16 = 0xFE0F

LINE_RE = re.compile(r"^([0-9A-F ]+?)\s*;\s*fully-qualified\s*#\s*\S+\s+E(\d+\.\d+)\s+(.*)$")
# ": light skin tone" / ", medium-dark skin tone" clauses in emoji names.
TONE_CLAUSE_RE = re.compile(r"[:,] [a-z-]+ skin tone")
# The neutral two-person forms are named "kiss: person, person, <tones>" while
# their base is plain "kiss" (💏) / "couple with heart" (💑).
NEUTRAL_PAIR_SUFFIX = ": person, person"

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "emoji")


def fetch(url_or_path):
    if os.path.exists(url_or_path):
        with open(url_or_path, encoding="utf-8") as f:
            return f.read()
    print("fetching " + url_or_path, file=sys.stderr)
    with urllib.request.urlopen(url_or_path) as r:
        return r.read().decode("utf-8")


def strip_key(cps):
    """Identity of an emoji ignoring skin tones and emoji presentation selectors."""
    return tuple(c for c in cps if c not in SKIN_TONES and c != VS16)


def tones_of(cps):
    return [c for c in cps if c in SKIN_TONES]


def parse_emoji_test(text):
    """Returns [(category, [base])] with base = dict(cps, version, variants)."""
    categories = {cat: [] for _, cat in CATEGORIES}
    by_key = {}
    by_name = {}
    category = None
    for line in text.splitlines():
        if line.startswith("# group:"):
            category = GROUP_TO_CATEGORY.get(line.split(":", 1)[1].strip())
            continue
        if category is None:
            continue
        m = LINE_RE.match(line)
        if not m:
            continue
        cps = tuple(int(c, 16) for c in m.group(1).split())
        version, name = m.group(2), m.group(3).strip()
        if tones_of(cps):
            # Tone variant: attach to the base with the same identity. Mixed
            # tone sequences like 🫱🏻‍🫲🏼 differ from their base (🤝), so fall
            # back to the base whose name matches once tone clauses are removed.
            base_name = TONE_CLAUSE_RE.sub("", name)
            if base_name.endswith(NEUTRAL_PAIR_SUFFIX):
                base_name = base_name[:-len(NEUTRAL_PAIR_SUFFIX)]
            base = by_key.get(strip_key(cps)) or by_name.get(base_name)
            if base is None:
                raise ValueError("orphan skin tone variant: " + line)
            base["variants"].append(cps)
            continue
        base = {"cps": cps, "version": version, "variants": []}
        categories[category].append(base)
        by_key[strip_key(cps)] = base
        by_name[name] = base
    for bases in categories.values():
        for base in bases:
            base["variants"] = order_variants(base)
    return categories


def order_variants(base):
    variants = base["variants"]
    if len(variants) == 25:
        # Two-person emoji: order as a 5x5 grid of (first tone, second tone).
        # Single-modifier forms like 🤝🏻 mean "both people the same tone".
        def grid_pos(cps):
            t = tones_of(cps)
            return (t[0], t[-1])
        variants = sorted(variants, key=grid_pos)
        assert len({grid_pos(v) for v in variants}) == 25, base
    elif variants and len(variants) != 5:
        raise ValueError("unexpected variant count %d for %s"
                         % (len(variants), to_str(base["cps"])))
    return variants


def to_str(cps):
    return "".join(chr(c) for c in cps)


def load_annotations(lang, cldr_dir):
    """Returns {stripped emoji key: (name, [keywords])} for a CLDR locale."""
    result = {}
    for derived in (False, True):
        if cldr_dir:
            sub = ("cldr-annotations-derived-full/annotationsDerived" if derived
                   else "cldr-annotations-full/annotations")
            src = os.path.join(cldr_dir, sub, lang, "annotations.json")
        else:
            src = CLDR_URL.format(derived="-derived" if derived else "",
                                  Derived="Derived" if derived else "", lang=lang)
        try:
            data = json.loads(fetch(src))
        except Exception as e:  # derived data is missing for some locales
            print("warning: %s: %s" % (src, e), file=sys.stderr)
            continue
        root = data["annotationsDerived" if derived else "annotations"]["annotations"]
        for emoji, entry in root.items():
            cps = tuple(ord(c) for c in emoji)
            # Derived data also names every skin tone variant ("...: light skin
            # tone"); those must not stand in for the base emoji.
            if tones_of(cps):
                continue
            key = strip_key(cps)
            if key in result:
                continue
            name = (entry.get("tts") or [""])[0]
            result[key] = (name, entry.get("default") or [])
    return result


def clean(s):
    return s.replace("\t", " ").replace("|", " ").strip()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--emoji-test", default=EMOJI_TEST_URL)
    ap.add_argument("--cldr-dir", help="local checkout of unicode-org/cldr-json/cldr-json")
    ap.add_argument("--lang", default="en")
    ap.add_argument("--out", default=OUT_DIR)
    args = ap.parse_args()

    test_text = fetch(args.emoji_test)
    m = re.search(r"^# Version: (\S+)", test_text, re.M)
    version = m.group(1) if m else "unknown"
    categories = parse_emoji_test(test_text)
    os.makedirs(args.out, exist_ok=True)

    with open(os.path.join(args.out, "emoji.txt"), "w", encoding="utf-8") as f:
        f.write("# Generated by tools/emoji/gen_emoji_data.py from Unicode emoji-test.txt %s.\n"
                % version)
        f.write("# Format: @category, then '<emoji> <emoji version> [<skin tone variant>...]'.\n")
        for _, cat in CATEGORIES:
            f.write("@%s\n" % cat)
            for base in categories[cat]:
                parts = [to_str(base["cps"]), base["version"]]
                parts += [to_str(v) for v in base["variants"]]
                f.write(" ".join(parts) + "\n")

    bases = [b for _, cat in CATEGORIES for b in categories[cat]]
    for lang in args.lang.split(","):
        ann = load_annotations(lang, args.cldr_dir)
        missing = 0
        path = os.path.join(args.out, "keywords_%s.txt" % lang.replace("-", "_"))
        with open(path, "w", encoding="utf-8") as f:
            f.write("# Generated by tools/emoji/gen_emoji_data.py from CLDR annotations (%s).\n"
                    % lang)
            f.write("# Format: <emoji>\\t<name>\\t<keyword>|<keyword>|...\n")
            for base in bases:
                entry = ann.get(strip_key(base["cps"]))
                if not entry:
                    missing += 1
                    continue
                name, keywords = entry
                keywords = [clean(k) for k in keywords if clean(k)]
                f.write("%s\t%s\t%s\n" % (to_str(base["cps"]), clean(name), "|".join(keywords)))
        print("%s: %d emoji without annotations" % (lang, missing), file=sys.stderr)

    total_variants = sum(len(b["variants"]) for b in bases)
    print("emoji %s: %d base emoji, %d skin tone variants"
          % (version, len(bases), total_variants), file=sys.stderr)


if __name__ == "__main__":
    main()
