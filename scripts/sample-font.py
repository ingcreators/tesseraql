#!/usr/bin/env python3
"""Regenerates TesseraQL Sample Gothic, the CJK-capable font the examples and tests embed.

The sample font is a subset of Noto Sans JP (OFL 1.1) so the repository can carry a font that
prints Japanese without committing a multi-megabyte binary. Its character set is a standard's,
not a hand list: ASCII, JIS X 0208 rows 1-8 (symbols, kana, Greek, Cyrillic, box drawing) and
the JIS level-1 kanji, plus the halfwidth katakana - the ranges derived through the Shift_JIS
encoding, which is how the standard numbers them. Anything a demo types in ordinary Japanese
renders; a rare name kanji from level 2 does not (docs/procurement-documents-and-edi.md,
decision 6).

Usage:

    pip install fonttools brotli
    curl -L -o NotoSansJP.ttf \\
      'https://github.com/google/fonts/raw/main/ofl/notosansjp/NotoSansJP%5Bwght%5D.ttf'
    python3 scripts/sample-font.py NotoSansJP.ttf

The script instances the variable font at weight 400, subsets it, renames the family (the
rename marks it as a modified sample asset), and writes TesseraQLSampleGothic-Regular.ttf into
every directory that carries the sample font. The source font is not committed.
"""

import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

FAMILY = "TesseraQL Sample Gothic"
POSTSCRIPT = "TesseraQLSampleGothic-Regular"
FILE_NAME = POSTSCRIPT + ".ttf"

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), os.pardir))
TARGETS = [
    "examples/user-admin-app/fonts",
    "examples/procurement-app/fonts",
    "tesseraql-pdf/src/test/resources/fonts",
    "tesseraql-runtime/src/test/resources/fonts",
]

# Shift_JIS code ranges (JIS X 0208): rows 1-8 hold the non-kanji, 0x889F-0x9872 the level-1 kanji.
ROWS_1_TO_8 = (0x8140, 0x84BE)
LEVEL_1_KANJI = (0x889F, 0x9872)
# Yen, (c), (r), em dash, ellipsis, ideographic space, and the Latin ligatures (ff fi fl ffi ffl):
# a ligature glyph the layout substitutes must keep its own code point, or the PDF carries
# no text for it and "office" extracts as "o ce".
EXTRA = [0x00A5, 0x00A9, 0x00AE, 0x2014, 0x2026, 0x3000, *range(0xFB00, 0xFB07)]


def character_set():
    keep = set(range(0x20, 0x7F))
    for cp in range(0x80, 0x10000):
        try:
            encoded = chr(cp).encode("shift_jis")
        except UnicodeEncodeError:
            continue
        if len(encoded) == 1:
            if 0xA1 <= encoded[0] <= 0xDF:  # halfwidth katakana
                keep.add(cp)
            continue
        code = encoded[0] << 8 | encoded[1]
        if ROWS_1_TO_8[0] <= code <= ROWS_1_TO_8[1] or LEVEL_1_KANJI[0] <= code <= LEVEL_1_KANJI[1]:
            keep.add(cp)
    keep.update(EXTRA)
    return keep


def rename(font):
    """Sets the family, subfamily, full, unique and PostScript names on every platform record."""
    names = {
        1: FAMILY,
        2: "Regular",
        3: FAMILY + " Regular; TesseraQL sample subset of Noto Sans JP",
        4: FAMILY + " Regular",
        6: POSTSCRIPT,
        16: FAMILY,
        17: "Regular",
    }
    table = font["name"]
    for record in list(table.names):
        if record.nameID in names:
            table.setName(names[record.nameID], record.nameID, record.platformID,
                          record.platEncID, record.langID)
        elif record.nameID in (0, 7, 8, 9, 10, 11, 12, 13, 14):
            continue  # copyright, trademark, manufacturer, designer, description, license: kept
        elif record.nameID in (21, 22, 25):
            table.removeNames(record.nameID)


def build(source):
    font = TTFont(source)
    if "fvar" in font:
        font = instancer.instantiateVariableFont(font, {"wght": 400})
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(unicodes=sorted(character_set()))
    subsetter.subset(font)
    rename(font)
    return font


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    font = build(argv[1])
    for target in TARGETS:
        path = os.path.join(ROOT, target, FILE_NAME)
        font.save(path)
        print(f"{path}: {len(font.getGlyphOrder())} glyphs, {os.path.getsize(path)} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
