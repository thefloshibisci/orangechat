"""Build the small, offline Gongdu heading font from Source Han Serif SC Regular.

Usage: python scripts/subset_gongdu_font.py /path/to/SourceHanSerifSC-Regular.otf
Requires fonttools. The source's SIL OFL license is shipped with the application.
"""

import argparse
import hashlib
import re
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    hub = root / "app/src/main/java/me/rerere/rikkahub/ui/pages/gongdu/GongduHub.kt"
    titles = re.findall(r'GongduRoom\("[^"\n]+", "[^"\n]+", "([^"\n]+)"', hub.read_text(encoding="utf-8"))
    if not titles:
        raise ValueError("No room titles found; check the GongduRoom declarations before subsetting")
    characters = set("共度·挑一篇，带回聊天每一张牌通往件起做的事左右滑动间轻点进入筹备中→" + "".join(titles)) | {chr(i) for i in range(32, 127)}
    font = TTFont(args.source, recalcTimestamp=False)
    missing = {ord(c) for c in characters} - set(font.getBestCmap())
    if missing:
        raise ValueError(f"Source font is missing required characters: {sorted(missing)}")
    options = subset.Options()
    options.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14, 16, 17]
    options.name_legacy = True
    options.name_languages = [0x409]
    subsetter = subset.Subsetter(options=options)
    subsetter.populate(text="".join(sorted(characters)))
    subsetter.subset(font)

    # Rename the modified subset; preserve upstream copyright and licensing records.
    names = {1: "Gongdu Book", 2: "Regular", 3: "OrangeChat:GongduBook-Regular:1.0",
             4: "Gongdu Book Regular", 6: "GongduBook-Regular", 16: "Gongdu Book", 17: "Regular"}
    for name_id, value in names.items():
        font["name"].removeNames(nameID=name_id)
        font["name"].setName(value, name_id, 3, 1, 0x409)
    cff = font["CFF "].cff
    cff.fontNames = ["GongduBook-Regular"]
    top = cff.topDictIndex[0]
    top.FamilyName = "Gongdu Book"
    top.FullName = "Gongdu Book Regular"
    if hasattr(top, "CIDFontName"):
        top.CIDFontName = "GongduBook-Regular"
    output = root / "app/src/main/res/font/gongdu_book_regular.otf"
    font.save(output)
    check = TTFont(output)
    assert {ord(c) for c in characters} <= set(check.getBestCmap())
    print(f"Font: {output.name}; {output.stat().st_size} bytes; {len(characters)} characters")
    print(f"Source SHA256: {hashlib.sha256(args.source.read_bytes()).hexdigest()}")
    print(f"Subset SHA256: {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
