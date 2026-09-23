#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门店屏 · 网页后台服务

只依赖标准库 + boto3，服务器上有 python3 和 boto3 就能跑。
不起框架，是为了让这个服务能长期无人值守——依赖越少，出问题的地方越少，
迁移的时候也就是拷几个文件。

**也可以打成不依赖 Python 的独立 exe**（server/build-exe.py，见 server/README.md）。
打成 exe 之后有两点不一样：

    1. 数据目录默认变成 **exe 旁边的 data/**（源码运行是 server/data/）。
       单文件 exe 每次启动都解到新的临时目录，状态存那儿等于每次重启都重置。
    2. 支持在 exe 旁边放一个 `signage-server.json` 写死启动参数，双击就能跑
       （双击时命令行是空的，环境变量对双击运行等于没有）。命令行 > 环境变量 >
       配置文件 > 内置默认。storage 都没给时默认发到 exe 旁边的 site/。

接口一览：

    GET  /                    网页
    GET  /app.js /app.css     前端资源
    GET  /api/health          探活（不需要登录）
    POST /api/login           换登录凭证
    POST /api/logout
    POST /api/password        改访问码（改完其他设备上的登录立刻失效）
    GET  /api/state           线上现状：清单 + 素材 + 存储位置
    GET  /api/devices         十块屏的状态台账：谁在线、在播哪一版、上次报什么错
    GET  /api/preview?file=x  看素材内容：对象存储回 302 直连，本地目录服务直读
    POST /api/presign         签发直传凭证（对象存储）/ 告知代传（本地目录）
    POST /api/upload?name=xx  代传通道（本地目录模式用）
    POST /api/publish         写清单（items 为空 = 下架全部远端内容，需要显式 allowEmpty）
    POST /api/delete          删素材（顺带从清单摘掉）

    POST /api/report          屏上报自己的状态，**不需要登录**，只写自己那一格

    GET  /<清单名>            电视端拉清单，**不需要登录**
    GET  /<素材相对路径>      电视端下素材，**不需要登录**

后面这几条是给电视用的：端侧只会"拉一份 JSON、下几个文件、POST 一段状态"，没有 cookie，
也没有输访问码的地方（十块屏不可能各配一个口令）。所以它们免登录——
**代价是能访问到这个端口的人就能读到全部素材和清单、也能往台账里写一条假设备**。
内容本来就是挂店门口的海报，可以接受；但别把这台服务器直接丢到公网上。
不想要这个出口：环境变量 SIGNAGE_PUBLIC_READ=0（或命令行 --no-public-read）。
对象存储模式下本来就不需要它（端侧直连桶），默认关闭。

环境变量：

    SIGNAGE_ADMIN_CODE     访问码。设了就以它为准，**页面上改不了** —— 命令行/环境变量
                           是"更高一级"的来源，页面上改了下次重启又变回去，不如直接拒掉。
                           不设就随机生成一次并存进 data/code.json，之后重启不变，页面上也能改。
    SIGNAGE_DATA_DIR       数据目录，默认是 server/data/。访问码这个"状态"就存在这里。
                           忘了访问码：删掉 <数据目录>/code.json，重启会重新生成并打日志。
    SIGNAGE_STORAGE        存储目标：s3://<bucket>[/<前缀>] 或 local:<目录>
    SIGNAGE_PLAYLIST       清单文件名，默认 playlist.json
    SIGNAGE_ASSET_BASE_URL 写进清单的素材基址，一般留空（端侧取清单同目录）
    SIGNAGE_ADVERTISE_BASE 对外基址，如 http://192.168.1.100:8600。
                           **Docker 里必须设**：容器里探到的本机 IP 是容器内网地址
                           （172.x），电视连不上，启动日志会打印错地址。
                           页面上那张「电视端填什么」卡片不受影响 —— 它按浏览器地址栏算。
    SIGNAGE_PUBLIC_READ    1/0，要不要给电视端开免登录的清单/素材出口。
                           本地目录模式默认开（不开电视就没地方拉），对象存储模式默认关。
    SIGNAGE_REPORT_TOKEN   状态上报的额外口令。**默认不要设**：端侧没地方填它，
                           设了屏会静默报不上来。只有把这个端口放到公网时才需要。
    SIGNAGE_S3_ENDPOINT    S3 兼容 endpoint，如 https://cos.ap-shanghai.myqcloud.com
    SIGNAGE_S3_REGION      如 ap-shanghai
    AWS_ACCESS_KEY_ID      COS 的 SecretId
    AWS_SECRET_ACCESS_KEY  COS 的 SecretKey
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import socket
import sys
import tempfile
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

# Windows 上这个服务被冻成 exe 之后，标准输出用的是「系统 ANSI 代码页」——
# 中文系统是 cp936（没事），英文系统是 cp1252，一 print 中文就 UnicodeEncodeError
# 把进程崩掉。启动日志、访问码、电视端地址全在 stdout 上，崩了就是窗口一闪，
# 连原因都看不见。
#
# 注意：**PYTHONIOENCODING 对冻过的 exe 不起作用**（实测过，它只认系统代码页），
# 而且双击运行时是控制台（走 UTF-8，中文没问题），只有输出被接进管道或文件里
# 才会炸——CI 上正是这个情形。所以只能自己把自己锁成 UTF-8。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

_HERE = Path(__file__).resolve().parent
# 打成独立 exe（PyInstaller）之后有「两个目录」，必须分开：
#   _BUNDLE_DIR —— 只读的打包内容（web/ 页面）。单文件模式下每次启动都解到临时目录
#   _APP_DIR    —— exe 所在目录。data/、配置这些要活到下次启动的东西放这里
# 混用会出两种很难查的毛病：页面 404（web/ 在一个进程退出就没的临时目录里），
# 或者访问码每次重启都换一个（存进临时目录了）。
_FROZEN = bool(getattr(sys, "frozen", False))
if _FROZEN:
    _BUNDLE_DIR = Path(getattr(sys, "_MEIPASS", Path(sys.executable).parent))
    _APP_DIR = Path(sys.executable).resolve().parent
else:
    _BUNDLE_DIR = _HERE
    _APP_DIR = _HERE
for _p in (str(_HERE), str(_HERE.parent / "tools")):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import signage_core as core  # noqa: E402
from backend import make_backend  # noqa: E402

ADMIN_VERSION = "1.2.0"
COOKIE_NAME = "signage_admin"
SESSION_TTL = 14 * 24 * 3600          # 登录有效期 14 天
JSON_MAX_BYTES = 2 * 1024 * 1024      # JSON 请求体上限
UPLOAD_MAX_BYTES = 2 * 1024 ** 3      # 代传单文件上限 2GB
CODE_MIN_LEN = 4
CODE_MAX_LEN = 64
WEB_DIR = _BUNDLE_DIR / "web"
CONFIG_NAME = "signage-server.json"

STATIC_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".png": "image/png",
    ".webmanifest": "application/manifest+json",
}


class ApiError(Exception):
    def __init__(self, message, status=400):
        super().__init__(message)
        self.status = status


def log(msg=""):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def lan_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return "127.0.0.1"


def public_base(port: int) -> str:
    """这台服务对外的基址。只用于「往日志和屏幕上写地址」。

    跑在容器里的时候，上面的 lan_ip() 探到的是**容器内网 IP**（172.x 那种），
    电视根本连不上——容器的网络是隔离的，看不见宿主机所在的局域网。
    所以容器场景必须用 SIGNAGE_ADVERTISE_BASE 把真实地址喂进来。
    """
    base = (os.environ.get("SIGNAGE_ADVERTISE_BASE") or "").strip().rstrip("/")
    if base:
        return base
    return f"http://{lan_ip()}:{port}"


# ---------------------------------------------------------------- 访问码

def data_dir() -> Path:
    """访问码这类「状态」存在哪。

    源码运行默认 server/data/；**打成 exe 之后默认是 exe 旁边的 data/** —— 单文件
    exe 每次启动都解到一个新的临时目录，把访问码存那儿等于每次重启换一个码。
    用 SIGNAGE_DATA_DIR 可以换地方。
    """
    return Path(os.environ.get("SIGNAGE_DATA_DIR") or (_APP_DIR / "data"))


def load_config() -> dict:
    """
    exe 旁边的启动配置，用来支持「双击就跑」。

    双击 exe 时命令行是空的，而环境变量对双击运行等于没有，所以总得有个地方写死
    存储路径这类参数。看两个位置，先 exe 同目录（更可靠：双击时工作目录可能是任何
    地方），再看当前目录。都没有就返回 {}。

    文件坏了、或者不是 JSON 对象，一律当作没有 —— 起不来比起得不对更糟，宁可退回
    默认值。返回的字典里带 `_path` 表示这份配置是从哪读来的（打日志用）。
    """
    cands = [_APP_DIR / CONFIG_NAME]
    cwd = Path.cwd() / CONFIG_NAME
    if cwd.resolve() != cands[0].resolve():
        cands.append(cwd)
    for p in cands:
        if not p.is_file():
            continue
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
        except Exception as e:
            log(f"！配置文件读不了（当作没有）：{p} —— {e}")
            return {}
        if not isinstance(data, dict):
            log(f"！配置文件不是一个 JSON 对象（当作没有）：{p}")
            return {}
        data["_path"] = str(p)
        return data
    return {}


def cfg_str(cfg, key):
    """配置里取字符串。写了数字/布尔也认，转成字符串"""
    v = cfg.get(key)
    if v is None:
        return ""
    if isinstance(v, bool):
        return "1" if v else "0"
    return str(v).strip()


def cfg_bool(cfg, key):
    """配置里取布尔。**没写这个键返回 None**，好和「明确写了 false」区分开"""
    if key not in cfg:
        return None
    v = cfg.get(key)
    if isinstance(v, bool):
        return v
    s = str(v).strip().lower()
    if s in ("1", "true", "yes", "on"):
        return True
    if s in ("0", "false", "no", "off"):
        return False
    return None


class CodeStore:
    """
    访问码放在哪。

    三种来源，优先级从高到低：

    1. **命令行 / 环境变量** —— 设了就以它为准，并且**页面上改不了**。来自环境变量的码
       在页面上改完，下次重启又变回去 —— 那种"改了没用"比不让改更坏。
    2. **数据目录里的 code.json** —— 页面上改的就是它，重启保持不变。
    3. 都没有 —— 随机生成一个，**立刻写进文件**（所以重启不变），并把码打进日志。
       老版本是随机生成、重启就换的，每次重启都要翻日志找码。
    """

    def __init__(self, path, fixed=""):
        self.path = Path(path)
        self.fixed = (fixed or "").strip()
        self.fresh = False            # 本次启动是不是刚生成的码（要打日志告诉人）
        self._code = ""

        if self.fixed:
            self._code = self.fixed
            self.origin = "命令行 / 环境变量"
            self.editable = False
            return

        saved = self._read_file()
        if saved:
            self._code = saved
            self.origin = "配置文件 %s" % self.path
            self.editable = True
            return

        self._code = secrets.token_urlsafe(9)
        self.editable = True
        try:
            self._write_file(self._code)
            self.fresh = True
            self.origin = "随机生成，已存到 %s" % self.path
        except Exception as e:
            # 数据目录写不了（只读挂载、权限不对）：服务照跑，但码每次重启都换
            self.origin = "随机生成，写不进文件（%s）：重启会换一个" % e
            self.editable = False

    @property
    def code(self) -> str:
        return self._code

    @property
    def enabled(self) -> bool:
        return bool(self._code)

    def set_code(self, new: str) -> None:
        """改码并落盘。写失败会抛异常，由调用方把原因原样说给用户听"""
        if not self.editable:
            raise RuntimeError("这个访问码来自%s，页面上改不了" % self.origin)
        self._write_file(new)
        self._code = new
        self.fresh = False
        self.origin = "配置文件 %s" % self.path

    def _read_file(self) -> str:
        try:
            if not self.path.is_file():
                return ""
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception as e:
            log("！访问码文件读不出来（%s），先当作没设，稍后会重新生成：%s" % (e, self.path))
            return ""
        if not isinstance(data, dict):
            return ""
        return str(data.get("code") or "").strip()

    def _write_file(self, code: str) -> None:
        # 先写临时文件再改名：断电不会留下半截 JSON —— 那种文件读出来是空码，
        # 等于门锁坏了，比打不开更糟
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name("." + self.path.name + ".part")
        tmp.write_text(json.dumps({"code": code}, ensure_ascii=False, indent=2),
                       encoding="utf-8")
        os.replace(tmp, self.path)


# ---------------------------------------------------------------- 设备上报

# 上报体里只认这些字段，别的一律丢掉。
# 这个接口**免登录**（十块屏不可能各配一个口令），所以进来的东西不能直接信：
# 不认识的键会让文件越写越大，超长字符串同理。全部按白名单收 + 截断。
REPORT_TEXT_FIELDS = {
    "device": 64,           # 人起的名字，比如「前台」「3号试衣间」
    "id": 64,               # 机器自己报的稳定标识，没填 device 时用它认人
    "version": 40,
    "mode": 16,
    "playlistUrl": 500,
    "revision": 80,
    "message": 500,
    "storageLabel": 120,
    "storageRoot": 300,
    "screen": 20,
    "playingFile": 200,
    "lastError": 500,
}
REPORT_NUM_FIELDS = {
    "playlistTotal": (0, 100000),
    "playableNow": (0, 100000),
    "manualCount": (0, 100000),
    "localCount": (0, 100000),
    "remoteCount": (0, 100000),
    "playingIndex": (-1, 100000),
    "runningSec": (-1, 10 ** 9),
    "reportIntervalSec": (30, 86400),
}
REPORT_BOOL_FIELDS = ("online", "storageWritable")

# 心跳断了这么久就把这条记录清掉。屏被拆走、换机器之后，别在页面上留一年
REPORT_KEEP_SEC = 30 * 24 * 3600
# 最多认这么多台。有人拿脚本往里灌也不能把文件撑爆
REPORT_MAX_DEVICES = 200
# 判断"离线"的最短宽限：上报间隔再短也至少给这么久。
# 少了它的话，间隔 60 秒的屏只要抖一次就被标成离线，页面上红成一片反而没人看了
REPORT_OFFLINE_MIN_SEC = 900


class ReportStore:
    """
    每块屏的状态快照。

    **为什么要落盘**：跑在云上的话，服务重启一次内存就空了，页面变成"一台设备都没有" ——
    而屏全是好的、正在播。那种假警报比不显示更糟（会让人跑去店里看）。
    落盘之后重启还能看见上一次的心跳。

    **时间一律用服务器收到的时刻**：老电视的系统时钟经常是错的（差几年都见过），
    拿端侧报上来的时间戳判在线，会出现"这台屏显示离线 800 天"这种事。
    """

    def __init__(self, path):
        self.path = Path(path)
        self._lock = threading.Lock()
        self._data = self._read()

    def snapshot(self):
        with self._lock:
            return {k: dict(v) for k, v in self._data.items()}

    def update(self, body, ip, now):
        """收一条上报。返回 (设备键, 这条记录)"""
        rec = {}
        for k, limit in REPORT_TEXT_FIELDS.items():
            v = body.get(k)
            if v is None:
                continue
            s = str(v).strip()[:limit]
            if s:
                rec[k] = s
        for k, (lo, hi) in REPORT_NUM_FIELDS.items():
            v = body.get(k)
            # bool 在 Python 里是 int 的子类，得先挡掉，不然 True 会变成 1
            if isinstance(v, bool) or not isinstance(v, (int, float)):
                continue
            rec[k] = max(lo, min(hi, int(v)))
        for k in REPORT_BOOL_FIELDS:
            v = body.get(k)
            if isinstance(v, bool):
                rec[k] = v

        # 认人顺序：人起的名字 > 机器报的标识 > 来源 IP。
        # 名字最符合直觉（店里就按「前台」「西墙」说事），IP 是最后兜底 ——
        # 路由器重启换个 IP，同一台屏就会被当成新设备，所以只作兜底
        name = rec.get("device") or ""
        key = name or rec.get("id") or ("ip:" + ip)

        with self._lock:
            old = self._data.get(key) or {}
            rec["key"] = key
            rec["ip"] = ip
            rec["lastSeen"] = now
            rec["firstSeen"] = old.get("firstSeen") or now
            rec["reports"] = int(old.get("reports") or 0) + 1
            self._data[key] = rec
            self._prune(now)
            self._write()
        return key, rec

    def rows(self, now):
        """给页面用的列表：算好在线与否，在线的排前面"""
        out = []
        for rec in self.snapshot().values():
            interval = rec.get("reportIntervalSec") or 300
            limit = max(int(interval) * 3, REPORT_OFFLINE_MIN_SEC)
            silent = max(0, int(now - (rec.get("lastSeen") or 0)))
            rec["silentSec"] = silent
            rec["offlineAfterSec"] = limit
            rec["online"] = silent <= limit
            out.append(rec)
        # 在线的在前、心跳新的在前：一眼看到的就是"现在什么情况"
        out.sort(key=lambda r: (not r["online"], -r["lastSeen"]))
        return out

    def _prune(self, now):
        # 先按时间清（屏拆走了就别留着），再按数量截（防止被灌）
        for k in [k for k, v in self._data.items()
                  if now - (v.get("lastSeen") or 0) > REPORT_KEEP_SEC]:
            self._data.pop(k, None)
        if len(self._data) > REPORT_MAX_DEVICES:
            keep = sorted(self._data.items(), key=lambda kv: -(kv[1].get("lastSeen") or 0))
            self._data = dict(keep[:REPORT_MAX_DEVICES])

    def _read(self):
        try:
            if not self.path.is_file():
                return {}
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except Exception as e:
            log("！设备上报文件读不出来（%s），先当作空的：%s" % (e, self.path))
            return {}
        if not isinstance(data, dict):
            return {}
        raw = data.get("devices")
        if not isinstance(raw, dict):
            return {}
        # 文件是别人（或旧版本）写的也不能信，逐条按白名单过一遍
        out = {}
        for k, v in raw.items():
            if isinstance(v, dict) and isinstance(k, str):
                out[k[:200]] = v
        return out

    def _write(self):
        # 临时文件 + 改名：断电不会留下半截 JSON（那种文件下次读出来是空台账，
        # 页面又变成"一台设备都没有"）
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_name("." + self.path.name + ".part")
            tmp.write_text(
                json.dumps({"devices": self._data}, ensure_ascii=False, indent=1),
                encoding="utf-8")
            os.replace(tmp, self.path)
        except Exception as e:
            # 写不进去（只读挂载）也得让上报本身成功：台账丢了顶多重启后看见空白，
            # 而报失败会让端侧退让、页面彻底看不到设备
            log("！设备台账写不进去：%s" % e)


# ---------------------------------------------------------------- 登录凭证

def make_token(code: str, ttl=SESSION_TTL) -> str:
    exp = int(time.time()) + ttl
    sig = hmac.new(code.encode("utf-8"), str(exp).encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{sig}.{exp}"


def verify_token(code: str, token) -> bool:
    """凭证就是 code 的 HMAC——不用额外的密钥文件，改访问码就让旧登录全部失效"""
    if not token or "." not in token:
        return False
    sig, _, exp_str = token.rpartition(".")
    try:
        exp = int(exp_str)
    except ValueError:
        return False
    if exp < time.time():
        return False
    want = hmac.new(code.encode("utf-8"), exp_str.encode("utf-8"), hashlib.sha256).hexdigest()
    return hmac.compare_digest(sig, want)


def _same_secret(a, b) -> bool:
    """
    定长比较两个访问码。

    比**字节**而不是比字符串：hmac.compare_digest 碰到非 ASCII 会直接抛 TypeError，
    而访问码用中文是很自然的选择。
    """
    return hmac.compare_digest(str(a).encode("utf-8"), str(b).encode("utf-8"))


# ---------------------------------------------------------------- 请求处理

class Handler(BaseHTTPRequestHandler):

    protocol_version = "HTTP/1.1"
    server_version = f"SignageAdmin/{ADMIN_VERSION}"

    # -------------------------------------------------- 基础设施

    @property
    def backend(self):
        return self.server.backend

    @property
    def admin_code(self) -> str:
        """当前访问码。挂在 store 上而不是 Server 上 —— 页面上能改它"""
        return self.server.code_store.code

    def log_message(self, fmt, *args):
        log(f"{self.address_string()} {fmt % args}")

    def _send(self, status, body, ctype="application/json; charset=utf-8", headers=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD" and body:
            self.wfile.write(body)

    def _json(self, obj, status=200, headers=None):
        self._send(status, json.dumps(obj, ensure_ascii=False), headers=headers)

    def _drain(self, n):
        """
        把请求体读掉再回响应。

        不读就关连接的话，客户端看到的是「网络中断」——真实的拒绝原因
        （格式不支持、路径不合法之类）会被这个假象盖掉。App 端踩过这个坑。
        """
        remain = int(n or 0)
        buf = bytearray(64 * 1024)
        while remain > 0:
            chunk = self.rfile.read(min(remain, len(buf)))
            if not chunk:
                break
            remain -= len(chunk)

    def _read_json(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        if n > JSON_MAX_BYTES:
            self._drain(n)
            raise ApiError(f"请求体太大（{n} 字节，上限 {JSON_MAX_BYTES}）", 413)
        raw = self.rfile.read(n)
        try:
            data = json.loads(raw.decode("utf-8"))
        except Exception as e:
            raise ApiError(f"请求不是合法 JSON：{e}")
        if not isinstance(data, dict):
            raise ApiError("请求体必须是一个 JSON 对象")
        return data

    def _cookie(self, name):
        raw = self.headers.get("Cookie") or ""
        for part in raw.split(";"):
            k, _, v = part.strip().partition("=")
            if k == name:
                return unquote(v)
        return None

    def _authed(self) -> bool:
        return verify_token(self.admin_code, self._cookie(COOKIE_NAME))

    def _require_auth(self):
        if not self._authed():
            raise ApiError("没登录或登录已过期，请重新输入访问码", 401)

    # -------------------------------------------------- 路由

    def do_GET(self):
        self._route("GET")

    def do_HEAD(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def _route(self, method):
        try:
            path, query = self._parse_target()
            self._dispatch(method, path, query)
        except ApiError as e:
            self._safe_json({"ok": False, "error": str(e)}, e.status)
        except BrokenPipeError:
            pass
        except Exception as e:
            traceback.print_exc()
            self._safe_json({"ok": False, "error": f"{type(e).__name__}: {e}"}, 500)

    def _safe_json(self, obj, status):
        try:
            self._json(obj, status)
        except Exception:
            pass

    def _parse_target(self):
        u = urlparse(self.path)
        return u.path, parse_qs(u.query)

    def _dispatch(self, method, path, query):
        # 静态资源
        if method == "GET" and (path == "/" or path in ("/index.html", "/app.js", "/app.css")):
            return self._serve_static("index.html" if path == "/" else path.lstrip("/"))

        if path == "/api/health":
            return self._json({"ok": True, "version": ADMIN_VERSION})

        if method == "GET":
            if path == "/api/state":
                self._require_auth()
                return self._json(self._build_state())
            # 十块屏的状态台账。跟 /api/state 分开，是因为页面上要单独**定时刷新**它 ——
            # 跟着整个 state 一起刷会顺手重建播放顺序列表，用户正在输「停留秒数」时会被冲掉
            if path == "/api/devices":
                self._require_auth()
                return self._json(self._do_devices())
            if path == "/api/preview":
                self._require_auth()
                return self._do_preview(query)
            if path.startswith("/api/"):
                raise ApiError("没有这个接口：" + path, 404)
            # 剩下的都交给电视端出口。它吃任意路径，所以必须放在最后
            # ——放前面会把 /api/* 和静态资源一起吞掉。
            return self._do_public(path)

        if method == "POST":
            if path == "/api/login":
                return self._do_login()
            if path == "/api/logout":
                return self._json({"ok": True},
                                  headers={"Set-Cookie": self._clear_cookie()})
            # 电视端的状态上报：**故意免登录**，理由见 _do_report。
            # 必须在 _require_auth() 前面 —— 十块屏没地方填访问码
            if path == "/api/report":
                return self._do_report()

            self._require_auth()

            if path == "/api/state":
                return self._json(self._build_state())
            if path == "/api/password":
                return self._do_password(self._read_json())
            if path == "/api/presign":
                return self._do_presign(self._read_json())
            if path == "/api/upload":
                return self._do_upload(query)
            if path == "/api/publish":
                return self._do_publish(self._read_json())
            if path == "/api/delete":
                return self._do_delete(self._read_json())
            raise ApiError("没有这个接口：" + path, 404)

        raise ApiError("不支持的方法：" + method, 405)

    # -------------------------------------------------- 静态文件

    def _serve_static(self, rel):
        target = (WEB_DIR / rel).resolve()
        web_root = WEB_DIR.resolve()
        if web_root not in target.parents and target != web_root:
            raise ApiError("路径不合法", 403)
        if not target.is_file():
            raise ApiError(f"缺少前端文件：{target}", 500)
        ctype = STATIC_TYPES.get(target.suffix.lower(), "application/octet-stream")
        self._send(200, target.read_bytes(), ctype)

    # -------------------------------------------------- 素材内容

    def _do_preview(self, query):
        """
        把素材内容给浏览器看。

        两条路，按存储位置分：
          对象存储 → 回 302 到预签名 GET，浏览器自己去拉，服务不转发字节；
          本地目录 → 服务直接读文件返回（带 Range 支持）。

        只服务图片/视频：清单文件这类不给通过这里读出去。
        """
        raw = (query.get("file") or [""])[0]
        rel = core.safe_rel_path(raw)
        if rel is None:
            raise ApiError(f"素材路径不合法或不是图片/视频：{raw!r}")

        url = self.backend.presign_get(rel)
        if url:
            # 302 之后浏览器带着 URL 直接去对象存储，图片能显示、视频能放，
            # 不经过服务器，看多大的视频都不占服务器带宽。
            self.send_response(302)
            self.send_header("Location", url)
            self.send_header("Content-Length", "0")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            return

        self._serve_local_file(rel)

    def _serve_local_file(self, rel, cache="private, max-age=60"):
        """
        本地目录模式：服务自己把文件吐出去。

        Range 必须支持——视频拖进度条全靠它，不支持的话浏览器只能从头播，
        有的干脆直接报错。所有可能抛异常的事都在发响应头之前做完，
        一旦开始写 body 就没法再回错误了。
        """
        resolver = getattr(self.backend, "abs_path", None)
        path = resolver(rel) if resolver else None
        if path is None or not path.is_file():
            raise ApiError(f"线上没有这个素材：{rel}", 404)

        total = path.stat().st_size
        ctype = core.content_type(rel)
        start, end, status = 0, max(0, total - 1), 200

        rng = self.headers.get("Range") or ""
        if rng.startswith("bytes="):
            spec = rng[len("bytes="):].split(",")[0].strip()
            a, _, b = spec.partition("-")
            try:
                if not a:                       # bytes=-500：最后 500 字节
                    start = max(0, total - int(b))
                else:
                    start = int(a)
                    if b:                       # bytes=100-299 /
                        end = min(int(b), total - 1)
            except ValueError:
                raise ApiError(f"Range 请求头看不懂：{rng!r}", 416)

            if start > end or start >= total:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            status = 206

        length = max(0, end - start + 1)
        if status == 200:
            start, length = 0, total

        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        # 页面预览是私密内容，别让中间层长期缓存；
        # 电视端出口是公开的，用 public
        self.send_header("Cache-Control", cache)
        self.end_headers()

        if self.command == "HEAD" or length == 0:
            return

        with path.open("rb") as f:
            f.seek(start)
            remain = length
            buf = bytearray(256 * 1024)
            while remain > 0:
                chunk = f.read(min(remain, len(buf)))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remain -= len(chunk)

    # -------------------------------------------------- 电视端出口

    def _do_public(self, path):
        """
        电视端拿内容的地方，**故意免登录**。

        端侧只会两个 HTTP 动作：拉一份 JSON、下几个文件。它没有 cookie，也没有
        填访问码的地方（十块屏不可能各配一个口令，何况换码的时候屏全在店里）。
        所以这里只做路径白名单：清单名精确匹配 + 图片/视频相对路径，**只读，不写**。

        为什么不复用 /api/preview：那条路要登录，而且它按"给人的预览"设计
        （对象存储回 302 到预签名 URL，URL 一小时就过期，端侧存下来会失效）。

        代价说清楚：能访问到这个端口的人就能读到全部素材和清单。
        放出去的都是门店海报，可以接受；但别把这台服务器直接暴露到公网。
        """
        if not self.server.public_read:
            raise ApiError(
                "这个后台没有开电视端出口。对象存储模式不用它——"
                "电视端直接填桶的域名，例如 https://<桶>.cos.<地域>.myqcloud.com/"
                + self.server.playlist_name
                + "（桶要设成公有读）",
                404,
            )

        rel = unquote(path.lstrip("/"))
        if not rel:
            raise ApiError("这是电视端取内容的地址。要在浏览器里管理内容，打开 / 就行", 404)

        name = self.server.playlist_name
        if rel == name:
            text = self.backend.read_text(name)
            if text is None:
                raise ApiError(f"还没发布过清单（{name}），先在页面下面点「发布到电视」", 404)
            # no-store：改了内容最迟 5 分钟就要让屏上看到，不能让中间层咬着旧版本
            self._send(200, text, "application/json; charset=utf-8")
            return

        # 素材。safe_rel_path 默认 media_only=True：只认图片和视频，
        # 顺手把 ../ 上跳、绝对路径、隐藏文件一起挡掉。
        safe = core.safe_rel_path(rel)
        if safe is None:
            raise ApiError(f"这里只提供图片和视频，没有：{rel}", 404)
        self._serve_local_file(safe, cache="public, max-age=60")

    # -------------------------------------------------- 设备上报

    def _do_report(self):
        """
        收一块屏报上来的状态。**免登录**，跟 _do_public 一个理由：
        十块屏没地方输访问码（换码的时候屏全挂在店里），而且它只能写自己那一格。

        进来的东西一律按白名单收（见 REPORT_TEXT_FIELDS），超长的截断 ——
        免登录的写接口，默认当成"有人在乱灌"来防。

        要不要口令看 SIGNAGE_REPORT_TOKEN：**默认不要**。端侧根本没有填口令的地方，
        凭空要一个只会让新装的屏静默不上报，而这一点在页面上看不出来。
        """
        body = self._read_json()
        if not isinstance(body, dict):
            raise ApiError("上报内容得是一个 JSON 对象")

        token = self.server.report_token
        if token:
            got = (self.headers.get("X-Report-Token") or "").strip()
            if not _same_secret(got, token):
                # 403 而不是 401：这套里的 401 表示"该登录了"，别把两件事混成一个信号
                raise ApiError("上报口令不对", 403)

        ip = self.client_address[0]
        key, rec = self.server.reports.update(body, ip, time.time())
        if int(rec.get("reports") or 1) == 1:
            # 只在第一次见到这台设备时打日志，免得十块屏把日志刷满
            log(f"新设备开始上报：{key}（{rec.get('version') or '版本未知'} 来自 {ip}）")
        self._json({"ok": True, "device": key, "seenAt": int(rec["lastSeen"])})

    def _do_devices(self):
        """十块屏现在的状况：谁在线、在播哪一版、上次报错是什么"""
        now = time.time()
        rows = self.server.reports.rows(now)
        playlist, _ = self._load_playlist()
        return {
            "ok": True,
            "now": int(now),
            "onlineCount": sum(1 for r in rows if r["online"]),
            "total": len(rows),
            # 线上现在是哪一版。跟每台屏报上来的 revision 比一下就知道谁还没更新上去
            "currentRevision": (playlist or {}).get("revision"),
            "devices": rows,
        }

    # -------------------------------------------------- 登录

    def _cookie_header(self, token):
        return (f"{COOKIE_NAME}={token}; Path=/; HttpOnly; SameSite=Strict; "
                f"Max-Age={SESSION_TTL}")

    def _clear_cookie(self):
        return f"{COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"

    def _do_login(self):
        body = self._read_json()
        code = str(body.get("code") or "")
        if not code:
            raise ApiError("请输入访问码")
        if not _same_secret(code, self.admin_code):
            time.sleep(0.5)   # 让暴力猜码变慢
            raise ApiError("访问码不对", 401)
        token = make_token(self.admin_code)
        log("登录成功")
        self._json({"ok": True}, headers={"Set-Cookie": self._cookie_header(token)})

    def _do_password(self, body):
        """
        改访问码。

        要求重新输一遍当前码，哪怕这个请求本来就带着有效凭证：改码会把所有设备踢下线，
        这种操作不该只凭浏览器里存着的一个 cookie 就放行。

        改完给**当前这台**发一个新凭证。其他设备的旧凭证因为签名对不上立刻失效 ——
        那正是改码想要的效果；顺手把自己也踢出去就纯属添乱了。
        """
        store = self.server.code_store
        if not store.editable:
            raise ApiError(
                "这个访问码来自%s，页面上改不了。要改就改一下 SIGNAGE_ADMIN_CODE"
                "（或 --admin-code）再重启服务。" % store.origin
            )

        cur = str(body.get("current") or "")
        new = str(body.get("next") or "").strip()
        confirm = str(body.get("confirm") or "").strip()

        if not _same_secret(cur, store.code):
            time.sleep(0.5)          # 让暴力猜码变慢
            # 用 400 而不是 401：401 会被前端当成"登录过期"而踢回登录页，
            # 把"当前码打错了"这个具体原因盖掉。这里的人本来就是登录着的
            raise ApiError("当前访问码不对", 400)

        if not new:
            # 这个后台是长期挂在公网上的，不该有"不带锁"的状态。
            # 想彻底重新来过：删掉数据目录里的 code.json，重启会生成一个新的
            raise ApiError("新访问码不能空着。要换就直接填新的；"
                           "想彻底重新来过，可以删掉服务器上的 code.json 再重启。")
        if len(new) < CODE_MIN_LEN:
            raise ApiError("新访问码太短了，至少 %d 位" % CODE_MIN_LEN)
        if len(new) > CODE_MAX_LEN:
            raise ApiError("新访问码太长了（最多 %d 个字符）" % CODE_MAX_LEN)
        if "\n" in new or "\r" in new:
            raise ApiError("新访问码里不能有换行")
        if confirm and confirm != new:
            raise ApiError("两次输入的新访问码不一样")
        if _same_secret(new, store.code):
            raise ApiError("新访问码和现在的一样，不用改")

        try:
            store.set_code(new)
        except Exception as e:
            raise ApiError("写入访问码失败：%s（路径 %s）" % (e, store.path))

        log("访问码已改（%d 个字符）" % len(new))
        token = make_token(new)
        return self._json(
            {"ok": True, "editable": store.editable, "origin": store.origin},
            headers={"Set-Cookie": self._cookie_header(token)},
        )

    # -------------------------------------------------- 线上现状

    def _load_playlist(self):
        raw = self.backend.read_text(self.server.playlist_name)
        if not raw:
            return None, None
        try:
            data = json.loads(raw)
        except Exception as e:
            return None, f"清单文件不是合法 JSON：{e}"
        if not isinstance(data, dict):
            return None, "清单文件不是一个 JSON 对象"
        return data, None

    def _code_info(self):
        """
        页面上的「安全设置」卡片靠这个回显。

        **不回传访问码本身** —— 页面上没有需要看到它的地方，少一处泄露就少一处。
        忘了码就去服务器上看 data/code.json，或者删掉它重启换一个。
        """
        store = self.server.code_store
        return {
            "editable": store.editable,
            "origin": store.origin,
            "minLen": CODE_MIN_LEN,
            "maxLen": CODE_MAX_LEN,
        }

    def _build_state(self):
        problem = self.backend.check()
        objects = self.backend.list_objects()
        playlist, playlist_error = self._load_playlist()

        items = []
        if playlist:
            for it in playlist.get("items") or []:
                if isinstance(it, dict) and it.get("file"):
                    items.append(dict(it))

        in_list = {it["file"] for it in items}
        missing = sorted(f for f in in_list if f not in objects)
        orphans = sorted(f for f in objects if f not in in_list)

        rows = []
        for rel, size in sorted(
            objects.items(),
            key=lambda kv: [core.natural_key(seg) for seg in kv[0].split("/")],
        ):
            rows.append({
                "file": rel,
                "size": size,
                "type": core.kind_of(rel) or "file",
                "inPlaylist": rel in in_list,
            })

        return {
            "ok": True,
            "version": ADMIN_VERSION,
            "code": self._code_info(),
            "storage": {**self.backend.describe(), "problem": problem},
            "playlistName": self.server.playlist_name,
            "publicRead": self.server.public_read,
            "playlist": {
                "exists": playlist is not None,
                "error": playlist_error,
                "revision": (playlist or {}).get("revision"),
                "defaults": (playlist or {}).get("defaults") or {},
                "items": items,
            },
            "objects": rows,
            "missing": missing,
            "orphans": orphans,
        }

    # -------------------------------------------------- 上传

    def _do_presign(self, body):
        names = body.get("names")
        if not isinstance(names, list) or not names:
            raise ApiError("names 必须是非空数组")

        out, mode = [], "proxy"
        for raw in names:
            rel = core.safe_rel_path(str(raw or ""))
            if rel is None:
                raise ApiError(f"文件名不合法或不是图片/视频：{raw!r}")
            url = self.backend.presign_put(rel)
            entry = {"name": rel}
            if url:
                entry["url"] = url
                mode = "direct"
            out.append(entry)

        return self._json({
            "ok": True,
            "mode": mode,
            "files": out,
            "maxBytes": UPLOAD_MAX_BYTES,
        })

    def _do_upload(self, query):
        """本地目录模式的代传通道：边读边写临时文件，再原子落位"""
        name = (query.get("name") or [""])[0]
        rel = core.safe_rel_path(name)
        if rel is None:
            self._drain(int(self.headers.get("Content-Length") or 0))
            raise ApiError(f"文件名不合法或不是图片/视频：{name!r}")

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            raise ApiError("缺少 Content-Length，无法接收文件")
        if length > UPLOAD_MAX_BYTES:
            self._drain(length)
            raise ApiError(f"文件太大（{core.human(length)}，上限 "
                           f"{core.human(UPLOAD_MAX_BYTES)}）", 413)

        tmp_fd, tmp_path = tempfile.mkstemp(prefix="signage-up-", suffix=".part")
        os.close(tmp_fd)
        tmp = Path(tmp_path)
        written = 0
        try:
            with tmp.open("wb") as f:
                remain = length
                buf = bytearray(256 * 1024)
                while remain > 0:
                    chunk = self.rfile.read(min(remain, len(buf)))
                    if not chunk:
                        break
                    f.write(chunk)
                    written += len(chunk)
                    remain -= len(chunk)

            if written != length:
                raise ApiError(f"上传中断：收到 {written} / {length} 字节")

            size = self.backend.put_file(tmp, rel)
            log(f"代传完成：{rel}（{core.human(size)}）")
            return self._json({"ok": True, "name": rel, "size": size})
        except ApiError:
            raise
        except Exception as e:
            raise ApiError(f"保存 {rel} 失败：{e}")
        finally:
            try:
                tmp.unlink()
            except Exception:
                pass

    # -------------------------------------------------- 发布

    def _do_publish(self, body):
        raw_items = body.get("items")
        if not isinstance(raw_items, list):
            raise ApiError("items 必须是数组")
        if not raw_items and not body.get("allowEmpty"):
            # 空清单是**允许**的（下架全部远端内容，屏会退回本地目录），但它也是
            # 最容易误操作的破坏：一次手滑把十块屏都清空。所以要一个显式意图 ——
            # 页面在用户自己点了确认之后才带上 allowEmpty，别的东西误发空数组会被挡下。
            raise ApiError("清单是空的。确认要把远端内容全部下架的话，请勾选/确认后再发布。")

        objects = self.backend.list_objects()
        out, missing = [], []
        for raw in raw_items:
            src = raw if isinstance(raw, dict) else {}
            rel = core.safe_rel_path(str(src.get("file") or ""))
            if rel is None:
                raise ApiError(f"素材路径不合法：{src.get('file')!r}")
            if rel not in objects:
                missing.append(rel)
                continue

            item = {"file": rel, "type": core.kind_of(rel), "size": objects[rel]}
            self._fill_item_options(item, src)
            out.append(item)

        if missing:
            raise ApiError(
                "这些素材在远端还没有，发布已中止（先等它们传完再点发布）："
                + "、".join(missing)
            )

        image_duration = self._int_option(body.get("imageDurationSec"), 1, 3600, 8)
        muted = bool(body.get("muted", True))

        playlist = core.make_playlist(
            out,
            image_duration=image_duration,
            muted=muted,
            asset_base_url=self.server.asset_base_url,
        )
        name = self.server.playlist_name
        self.backend.write_text(name, core.playlist_text(playlist))

        log(f"已发布 {name}：{len(out)} 项，revision {playlist['revision']}")
        return self._json({
            "ok": True,
            "revision": playlist["revision"],
            "count": len(out),
            "empty": not out,
            "playlistName": name,
        })

    def _fill_item_options(self, item, src):
        """把页面上设的可选参数搬进 item，顺手做格式校验——坏数据进了清单，端侧会当垃圾播"""
        if src.get("durationSec") not in (None, ""):
            item["durationSec"] = self._int_option(src.get("durationSec"), 1, 3600, None)

        if src.get("volume") not in (None, ""):
            try:
                v = float(src["volume"])
            except Exception:
                raise ApiError(f"volume 不是数字：{src.get('volume')!r}")
            item["volume"] = min(max(v, 0.0), 1.0)

        if src.get("muted") not in (None, ""):
            item["muted"] = bool(src["muted"])

        start, end = src.get("startTime"), src.get("endTime")
        if start not in (None, ""):
            if not self._valid_hhmm(start):
                raise ApiError(f"开始时间格式不对（要 HH:mm）：{start!r}")
            item["startTime"] = str(start)
        if end not in (None, ""):
            if not self._valid_hhmm(end):
                raise ApiError(f"结束时间格式不对（要 HH:mm）：{end!r}")
            item["endTime"] = str(end)

        days = src.get("days")
        if days not in (None, "", []):
            if not isinstance(days, list):
                raise ApiError("days 必须是数组，如 [1,2,3,4,5]")
            clean = []
            for d in days:
                try:
                    d = int(d)
                except Exception:
                    raise ApiError(f"days 里出现了非法值：{d!r}")
                if not 1 <= d <= 7:
                    raise ApiError(f"days 只能是 1~7（周一~周日）：{d}")
                clean.append(d)
            if clean:
                item["days"] = sorted(set(clean))

    @staticmethod
    def _valid_hhmm(s):
        try:
            h, m = str(s).split(":")
            return 0 <= int(h) <= 23 and 0 <= int(m) <= 59
        except Exception:
            return False

    @staticmethod
    def _int_option(value, low, high, default):
        if value in (None, ""):
            if default is None:
                raise ApiError("缺少必填的数值参数")
            return default
        try:
            v = int(value)
        except Exception:
            raise ApiError(f"不是整数：{value!r}")
        if not low <= v <= high:
            raise ApiError(f"数值超出范围（{low}~{high}）：{v}")
        return v

    # -------------------------------------------------- 删除

    def _do_delete(self, body):
        names = body.get("names")
        if not isinstance(names, list) or not names:
            raise ApiError("names 必须是非空数组")

        rels = []
        for raw in names:
            rel = core.safe_rel_path(str(raw or ""))
            if rel is None:
                raise ApiError(f"路径不合法或不是图片/视频：{raw!r}")
            rels.append(rel)

        done, failed = self.backend.delete_objects(rels)

        # 删掉的素材如果还在清单里，顺手摘掉——不然清单引用了不存在的文件，
        # 端侧会去下载它、失败、在 /status 里刷一堆错误，看着像故障。
        removed_from_playlist = []
        playlist, err = self._load_playlist()
        if playlist and not err:
            gone = set(rels)
            kept = []
            for it in playlist.get("items") or []:
                if isinstance(it, dict) and it.get("file") in gone:
                    removed_from_playlist.append(it["file"])
                    continue
                kept.append(it)

            if removed_from_playlist:
                if kept:
                    new_pl = core.make_playlist(
                        kept,
                        image_duration=(playlist.get("defaults") or {}).get("imageDurationSec", 8),
                        muted=(playlist.get("defaults") or {}).get("muted", True),
                        volume=(playlist.get("defaults") or {}).get("volume", 1.0),
                        asset_base_url=self.server.asset_base_url,
                    )
                    self.backend.write_text(self.server.playlist_name,
                                            core.playlist_text(new_pl))
                    log(f"删除并重写清单，{len(removed_from_playlist)} 项被摘除")
                else:
                    empty = core.make_playlist(
                        [],
                        image_duration=(playlist.get("defaults") or {}).get("imageDurationSec", 8),
                        muted=(playlist.get("defaults") or {}).get("muted", True),
                        asset_base_url=self.server.asset_base_url,
                    )
                    self.backend.write_text(self.server.playlist_name,
                                            core.playlist_text(empty))
                    log("删除后清单已清空")

        log(f"删除 {done} 个文件" + (f"，失败 {len(failed)} 个" if failed else ""))
        return self._json({
            "ok": not failed,
            "deleted": done,
            "failed": failed,
            "removedFromPlaylist": removed_from_playlist,
            "playlistEmptied": bool(removed_from_playlist) and not failed
                              and self._playlist_is_empty(),
            "error": ("；".join(failed) if failed else None),
        })

    def _playlist_is_empty(self):
        playlist, err = self._load_playlist()
        if not playlist or err:
            return False
        return not (playlist.get("items") or [])


# ---------------------------------------------------------------- 启动

class Server(ThreadingHTTPServer):

    daemon_threads = True          # 否则 Ctrl+C 之后进程不退出
    allow_reuse_address = True

    def __init__(self, addr, handler, backend, admin_code, playlist_name, asset_base_url,
                 code_store=None, public_read=True, report_store=None, report_token=""):
        super().__init__(addr, handler)
        self.backend = backend
        self.playlist_name = playlist_name
        self.asset_base_url = asset_base_url
        # 要不要给电视端开免登录的清单/素材出口（见 _do_public）
        self.public_read = public_read
        # 访问码挂在 store 上而不是这里的一个字符串：页面上能改它，改完立刻生效不用重启
        self.code_store = code_store or CodeStore(data_dir() / "code.json", fixed=admin_code)
        # 十块屏的状态台账（见 ReportStore）
        self.reports = report_store or ReportStore(data_dir() / "reports.json")
        # 上报可选的额外口令。默认空 = 不收口令 —— 端侧没地方填它，
        # 凭空要一个口令只会让新装的屏静默不上报。暴露到公网时才需要设
        self.report_token = (report_token or "").strip()


def build_server(args):
    cfg = load_config()

    # 取参数的顺序：命令行 > 环境变量 > exe 旁边的配置文件 > 内置默认。
    # 配置文件夹在中间，是为了「双击 exe 就跑」——那种场景下命令行和环境变量都是空的
    storage = (args.storage or os.environ.get("SIGNAGE_STORAGE") or cfg_str(cfg, "storage"))
    if not storage:
        if _FROZEN:
            # 双击的人不会去读报错，也不想敲命令行：给个能直接跑起来的默认值，
            # 清单和素材就发在 exe 旁边的 site/ 里，电视端照样能拉
            storage = "local:" + str(_APP_DIR / "site")
            log(f"！没给存储目标（--storage / SIGNAGE_STORAGE / {CONFIG_NAME}）：")
            log(f"  先用默认值，发到 exe 旁边的 site 目录：{_APP_DIR / 'site'}")
            log(f"  想改就在 exe 旁边放一个 {CONFIG_NAME}，见 README 里的示例。")
            log("")
        else:
            raise SystemExit(
                "！没指定存储目标。用 --storage 或环境变量 SIGNAGE_STORAGE 给一个，例如：\n"
                "    SIGNAGE_STORAGE=s3://mybucket-1250000000/signage\n"
                "    SIGNAGE_STORAGE=local:D:/web/signage"
            )

    try:
        backend = make_backend(storage)
    except Exception as e:
        raise SystemExit(f"！存储目标不对：{e}")

    # 访问码：环境变量/命令行给了就固定成那个（页面上改不了），没给就用数据目录里存的，
    # 实在没有才随机生成一个并**存下来** —— 老版本随机生成、重启就换，每次都要翻日志找码
    store = CodeStore(
        data_dir() / "code.json",
        fixed=(args.admin_code or os.environ.get("SIGNAGE_ADMIN_CODE")
               or cfg_str(cfg, "adminCode")),
    )

    problem = backend.check()
    if problem:
        log(f"！存储自检没过：{problem}")
        log("  （服务照常起来，但页面上的操作会失败，先把这个修好）")

    playlist_name = (args.playlist or os.environ.get("SIGNAGE_PLAYLIST")
                     or cfg_str(cfg, "playlist") or "playlist.json")
    asset_base_url = (args.asset_base_url or os.environ.get("SIGNAGE_ASSET_BASE_URL")
                      or cfg_str(cfg, "assetBaseUrl") or "")

    # 电视端出口：本地目录模式必须开——关了屏上就没地方拉内容了；
    # 对象存储模式默认关（端侧直连桶，多这一跳没意义）。
    # 优先级：命令行 --no-public-read > 环境变量 SIGNAGE_PUBLIC_READ > 配置文件 > 默认
    cfg_pr = cfg_bool(cfg, "publicRead")
    env_pr = (os.environ.get("SIGNAGE_PUBLIC_READ") or "").strip().lower()
    if getattr(args, "no_public_read", False):
        public_read = False
    elif env_pr in ("0", "false", "no", "off"):
        public_read = False
    elif env_pr in ("1", "true", "yes", "on"):
        public_read = True
    elif cfg_pr is not None:
        public_read = cfg_pr
    else:
        public_read = backend.kind == "local"

    srv = Server((args.host, args.port), Handler, backend, store.code,
                 playlist_name, asset_base_url, code_store=store, public_read=public_read,
                 report_store=ReportStore(data_dir() / "reports.json"),
                 report_token=(args.report_token or os.environ.get("SIGNAGE_REPORT_TOKEN")
                               or cfg_str(cfg, "reportToken")))

    log("")
    log(f"门店屏网页后台 v{ADMIN_VERSION}")
    if _FROZEN:
        log(f"  运行方式：独立 exe（{Path(sys.executable).name}）")
    if cfg.get("_path"):
        log(f"  启动配置：{cfg['_path']}")
    log(f"  存储目标：{backend.describe()['target']}（{backend.describe()['label']}）")
    log(f"  上传方式：{'浏览器直传对象存储' if backend.describe()['directUpload'] else '经服务代传'}")
    log(f"  清单名称：{playlist_name}")
    log(f"  数据目录：{data_dir()}")
    log("")
    log(f"  本机打开：http://127.0.0.1:{args.port}/")
    log(f"  局域网：  {public_base(args.port)}/")
    log("")
    # 电视端上报的地址是它自己从清单地址推出来的（同源 + /api/report），
    # 屏上填的清单地址就在这台机器上，所以天然对得上
    log("  电视状态回传：屏会自己往 /api/report 上报，端侧不用配")
    if srv.report_token:
        log("  ！设了 SIGNAGE_REPORT_TOKEN，但端侧没地方填这个口令，屏会一直报不上来。")
        log("    除非要把这个端口放到公网，否则去掉它。")
    log("")
    if public_read:
        log("  电视端拿内容的地址（不用登录，填在手机上：点屏呼出二维码 → 服务器地址）：")
        log(f"    {public_base(args.port)}/{playlist_name}")
        log("  ！这个地址下的清单和素材是公开可读的——内容本来就是挂门口的，")
        log("    但这台机器别把这个端口直接暴露到公网。")
    elif backend.kind == "local":
        log("  ！电视端出口是关着的：本地目录模式下，屏上没有地方去拉内容。")
        log("    要么去掉 --no-public-read / SIGNAGE_PUBLIC_READ=0，")
        log("    要么就改用对象存储（端侧直连桶）。")
    else:
        log("  电视端：直连对象存储，填桶的域名 + 清单名（桶要设成公有读）。")
        if public_read:
            log("  ！不过对象存储模式下你把电视端出口打开了：清单能给，但素材不会从这台")
            log("    服务器发出去（端侧还是得直连桶），所以这么配只有调试时才有意义。")
    log("")
    if store.fresh:
        log(f"  本次访问码：{store.code}")
        log(f"  （没设 SIGNAGE_ADMIN_CODE，这是随机生成的，已经存进 {store.path}，"
            "重启不变。登录之后可以在页面上改）")
    elif not store.editable:
        log(f"  访问码：来自{store.origin}（页面上改不了）")
    else:
        log(f"  访问码：来自{store.origin}（登录之后可以在页面上改）")
    log("")
    return srv


def main():
    ap = argparse.ArgumentParser(description="门店屏网页后台")
    ap.add_argument("--version", action="version",
                    version=f"门店屏网页后台 v{ADMIN_VERSION}")
    ap.add_argument("--host", default="", help="监听地址，默认 0.0.0.0（局域网能访问）")
    ap.add_argument("--port", type=int, default=0, help="监听端口，默认 8600")
    ap.add_argument("--storage", default="", help="s3://<bucket>[/<前缀>] 或 local:<目录>")
    ap.add_argument("--admin-code", default="", help="访问码（也可用环境变量）")
    ap.add_argument("--playlist", default="", help="清单文件名，默认 playlist.json")
    ap.add_argument("--asset-base-url", default="", help="写进清单的素材基址，一般留空")
    ap.add_argument("--report-token", default="",
                    help="状态上报口令，默认不要（端侧没地方填它，只有把端口放公网时才需要）")
    ap.add_argument("--no-public-read", action="store_true",
                    help="关掉电视端免登录的清单/素材出口（本地目录模式下关了屏就拉不到内容）")
    args = ap.parse_args()

    # 监听地址/端口也走「命令行 > 环境变量 > 配置文件 > 内置默认」这一套：
    # 双击 exe 时命令行是空的，端口只能从 exe 旁边的配置文件里来
    cfg = load_config()
    args.host = (args.host or os.environ.get("SIGNAGE_HOST")
                 or cfg_str(cfg, "host") or "0.0.0.0")
    if not args.port:
        raw = os.environ.get("SIGNAGE_PORT") or cfg_str(cfg, "port") or "8600"
        try:
            args.port = int(raw)
        except ValueError:
            raise SystemExit(f"！端口不是数字：{raw}")

    srv = build_server(args)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到中断，正在退出…")
    finally:
        srv.server_close()


if __name__ == "__main__":
    main()
