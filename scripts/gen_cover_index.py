#!/usr/bin/env python3
"""gen_cover_index.py — 重新生成 assets/cover_index/ 内置封面名索引。

背景：CoverFetcher 的模糊匹配依赖“系统封面名索引”（官方 No-Intro/TOSEC 名
列表）。索引此前只能联网拉取（libretro autoindex 4MB HTML / GitHub trees
API 15MB 且限流），网络受限时拉不下来 → 中文/异名 ROM 的模糊匹配永久失效
→ “封面全部跳过”。现在把各主平台索引随 APK 打包（≈2.9MB，14 平台），
CoverFetcher.loadBundledIndex 离线可用（磁盘缓存 30 天后自动走网络刷新）。

用法（重新生成/刷新索引）：
  python3 scripts/gen_cover_index.py            # 全部系统
  python3 scripts/gen_cover_index.py "Nintendo - Nintendo DS"   # 指定系统

输出：app/src/main/assets/cover_index/<safe_name>.txt（每行一个官方封面名，
safe_name = 系统名中所有非字母数字段折叠为单个下划线，与
CoverFetcher.fetchSystemIndex 的缓存命名完全一致）。
"""
import os
import re
import sys
import urllib.request
import urllib.parse

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(REPO_ROOT, "app", "src", "main", "assets", "cover_index")
BASE = "https://thumbnails.libretro.com"

# 与 CoverFetcher.libretroSystemDir 对应的系统目录（2026-10 实测目录名）
SYSTEMS = [
    "Nintendo - Nintendo Entertainment System",
    "Nintendo - Super Nintendo Entertainment System",
    "Nintendo - Game Boy",
    "Nintendo - Game Boy Advance",
    "Nintendo - Nintendo DS",
    "Nintendo - Nintendo 3DS",
    "Sega - Mega Drive - Genesis",
    "Sega - Dreamcast",
    "NEC - PC Engine - TurboGrafx 16",   # 注意：站上目录名是空格不是连字符
    "Sony - PlayStation",
    "Sony - PlayStation 2",
    "Nintendo - Wii",
    "Nintendo - GameCube",
    "FBNeo - Arcade Games",
]


def fetch_index(system: str) -> list:
    url = f"{BASE}/{urllib.parse.quote(system)}/Named_Boxarts/"
    req = urllib.request.Request(url, headers={"User-Agent": "NesStation/1.0 (index-gen)"})
    with urllib.request.urlopen(req, timeout=120) as r:
        html = r.read().decode("utf-8", "replace")
    names = []
    for m in re.finditer(r'<a\s+href="([^"]+)">([^<]*)</a>', html, re.I):
        text = m.group(2).strip()
        if text.lower().endswith(".png"):
            names.append(text[:-4])
    return names


def main() -> int:
    systems = sys.argv[1:] or SYSTEMS
    os.makedirs(OUT_DIR, exist_ok=True)
    total = 0
    for system in systems:
        try:
            names = fetch_index(system)
        except Exception as e:
            print(f"❌ {system}: {e}")
            continue
        if not names:
            print(f"⚠️ {system}: 0 names（目录名不对或站点变化？）")
            continue
        safe = re.sub(r"[^A-Za-z0-9]+", "_", system).strip("_")
        out = os.path.join(OUT_DIR, safe + ".txt")
        with open(out, "w", encoding="utf-8") as f:
            f.write("\n".join(names))
        total += os.path.getsize(out)
        print(f"✅ {safe}.txt  {len(names)} names, {os.path.getsize(out)}B")
    print(f"---- total: {total / 1024 / 1024:.2f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
