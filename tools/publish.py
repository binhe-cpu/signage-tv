#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门店屏 · 素材发布工具（命令行）

把一个本地素材目录，发布成「电视端能直接拉的清单 + 素材」：

    本地 media/                      远端（对象存储 / 局域网 Web 根）
    ├── 01_秋季促销.jpg        →      ├── 01_秋季促销.jpg
    ├── 02_新品视频.mp4        →      ├── 02_新品视频.mp4
    ├── 03_会员日.jpg          →      ├── 03_会员日.jpg
    └── ...                           └── playlist.json   ← 电视端每 5 分钟来拉这个

素材没变就不重传（比对 sha256），所以日常「只换一张图」只传一张。

清单的生成逻辑在 signage_core.py，和网页后台（server/signage_admin.py）共用一份，
免得两边生成的清单不一致。

用法：

    # 1) 发到本机目录 / 网络共享目录（局域网方案，最省事）
    python publish.py --src ./media --target "local:D:/web/signage"

    # 2) 发到腾讯云 COS（走 S3 兼容协议，先设好环境变量）
    #    set SIGNAGE_S3_ENDPOINT=https://cos.ap-shanghai.myqcloud.com
    #    set SIGNAGE_S3_REGION=ap-shanghai
    #    set AWS_ACCESS_KEY_ID=...        （COS 的 SecretId）
    #    set AWS_SECRET_ACCESS_KEY=...    （COS 的 SecretKey）
    #    注意：COS 的存储桶名必须带 APPID 后缀，所以是 mybucket-1250000000
    #    桶权限设成「公有读私有写」就够了，不用传 --acl
    python publish.py --src ./media --target s3://mybucket-1250000000/signage

    # 3) 10 家店各自一份清单，同一个素材目录
    python publish.py --src ./media --target s3://mybucket-1250000000/signage \
        --playlist "playlist-{device}.json" --device "A店"

