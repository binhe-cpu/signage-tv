#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门店屏 · 清单生成核心

命令行工具（tools/publish.py）和网页后台（server/signage_admin.py）共用这一份。
共用是刻意的：两边各写一套的话，改一处忘一处，生成的清单就会不一致，
而端侧对不一致的表现只是"某些素材不更新"，这种问题极难排查。

这里只放纯逻辑——不碰网络、不碰存储、不碰命令行。
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

SCHEMA_VERSION = 1

VIDEO_EXT = {"mp4", "mkv", "webm", "ts", "mov", "m4v", "avi", "3gp"}
IMAGE_EXT = {"jpg", "jpeg", "png", "webp", "bmp"}

MIME = {
    "jpg": "image/jpeg", "jpeg": "image/jpeg", "png": "image/png",
    "webp": "image/webp", "bmp": "image/bmp",
    "mp4": "video/mp4", "m4v": "video/mp4", "mov": "video/quicktime",
    "mkv": "video/x-matroska", "webm": "video/webm",
    "ts": "video/mp2t", "avi": "video/x-msvideo", "3gp": "video/3gpp",
    "json": "application/json; charset=utf-8",
}

PRESET_NAME = "playlist.preset.json"


# ---------------------------------------------------------------- 文件名工具

def ext_of(name: str) -> str:
    return name.rsplit(".", 1)[-1].lower() if "." in name else ""


def natural_key(s: str):
    """让 2_xxx 排在 10_xxx 前面（纯字符串排序会排反）"""
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def human(n) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit != "MB" else f"{n:.1f} MB"
        n /= 1024.0


def is_media(name: str) -> bool:
    e = ext_of(name)
    return e in VIDEO_EXT or e in IMAGE_EXT


def kind_of(name: str):
    e = ext_of(name)
    if e in VIDEO_EXT:
        return "video"
    if e in IMAGE_EXT:
        return "image"
    return None


def content_type(name: str) -> str:
    return MIME.get(ext_of(name), "application/octet-stream")


def is_playlist_name(name: str) -> bool:
    """清单文件和 preset 本身不算素材"""
    if name == PRESET_NAME:
        return True
    stem, _, ext = name.rpartition(".")
    return ext.lower() == "json" and stem.startswith("playlist")


def safe_rel_path(raw: str, media_only: bool = True):
    """
    把用户给的相对路径规整成安全形式，不安全则返回 None。

    必须挡住的：绝对路径、`..` 上跳、`.` 开头的段（临时文件/隐藏文件）、
    空段、段数过多。

    media_only=True（默认）还要求扩展名是支持的图片/视频——**素材路径一律走这个**。
    清单文件这类非素材传 False，只挡越界和隐藏文件，否则 playlist.json
    会因为"不是媒体文件"被自己拦下来。
    """
    if not raw:
        return None
    s = str(raw).replace("\\", "/").strip()
    if not s or s.startswith("/"):
        return None
    parts = []
    for seg in s.split("/"):
        if not seg or seg == "." or seg == "..":
            return None
        if seg.startswith("."):
            return None
        if any(ch in seg for ch in ("\x00", "\n", "\r")):
            return None
        parts.append(seg)
    if len(parts) > 4:
        return None
    if media_only and not is_media(parts[-1]):
        return None
    return "/".join(parts)


def resolve_playlist_name(pattern: str, device: str) -> str:
    name = pattern or "playlist.json"
    if "{device}" in name:
        if not device:
            raise ValueError("清单名里用了 {device}，但没给设备名")
        name = name.replace("{device}", device)
    return name


def sha256_file(path, chunk=1024 * 1024) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            block = f.read(chunk)
            if not block:
                break
            h.update(block)
    return h.hexdigest()


# ---------------------------------------------------------------- 清单

@dataclass
class PlaylistOptions:
    image_duration: int = 8
    muted: bool = True
    with_hash: bool = True
    asset_base_url: str = ""
    preset: dict = field(default_factory=dict)


