#!/usr/bin/env python3
"""用真实 thumbnails.libretro.com 验证候选命中率（等价于 CoverFetcher.fetchCover 的 URL 生成）。
用法: python3 cover_candidates_hit_test.py
"""
import sys, time, urllib.parse, urllib.request
from cover_candidates_test import name_candidates

BASE = "https://thumbnails.libretro.com"
UA = "Mozilla/5.0 (cover-hit-test)"

def url_seg(s: str) -> str:
    # 与 Kotlin CoverFetcher.urlSeg 等价：URLEncoder + 还原括号
    return urllib.parse.quote(s, safe="") \
        .replace("+", "%20") \
        .replace("%28", "(").replace("%29", ")")

# 平台 -> libretro 目录
SYSTEM = {
    "NES": "Nintendo - Nintendo Entertainment System",
    "SFC": "Nintendo - Super Nintendo Entertainment System",
    "GBA": "Nintendo - Game Boy Advance",
    "MD": "Sega - Mega Drive - Genesis",
    "GB": "Nintendo - Game Boy",
    "ARC": "FBNeo - Arcade Games",
    "PCE": "NEC - PC Engine - TurboGrafx-16",
    "PSX": "Sony - PlayStation",
    "DC": "Sega - Dreamcast",
}

def probe(url: str) -> bool:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status == 200
    except Exception:
        return False

def main():
    # (平台, 游戏名) 样本 —— 覆盖旧式/No-Intro/多 Disc 等命名
    samples = [
        ("NES", "Super Mario Bros (JU) [!]"),      # 旧式 → (Europe) 补点+映射命中
        ("NES", "Contra (U) [!]"),                  # 旧式 → (USA) 映射命中
        ("NES", "Mega Man 2 (JU)"),                 # 旧式多区域
        ("NES", "Kirby's Adventure (USA)"),         # No-Intro 直中
        ("NES", "Final Fantasy VI (UE) [!]"),       # 旧式多区域
        ("NES", "Super Mario Bros. (World)"),       # No-Intro 句点
        ("NES", "Zelda II - The Adventure of Link (U)"),  # 带 - 副标题
        ("SFC", "Super Mario World (U)"),
        ("SFC", "Final Fantasy III (U) [!]"),
        ("GBA", "Metroid Fusion (U)"),
        ("GBA", "Pokemon - Emerald Version (U)"),
        ("MD", "Sonic the Hedgehog (U) [!]"),
        ("MD", "Streets of Rage 2 (U) [!]"),
        ("GB", "Tetris (World)"),
        ("PCE", "Bonk's Adventure (U)"),
        ("PSX", "Final Fantasy VII (USA) (Disc 1)"),
        ("DC", "Sonic Adventure (USA)"),
    ]
    hits = 0
    for plat, title in samples:
        cands = name_candidates(title)
        hit_at = -1
        total_req = 0
        for i, name in enumerate(cands):
            url = f"{BASE}/{url_seg(SYSTEM[plat])}/Named_Boxarts/{url_seg(name)}.png"
            total_req += 1
            if probe(url):
                hit_at = i
                break
            time.sleep(0.05)
        if hit_at >= 0:
            hits += 1
            print(f"HIT  {plat:<4} [{title}] -> cand#{hit_at+1}/{len(cands)} : {cands[hit_at]}")
        else:
            print(f"MISS {plat:<4} [{title}] -> {total_req} reqs")
    print(f"\n=== 命中 {hits}/{len(samples)} ===")

if __name__ == "__main__":
    main()