字段规范见 ../docs/playlist.md。
"""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from signage_core import (  # noqa: E402
    MIME,
    PRESET_NAME,
    PlaylistOptions,
    build_playlist,
    content_type,
    human,
    is_media,
    load_preset,
    playlist_text,
    resolve_playlist_name,
)


def log(msg=""):
    print(msg, flush=True)


# ---------------------------------------------------------------- local 目标

def publish_local(target_dir: Path, files, base, playlist, playlist_name, args):
    target_dir.mkdir(parents=True, exist_ok=True)

    old = read_local_json(target_dir / playlist_name)
    known = {it.get("file"): it for it in (old.get("items") or []) if it.get("file")}

    todo, skipped = [], 0
    for rel, path, _ in files:
        item, src_path = base[rel]
        dst = target_dir / rel
        old_item = known.get(rel)

        if not dst.is_file():
            todo.append(rel)
            continue
        if dst.stat().st_size != src_path.stat().st_size:
            todo.append(rel)
            continue
        if args.hash and old_item and old_item.get("sha256") != item.get("sha256"):
            todo.append(rel)
            continue
        skipped += 1

    uploaded, total_bytes = 0, 0
    for rel in todo:
        dst = target_dir / rel
        size = base[rel][1].stat().st_size
        if args.dry_run:
            log(f"  [计划] 上传 {rel}  ({human(size)})")
        else:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(base[rel][1], dst)
            log(f"  上传 {rel}  ({human(size)})")
        uploaded += 1
        total_bytes += size

    # 写清单：一定要在素材都到位之后再写，否则端侧可能拉到清单却没素材
    text = playlist_text(playlist)
    if args.dry_run:
        log(f"  [计划] 写 {playlist_name}（{len(playlist['items'])} 项）")
    else:
        (target_dir / playlist_name).write_text(text, encoding="utf-8")
        log(f"  写 {playlist_name}（{len(playlist['items'])} 项，revision {playlist['revision']}）")

    deleted = 0
    if args.prune:
        keep = {rel for rel, _, _ in files} | {playlist_name}
        for p in sorted(target_dir.rglob("*")):
            if not p.is_file():
                continue
            rel = p.relative_to(target_dir).as_posix()
            if rel in keep:
                continue
            if not is_media(p.name):
                continue
            if args.dry_run:
                log(f"  [计划] 删除远端多余 {rel}")
                deleted += 1
                continue
            p.unlink()
            log(f"  删除远端多余 {rel}")
            deleted += 1

    return dict(uploaded=uploaded, skipped=skipped, deleted=deleted, bytes=total_bytes)


def read_local_json(path: Path):
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}


# ---------------------------------------------------------------- s3 目标

def make_s3_client(endpoint, region, sig_version="s3v4"):
    try:
        import boto3
        from botocore.config import Config as BotoConfig
    except ImportError:
        raise SystemExit(
            "！S3 目标需要 boto3，先装一下：\n"
            "    pip install boto3\n"
            "  （只想发到本机/共享目录的话，用 --target local:D:/web/signage，不需要 boto3）"
        )

    return boto3.client(
        "s3",
        endpoint_url=endpoint or os.environ.get("SIGNAGE_S3_ENDPOINT") or None,
        region_name=region or os.environ.get("SIGNAGE_S3_REGION") or None,
        aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID"),
        aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY"),
        config=BotoConfig(signature_version=sig_version, s3={"addressing_style": "virtual"}),
    )


def publish_s3(bucket, prefix, files, base, playlist, playlist_name, args):
    s3 = make_s3_client(args.endpoint, args.region, args.sig_version)
    key_prefix = (prefix.strip("/") + "/") if prefix.strip("/") else ""

    old = {}
    try:
        obj = s3.get_object(Bucket=bucket, Key=key_prefix + playlist_name)
        old = json.loads(obj["Body"].read().decode("utf-8"))
    except Exception:
        old = {}
    known = {it.get("file"): it for it in (old.get("items") or []) if it.get("file")}

    remote_sizes = {}
    try:
        token = None
        while True:
            kw = {"Bucket": bucket, "Prefix": key_prefix or "", "MaxKeys": 1000}
            if token:
                kw["ContinuationToken"] = token
            resp = s3.list_objects_v2(**kw)
            for o in resp.get("Contents") or []:
                remote_sizes[o["Key"][len(key_prefix):]] = o["Size"]
            if not resp.get("IsTruncated"):
                break
            token = resp.get("NextContinuationToken")
    except Exception as e:
        log(f"！列出远端对象失败（不影响上传，只影响清理）：{e}")

    extra = {}
    if args.acl:
        extra["ACL"] = args.acl

    uploaded, skipped, total_bytes = 0, 0, 0
    for rel, path, _ in files:
        item, src_path = base[rel]
        key = key_prefix + rel
        size = src_path.stat().st_size
        old_item = known.get(rel)

        same = (
            remote_sizes.get(rel) == size
            and (not args.hash or (old_item and old_item.get("sha256") == item.get("sha256")))
        )
        if same:
            skipped += 1
            continue

        if args.dry_run:
            log(f"  [计划] 上传 {rel}  ({human(size)})")
        else:
            s3.upload_file(
                str(src_path), bucket, key,
                ExtraArgs={**extra, "ContentType": content_type(rel)},
            )
            log(f"  上传 {rel}  ({human(size)})")
        uploaded += 1
        total_bytes += size

    body = playlist_text(playlist).encode("utf-8")
    if args.dry_run:
        log(f"  [计划] 写 {playlist_name}（{len(playlist['items'])} 项）")
    else:
        s3.put_object(
            Bucket=bucket, Key=key_prefix + playlist_name, Body=body,
            ContentType=MIME["json"], CacheControl="no-cache", **extra,
        )
        log(f"  写 {playlist_name}（{len(playlist['items'])} 项，revision {playlist['revision']}）")

    deleted = 0
    if args.prune:
        keep = {rel for rel, _, _ in files} | {playlist_name}
        stale = [k for k in remote_sizes if is_media(k.rsplit("/", 1)[-1]) and k not in keep]
        for k in stale:
            if args.dry_run:
                log(f"  [计划] 删除远端多余 {k}")
                deleted += 1
                continue
            s3.delete_object(Bucket=bucket, Key=key_prefix + k)
            log(f"  删除远端多余 {k}")
            deleted += 1

    return dict(uploaded=uploaded, skipped=skipped, deleted=deleted, bytes=total_bytes)


# ---------------------------------------------------------------- 入口

def main():
    ap = argparse.ArgumentParser(
        description="门店屏素材发布工具：生成 playlist.json 并增量上传",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--src", required=True, help="本地素材目录")
    ap.add_argument("--target", required=True,
                    help="发布目标：local:<目录> 或 s3://<bucket>[/<前缀>]")
    ap.add_argument("--playlist", default="playlist.json",
                    help="清单文件名，可含 {device} 占位（默认 playlist.json）")
    ap.add_argument("--device", default="", help="替换清单名里的 {device}")
    ap.add_argument("--preset", default=None,
                    help=f"规则文件路径（默认 <src>/{PRESET_NAME}），用来定播放顺序和时段")
    ap.add_argument("--image-duration", type=int, default=8, help="图片默认停留秒数（默认 8）")
    ap.add_argument("--muted", dest="muted", action="store_true", default=True, help="静音（默认）")
    ap.add_argument("--unmuted", dest="muted", action="store_false", help="不静音")
    ap.add_argument("--asset-base-url", default="", help="写进清单的素材基址，留空则端侧取清单同目录")
    ap.add_argument("--no-prune", dest="prune", action="store_false", default=True,
                    help="不清理远端多余素材")
    ap.add_argument("--no-hash", dest="hash", action="store_false", default=True,
                    help="不算 sha256（更快，但同尺寸换内容检测不到）")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不动任何文件")
    ap.add_argument("--endpoint", default="", help="S3 兼容 endpoint")
    ap.add_argument("--region", default="", help="S3 region")
    ap.add_argument("--acl", default="", help="对象 ACL，如 public-read")
    ap.add_argument("--sig-version", default="s3v4", choices=["s3v4", "s3"],
                    help="S3 签名版本，默认 s3v4；COS 若报签名错误可试 s3")
    args = ap.parse_args()

    src = Path(args.src).expanduser().resolve()
    if not src.is_dir():
        raise SystemExit(f"！素材目录不存在：{src}")

    try:
        playlist_name = resolve_playlist_name(args.playlist, args.device)
    except ValueError as e:
        raise SystemExit(f"！{e}")

    log(f"素材目录：{src}")
    log(f"发布目标：{args.target}")
    log(f"清单名称：{playlist_name}")
    log("")

    opts = PlaylistOptions(
        image_duration=args.image_duration,
        muted=args.muted,
        with_hash=args.hash,
        asset_base_url=args.asset_base_url,
        preset=load_preset(src, args.preset, log),
    )
    try:
        playlist, files, base = build_playlist(src, opts, log)
    except ValueError as e:
        raise SystemExit(f"！{e}")

    log(f"清单项数：{len(playlist['items'])}，revision：{playlist['revision']}")
    log("")

    if args.target.startswith("local:"):
        target_dir = Path(args.target[len("local:"):]).expanduser()
        if not target_dir.is_absolute():
            target_dir = (Path.cwd() / target_dir).resolve()
        log(f"→ {target_dir}")
        result = publish_local(target_dir, files, base, playlist, playlist_name, args)
    elif args.target.startswith("s3://"):
        rest = args.target[len("s3://"):]
        bucket, _, prefix = rest.partition("/")
        if not bucket:
            raise SystemExit("！s3:// 后面要跟 bucket 名")
        log(f"→ s3://{bucket}/{prefix}")
        result = publish_s3(bucket, prefix, files, base, playlist, playlist_name, args)
    else:
        raise SystemExit("！--target 只支持 local:<目录> 或 s3://<bucket>/<前缀>")

    log("")
    verb = "计划" if args.dry_run else "完成"
    log(
        f"{verb}：上传 {result['uploaded']} 个"
        + (f"（{human(result['bytes'])}）" if result["bytes"] else "")
        + f"，跳过 {result['skipped']} 个，清理 {result['deleted']} 个"
    )
    if result["uploaded"] == 0 and result["deleted"] == 0:
        log("远端已经是最新的，什么都没动。")


if __name__ == "__main__":
    main()