def load_preset(src: Path, preset_path=None, log=None) -> dict:
    """preset 决定播放顺序和额外规则（时段、停留秒数等）"""
    path = Path(preset_path) if preset_path else (Path(src) / PRESET_NAME)
    if not path.is_file():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        if log:
            log(f"！preset 解析失败，已忽略：{e}")
        return {}
    if log:
        log(f"读取 preset：{path}")
    return data if isinstance(data, dict) else {}


def collect_files(src: Path, log=None):
    """扫素材目录，返回 [(相对路径, 绝对路径, kind)]，按自然序排列"""
    found = []
    for p in Path(src).rglob("*"):
        if not p.is_file():
            continue
        if p.name.startswith("."):
            continue
        if is_playlist_name(p.name):
            continue
        rel = p.relative_to(src).as_posix()
        kind = kind_of(p.name)
        if kind is None:
            if log:
                log(f"  跳过非素材文件：{rel}")
            continue
        found.append((rel, p, kind))

    # 按路径逐段做自然序：2_xxx 排在 10_xxx 前面
    found.sort(key=lambda t: [natural_key(seg) for seg in t[0].split("/")])
    return found


def build_playlist(src: Path, opts: PlaylistOptions, log=None):
    """扫目录生成清单。返回 (playlist, files, base)"""
    files = collect_files(src, log)
    if not files:
        raise ValueError(f"{src} 里没有找到任何图片或视频")

    preset = opts.preset or {}
    default_rules = preset.get("defaults") or {}
    preset_items = preset.get("items") or []

    image_duration = default_rules.get("imageDurationSec", opts.image_duration)
    muted = default_rules.get("muted", opts.muted)

    if log:
        log(f"扫描到 {len(files)} 个素材，开始算指纹…")

    base = {}
    for rel, path, kind in files:
        # durationSec 不在这里写：跟 defaults 一致的话是冗余，端侧会自己取默认值
        item = {"file": rel, "type": kind, "size": path.stat().st_size}
        if opts.with_hash:
            item["sha256"] = sha256_file(path)
        base[rel] = (item, path)

    # preset 里列到的排在前面（顺序就是 preset 的顺序），没列到的按自然序追加
    ordered, seen = [], set()
    for rule in preset_items:
        rel = (rule or {}).get("file")
        if not rel or rel not in base:
            if rel and log:
                log(f"！preset 里的 {rel} 找不到对应文件，已跳过")
            continue
        item, _ = base[rel]
        for k, v in rule.items():
            if k != "file":
                item[k] = v
        ordered.append(item)
        seen.add(rel)
    for rel, (item, _) in base.items():
        if rel not in seen:
            ordered.append(item)

    playlist = make_playlist(
        ordered,
        image_duration=image_duration,
        muted=muted,
        volume=default_rules.get("volume", 1.0),
        asset_base_url=opts.asset_base_url,
    )
    return playlist, files, base


def make_playlist(items, *, image_duration=8, muted=True, volume=1.0, asset_base_url=""):
    """
    由「已排好序的 items」组装清单。

    网页后台走的就是这条路：素材已经在远端了，顺序由用户在页面上拖出来，
    这里只负责按规范组装。和 build_playlist 共用同一份 defaults 写法，
    免得两条路生成的清单长得不一样。
    """
    clean = []
    for it in items or []:
        if not isinstance(it, dict):
            continue
        rel = it.get("file")
        if not rel:
            continue
        clean.append(it)

    playlist = {
        "schemaVersion": SCHEMA_VERSION,
        "revision": make_revision(clean),
        "defaults": {
            "imageDurationSec": image_duration,
            "muted": muted,
            "volume": 0.0 if muted else volume,
        },
        "items": clean,
    }
    if asset_base_url:
        playlist["assetBaseUrl"] = asset_base_url
    return playlist


def make_revision(items) -> str:
    h = hashlib.sha256()
    for it in items:
        h.update(str(it.get("file") or "").encode("utf-8"))
        h.update(str(it.get("sha256") or it.get("size") or 0).encode("utf-8"))
    stamp = datetime.now().strftime("%Y%m%d-%H%M")
    return f"{stamp}-{h.hexdigest()[:8]}"


def playlist_text(playlist) -> str:
    return json.dumps(playlist, ensure_ascii=False, indent=2) + "\n"
