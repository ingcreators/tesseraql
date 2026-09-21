# TesseraQL Sample Gothic

`TesseraQLSampleGothic-Regular.ttf` is a glyph subset of
[Noto Sans JP](https://fonts.google.com/noto/specimen/Noto+Sans+JP) (Copyright 2014-2021 Adobe,
SIL Open Font License 1.1 - see `OFL.txt`), instanced to weight 400 and subset with fontTools to
ASCII, JIS X 0208 rows 1-8 (symbols, kana, Greek, Cyrillic, box drawing), the JIS level-1
kanji and the halfwidth katakana — the character set a Japanese business document uses, so
anything the demo tour types renders. The family was renamed to `TesseraQL Sample Gothic` to
mark it as a modified sample asset; `scripts/sample-font.py` regenerates it.

It exists so PDF tests and example apps can embed a CJK-capable font without committing a
multi-megabyte font binary. Real applications should place a complete font (for example the
original Noto Sans JP) in their app home's `fonts/` directory instead.
