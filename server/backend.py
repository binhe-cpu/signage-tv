#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门店屏 · 存储后端

把「文件放哪儿」抽象出来，服务端代码不关心具体是本地目录还是对象存储。

  LocalBackend  本地 / 网络共享目录。浏览器不能直传，由服务代写。
  S3Backend     COS / OSS 等 S3 兼容对象存储。签发预签名 URL 让浏览器直传，
                服务不转发字节——传 500MB 视频也不占服务器带宽。

统一约定：所有方法收到的都是**已经过 core.safe_rel_path 校验的相对路径**，
内部仍然再复核一次（纵深防御），绝不拿未经校验的字符串拼路径。
"""

from __future__ import annotations

import os
import shutil
import sys
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_TOOLS = _HERE.parent / "tools"
for _p in (str(_HERE), str(_TOOLS)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import signage_core as core  # noqa: E402


# ---------------------------------------------------------------- 客户端

def make_s3_client(endpoint="", region="", sig_version="s3v4"):
    try:
        import boto3
        from botocore.config import Config as BotoConfig
    except ImportError:
        raise RuntimeError("对象存储目标需要 boto3，先安装：pip install boto3")

    return boto3.client(
        "s3",
        endpoint_url=endpoint or os.environ.get("SIGNAGE_S3_ENDPOINT") or None,
        region_name=region or os.environ.get("SIGNAGE_S3_REGION") or None,
        aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID"),
        aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY"),
        config=BotoConfig(signature_version=sig_version, s3={"addressing_style": "virtual"}),
    )


# ---------------------------------------------------------------- 本地目录

class LocalBackend:
    """本地或网络共享目录。浏览器不能直传，走服务代传。"""

    kind = "local"

    def __init__(self, root):
        self.root = Path(root).expanduser()
        self.root.mkdir(parents=True, exist_ok=True)

    def describe(self):
        return {
            "kind": self.kind,
            "label": "本地目录",
            "target": str(self.root),
            "directUpload": False,
        }

    def check(self):
        """实测能不能写——不许拿拼出来的路径当"存在"用"""
        probe = self.root / ".signage-probe"
        try:
            probe.write_bytes(b"1")
            probe.unlink()
            return None
        except Exception as e:
            return f"{self.root} 写不进去：{e}"
        finally:
            try:
                probe.unlink()
            except Exception:
                pass

    def _abs(self, rel: str) -> Path:
        # 这里用宽松校验（media_only=False）：清单文件不是媒体，也得能写。
        # 素材路径的严格校验（必须是图片/视频）在 API 入口做过了，这里是纵深防御。
        if core.safe_rel_path(rel, media_only=False) is None:
            raise ValueError(f"路径不合法：{rel}")
        p = (self.root / rel).resolve()
        root = self.root.resolve()
        if p != root and root not in p.parents:
            raise ValueError(f"路径越界：{rel}")
        return p

    def list_objects(self):
        out = {}
        if not self.root.is_dir():
            return out
        for p in self.root.rglob("*"):
            if not p.is_file():
                continue
            name = p.name
            if core.is_playlist_name(name):
                continue
            rel = p.relative_to(self.root).as_posix()
            out[rel] = p.stat().st_size
        return out

    def read_text(self, rel):
        p = self.root / rel
        if not p.is_file():
            return None
        try:
            return p.read_text(encoding="utf-8")
        except Exception:
            return None

    def write_text(self, rel, text):
        p = self._abs(rel)
        p.parent.mkdir(parents=True, exist_ok=True)
        tmp = p.with_name(p.name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, p)

    def put_file(self, local_path, rel):
        dst = self._abs(rel)
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_name(dst.name + ".part")
        shutil.copy2(local_path, tmp)
        os.replace(tmp, dst)
        return dst.stat().st_size

    def delete_objects(self, rels):
        done, failed = 0, []
        for rel in rels:
            try:
                p = self._abs(rel)
                if not p.is_file():
                    failed.append(f"{rel}：文件不存在")
                    continue
                p.unlink()
                done += 1
            except Exception as e:
                failed.append(f"{rel}：{e}")
        self._prune_dirs()
        return done, failed

    def _prune_dirs(self):
        """收掉空掉的子目录，免得文件管理器里留一堆空壳"""
        try:
            dirs = [d for d in self.root.rglob("*") if d.is_dir()]
            dirs.sort(key=lambda d: len(d.parts), reverse=True)
            for d in dirs:
                try:
                    if not any(d.iterdir()):
                        d.rmdir()
                except Exception:
                    pass
        except Exception:
            pass

    def presign_put(self, rel, expires=7200):
        """本地目录没法让浏览器直传"""
        return None

    def presign_get(self, rel, expires=3600):
        """本地目录也没有预签名这一说：预览由服务直接读文件返回"""
        return None

    def abs_path(self, rel):
        """本地模式下拿到真实文件路径（预览/下载用）。其他后端返回 None。"""
        return self._abs(rel)


# ---------------------------------------------------------------- 对象存储

class S3Backend:
    """COS / OSS 等 S3 兼容对象存储。浏览器拿预签名 URL 直传。"""

    kind = "s3"

    def __init__(self, bucket, prefix="", client=None,
                 endpoint="", region="", sig_version="s3v4"):
        self.bucket = bucket
        self.prefix = (prefix.strip("/") + "/") if prefix.strip("/") else ""
        self.client = client or make_s3_client(endpoint, region, sig_version)

    @property
    def target(self):
        return f"s3://{self.bucket}/{self.prefix}".rstrip("/")

    def describe(self):
        return {
            "kind": self.kind,
            "label": "对象存储",
            "target": self.target,
            "directUpload": True,
        }

    def check(self):
        try:
            self.client.list_objects_v2(Bucket=self.bucket, Prefix=self.prefix, MaxKeys=1)
            return None
        except Exception as e:
            return f"访问 {self.target} 失败：{e}"

    def _key(self, rel: str) -> str:
        # 同样是宽松校验：写清单也走这里，playlist.json 不是媒体扩展名。
        # 严格校验（必须是图片/视频）在 API 入口做，这里是纵深防御。
        if core.safe_rel_path(rel, media_only=False) is None:
            raise ValueError(f"路径不合法：{rel}")
        return self.prefix + rel

    def list_objects(self):
        out = {}
        token = None
        while True:
            kw = {"Bucket": self.bucket, "Prefix": self.prefix, "MaxKeys": 1000}
            if token:
                kw["ContinuationToken"] = token
            resp = self.client.list_objects_v2(**kw)
            for o in resp.get("Contents") or []:
                key = o["Key"]
                rel = key[len(self.prefix):] if key.startswith(self.prefix) else key
                if not rel or rel.endswith("/"):
                    continue
                if core.is_playlist_name(rel.rsplit("/", 1)[-1]):
                    continue
                out[rel] = o["Size"]
            if not resp.get("IsTruncated"):
                break
            token = resp.get("NextContinuationToken")
        return out

    def read_text(self, rel):
        try:
            obj = self.client.get_object(Bucket=self.bucket, Key=self.prefix + rel)
            return obj["Body"].read().decode("utf-8")
        except Exception:
            return None

    def write_text(self, rel, text):
        self.client.put_object(
            Bucket=self.bucket,
            Key=self._key(rel),
            Body=text.encode("utf-8"),
            ContentType=core.MIME["json"],
            CacheControl="no-cache",
        )

    def put_file(self, local_path, rel):
        self.client.upload_file(
            str(local_path), self.bucket, self._key(rel),
            ExtraArgs={"ContentType": core.content_type(rel)},
        )
        return Path(local_path).stat().st_size

    def delete_objects(self, rels):
        done, failed = 0, []
        for rel in rels:
            try:
                self.client.delete_object(Bucket=self.bucket, Key=self._key(rel))
                done += 1
            except Exception as e:
                failed.append(f"{rel}：{e}")
        return done, failed

    def presign_put(self, rel, expires=7200):
        """
        签发预签名 PUT。

        刻意**不带 ContentType**：带了它就会进签名，浏览器发的 Content-Type
        只要有一点差异（多一个 charset 之类）就是 403，而且报错完全看不出原因。
        不给它签，浏览器就能自由发；我们的端侧是按扩展名判断图片/视频的，
        不依赖这个 header，所以没有损失。
        """
        try:
            return self.client.generate_presigned_url(
                "put_object",
                Params={"Bucket": self.bucket, "Key": self.prefix + rel},
                ExpiresIn=expires,
                HttpMethod="PUT",
            )
        except Exception:
            return None

    def presign_get(self, rel, expires=3600):
        """
        签发预签名 GET，给网页看素材内容用。

        同样**不带** response-content-disposition / response-content-type 这类附加参数：
        它们会一起进签名，存储服务对参数的处理只要有一点差异就是 403，报错还看不出原因。
        服务端拿到这个 URL 后回 302，浏览器自己去对象存储拉，不占服务器带宽。

        有效期给一小时：URL 是每次请求现签的，够浏览器把图/视频拉完就行。
        """
        try:
            return self.client.generate_presigned_url(
                "get_object",
                Params={"Bucket": self.bucket, "Key": self._key(rel)},
                ExpiresIn=expires,
                HttpMethod="GET",
            )
        except Exception:
            return None

    def abs_path(self, rel):
        """对象存储没有本地路径，预览走预签名 URL"""
        return None


# ---------------------------------------------------------------- 工厂

def make_backend(target: str, **kw):
    """按目标字符串建后端：local:<目录> 或 s3://<bucket>[/<前缀>]"""
    t = (target or "").strip()
    if t.startswith("local:"):
        path = t[len("local:"):].strip()
        if not path:
            raise ValueError("local: 后面要跟目录路径")
        return LocalBackend(path)
    if t.startswith("s3://"):
        rest = t[len("s3://"):]
        bucket, _, prefix = rest.partition("/")
        if not bucket:
            raise ValueError("s3:// 后面要跟 bucket 名")
        return S3Backend(bucket=bucket, prefix=prefix, **kw)
    raise ValueError("存储目标只支持 local:<目录> 或 s3://<bucket>[/<前缀>]")
