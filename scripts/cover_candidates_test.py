#!/usr/bin/env python3
"""独立验证 CoverFetcher.nameCandidates 增强算法（与 Kotlin 实现逻辑等价）。
用法: python3 cover_candidates_test.py
"""
import re

MAX_CANDIDATES = 16

REGION_ALIASES = {
    "U": ["USA"], "US": ["USA"], "USA": ["USA"],
    "J": ["Japan"], "JP": ["Japan"], "JPN": ["Japan"], "JAPAN": ["Japan"],
    "E": ["Europe"], "EU": ["Europe"], "EUR": ["Europe"], "EUROPE": ["Europe"],
    "W": ["World"], "WORLD": ["World"],
    "UK": ["UK"],
    "AU": ["Australia"], "AUS": ["Australia"],
    "AS": ["Asia"], "ASIA": ["Asia"],
    "CN": ["China"], "CHN": ["China"], "CHINA": ["China"],
    "TW": ["Taiwan"], "TWN": ["Taiwan"], "TAIWAN": ["Taiwan"],
    "KR": ["Korea"], "KOR": ["Korea"], "KOREA": ["Korea"],
    "BR": ["Brazil"], "BRA": ["Brazil"], "BRAZIL": ["Brazil"],
    "CA": ["Canada"], "CAN": ["Canada"], "CANADA": ["Canada"],
    "F": ["France"], "FR": ["France"], "FRANCE": ["France"],
    "G": ["Germany"], "DE": ["Germany"], "GER": ["Germany"], "GERMANY": ["Germany"],
    "ES": ["Spain"], "SPA": ["Spain"], "SPAIN": ["Spain"],
    "I": ["Italy"], "IT": ["Italy"], "ITALY": ["Italy"],
    "NL": ["Netherlands"], "NED": ["Netherlands"], "NETHERLANDS": ["Netherlands"],
    "SW": ["Sweden"], "SWE": ["Sweden"], "SWEDEN": ["Sweden"],
    "RU": ["Russia"], "RUS": ["Russia"], "RUSSIA": ["Russia"],
    "HK": ["Hong Kong"],
    "GR": ["Greece"], "GREECE": ["Greece"],
    "NO": ["Norway"], "NOR": ["Norway"], "NORWAY": ["Norway"],
    "SC": ["Scandinavia"], "SCANDINAVIA": ["Scandinavia"],
    "JU": ["World", "USA", "Japan", "Europe"],
    "UJ": ["World", "USA", "Japan", "Europe"],
    "JUE": ["World", "USA", "Europe", "Japan"],
    "UJE": ["World", "USA", "Europe", "Japan"],
    "JEU": ["World", "USA", "Europe", "Japan"],
    "EJU": ["World", "USA", "Europe", "Japan"],
    "EUJ": ["World", "USA", "Europe", "Japan"],
    "UE": ["World", "USA", "Europe"],
    "JE": ["World", "Japan", "Europe"],
    "EJ": ["World", "Japan", "Europe"],
    "WE": ["World", "Europe"],
    "USJ": ["World", "USA", "Japan"],
    "USE": ["World", "USA", "Europe"],
}


def dot_title(name: str):
    if not name or not name.strip():
        return None
    idx = name.find("(")
    title = name[:idx].strip() if idx >= 0 else name.strip()
    if not title:
        return None
    last = title[-1]
    if not (last.isalnum() or last in "!?"):
        return None
    return f"{title}. {name[idx:]}" if idx >= 0 else f"{title}."


def map_regions(name: str):
    results = set()
    for m in re.finditer(r"\(([^)]+)\)", name):
        code = m.group(1).strip().upper()
        mapped = REGION_ALIASES.get(code)
        if not mapped:
            continue
        for r in mapped:
            results.add(name[: m.start()] + f"({r})" + name[m.end():])
    return list(results)


def name_candidates(raw: str):
    n0 = " ".join(raw.strip().replace("_", " ").split())
    if not n0:
        return []
    candidates = []
    seen = set()
    def add(v):
        if v and v not in seen:
            seen.add(v)
            candidates.append(v)
    no_brackets = re.sub(r"\s*\[[^\]]*]", "", n0).strip()
    no_tags = re.sub(r"\s*\([^)]*\)", "", no_brackets).strip()
    add(n0)
    if no_brackets and no_brackets != n0:
        add(no_brackets)
    if no_tags and no_tags != n0 and no_tags != no_brackets:
        add(no_tags)
    for v in (n0, no_brackets, no_tags):
        d = dot_title(v)
        if d:
            add(d)
    for mapped in map_regions(no_brackets):
        add(mapped)
        d = dot_title(mapped)
        if d:
            add(d)
    return candidates[:MAX_CANDIDATES]


if __name__ == "__main__":
    samples = [
        "Super Mario Bros (JU) [!]",
        "Contra (U) [!]",
        "Metroid Fusion (U)",
        "Sonic the Hedgehog (U) [!]",
        "Kirby's Adventure (USA)",
        "Mega Man 2 (JU)",
        "Final Fantasy VI (UE) [!]",
        "Super Bomberman 3 (J)",
        "Pocky & Rocky 2 (U) [!]",
        "Tetris",
        "Metal Slug (Asia)",
        "魂斗罗",
        "007 - Licence to Kill (Europe)",
    ]
    for s in samples:
        cands = name_candidates(s)
        print(f"[{s}] -> {len(cands)}")
        for c in cands:
            print(f"    {c}")
        print()
