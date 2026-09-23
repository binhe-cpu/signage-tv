#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门店屏 · 网页后台自测

不连对象存储，用 local 后端把服务跑起来，然后打**真实 HTTP 请求**做断言。
关注点不是"函数算得对不对"，而是"客户端到底收到什么"——
拒绝请求时有没有把请求体读掉、错误里有没有带上真正的原因，这些只能从响应上看。

    python server/selftest.py
"""

from __future__ import annotations

import hashlib
import hmac
import http.client
import json
import os
import shutil
import sys
import threading
import time
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "tools"))

import signage_core as core          # noqa: E402
from backend import make_backend     # noqa: E402
import signage_admin as admin        # noqa: E402

CODE = "TEST-CODE-123"
ROOT = Path("D:/android-toolchain/webtest/dst")
OUTSIDE = ROOT.parent / "outside-sentinel.jpg"
# 数据目录（访问码、设备台账）指到这儿，别让自测去动真正的 server/data/
DATA = ROOT.parent / "selftest-data"

PASS = 0
FAIL = 0
PORT = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok   {name}")
    else:
        FAIL += 1
        print(f"  FAIL {name}   {detail}")


def group(title):
    print("")
    print(title)


def req(method, path, body=None, raw=None, ctype=None, cookie=None, extra=None, port=None):
    c = http.client.HTTPConnection("127.0.0.1", port or PORT, timeout=15)
    headers = dict(extra or {})
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        payload = raw
        headers["Content-Type"] = ctype or "application/octet-stream"
    if cookie:
        headers["Cookie"] = cookie
    try:
        c.request(method, path, body=payload, headers=headers)
        r = c.getresponse()
        data = r.read()
        return r.status, data, r.getheaders()
    finally:
        c.close()


def jbody(data):
    try:
        return json.loads(data.decode("utf-8"))
    except Exception:
        return {}


def enc(s):
    return urllib.parse.quote(s, safe="")


def main():
    global PORT

    if ROOT.exists():
        shutil.rmtree(ROOT)
    ROOT.mkdir(parents=True, exist_ok=True)

    # 数据目录（访问码、设备台账）一律指到测试目录去。
    # 不指的话它们会写进真正的 server/data/ —— 跑一遍自测就把运行中的访问码换掉了，
    # 那种"测试把现场搞坏"的事最难查
    if DATA.exists():
        shutil.rmtree(DATA)
    os.environ["SIGNAGE_DATA_DIR"] = str(DATA)

    backend = make_backend(f"local:{ROOT}")
    srv = admin.Server(("127.0.0.1", 0), admin.Handler, backend, CODE, "playlist.json", "")
    PORT = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    real_log = admin.log
    admin.log = lambda *a, **k: None      # 测试输出干净点

    try:
        run_all()
    finally:
        srv.shutdown()
        srv.server_close()
        admin.log = real_log

    print("")
    print(f"通过 {PASS} 项，失败 {FAIL} 项")
    return 1 if FAIL else 0


def login(code=None, port=None):
    st, data, heads = req("POST", "/api/login", body={"code": CODE if code is None else code},
                          port=port)
    for k, v in heads:
        if k.lower() == "set-cookie":
            return v.split(";", 1)[0]
    return None


def set_cookie(heads):
    for k, v in heads:
        if k.lower() == "set-cookie":
            return v.split(";", 1)[0]
    return None


def run_all():
    # ---------------------------------------------------- 1. 鉴权
    group("=== 1. 鉴权 ===")

    st, data, _ = req("GET", "/api/health")
    check("探活不需要登录", st == 200 and jbody(data).get("ok") is True, f"{st} {data[:120]}")

    st, data, _ = req("GET", "/api/state")
    check("没登录看状态被挡（401）", st == 401, f"{st} {data[:120]}")
    check("401 里说明要重新登录，而不是只有个状态码",
          "登录" in jbody(data).get("error", ""), data[:160])

    st, data, _ = req("POST", "/api/login", body={"code": "错的"})
    check("访问码不对被拒（401）", st == 401, f"{st} {data[:120]}")

    st, data, _ = req("POST", "/api/login", body={"code": ""})
    check("空访问码被拒", st == 400, f"{st} {data[:120]}")

    ck = login()
    check("访问码正确能拿到登录凭证", ck is not None and ck.startswith(admin.COOKIE_NAME + "="), str(ck))

    st, data, _ = req("GET", "/api/state", cookie=ck)
    check("带上凭证能看状态", st == 200 and jbody(data).get("ok") is True, f"{st} {data[:120]}")

    st, data, _ = req("GET", "/api/state", cookie=admin.COOKIE_NAME + "=forged.9999999999")
    check("伪造凭证被拒", st == 401, f"{st}")

    exp = int(time.time()) - 60
    old_sig = hmac.new(CODE.encode(), str(exp).encode(), hashlib.sha256).hexdigest()
    st, data, _ = req("GET", "/api/state",
                      cookie=f"{admin.COOKIE_NAME}={old_sig}.{exp}")
    check("过期凭证被拒（签名对但时间过了）", st == 401, f"{st}")

    st, _, heads = req("POST", "/api/logout", cookie=ck)
    ck2 = [v for k, v in heads if k.lower() == "set-cookie"]
    check("退出会清掉凭证", any("Max-Age=0" in v for v in ck2), str(ck2))

    ck = login()

    # ---------------------------------------------------- 2. 上传
    group("=== 2. 上传签名与落盘 ===")

    st, data, _ = req("POST", "/api/presign", body={"names": ["01_海报.jpg"]}, cookie=ck)
    j = jbody(data)
    check("申请上传凭证成功", st == 200 and j.get("ok") is True, f"{st} {data[:160]}")
    check("本地目录模式下走服务代传（mode=proxy）", j.get("mode") == "proxy", str(j.get("mode")))

    st, data, _ = req("POST", "/api/presign", body={"names": ["备注.txt"]}, cookie=ck)
    check("非图片/视频的扩展名被拒", st == 400 and "不是" in jbody(data).get("error", ""),
          f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/presign", body={"names": ["../../逃逸.jpg"]}, cookie=ck)
    check("路径里带 .. 被拒", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/presign", body={"names": ["/etc/passwd.jpg"]}, cookie=ck)
    check("绝对路径被拒", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/presign", body={"names": [".隐藏.jpg"]}, cookie=ck)
    check("点开头的隐藏文件被拒", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/presign", body={"names": []}, cookie=ck)
    check("空名单被拒", st == 400, f"{st}")

    st, data, _ = req("POST", "/api/upload?name=" + enc("01_海报.jpg"),
                      raw=b"IMG-A" * 100, cookie=ck)
    check("代传上传成功", st == 200 and jbody(data).get("ok") is True, f"{st} {data[:160]}")
    f1 = ROOT / "01_海报.jpg"
    check("文件真的落盘了，大小对得上",
          f1.is_file() and f1.stat().st_size == 500,
          f"{f1} exists={f1.is_file()} size={f1.stat().st_size if f1.is_file() else '-'}")

    st, data, _ = req("POST", "/api/upload?name=" + enc("子目录/A店_01.jpg"),
                      raw=b"IMG-C" * 60, cookie=ck)
    f2 = ROOT / "子目录" / "A店_01.jpg"
    check("带子目录的路径能落盘", st == 200 and f2.is_file(), f"{st} {data[:120]}")

    st, data, _ = req("POST", "/api/upload?name=" + enc("02_视频.mp4"),
                      raw=b"VID" * 500, cookie=ck)
    check("视频也能传", st == 200, f"{st} {data[:120]}")

    # 关键：拒绝时要把请求体读掉，否则客户端看到的是"网络中断"
    st, data, _ = req("POST", "/api/upload?name=" + enc("坏文件.txt"),
                      raw=b"x" * (256 * 1024), cookie=ck)
    check("格式不合法的上传会被拒", st == 400, f"{st} {data[:120]}")
    check("★ 被拒时也把请求体读掉了，客户端收到的是 JSON 而不是连接被重置",
          jbody(data).get("error") is not None, data[:160])

    # 穿越上传：目录外的哨兵文件必须原样不动
    OUTSIDE.write_bytes(b"ORIGINAL")
    st, data, _ = req("POST", "/api/upload?name=" + enc("../outside-sentinel.jpg"),
                      raw=b"HACKED", cookie=ck)
    check("穿越上传被拒", st == 400, f"{st} {data[:120]}")
    check("★ 目录外的文件没被动过", OUTSIDE.read_bytes() == b"ORIGINAL",
          OUTSIDE.read_bytes()[:20].decode("utf-8", "replace"))

    st, data, _ = req("POST", "/api/upload?name=" + enc("x.jpg"), raw=b"", cookie=ck)
    check("没有请求体（缺 Content-Length）被拒", st == 400, f"{st} {data[:120]}")

    st, data, _ = req("POST", "/api/upload?name=" + enc("x.jpg"),
                      raw=b"y" * 100, cookie=None)
    check("没登录不能上传", st == 401, f"{st}")

    # ---------------------------------------------------- 3. 状态
    group("=== 3. 线上现状 ===")

    st, data, _ = req("GET", "/api/state", cookie=ck)
    s = jbody(data)
    names = sorted(o["file"] for o in s.get("objects", []))
    check("状态里列出了全部素材",
          names == sorted(["01_海报.jpg", "02_视频.mp4", "子目录/A店_01.jpg"]),
          str(names))
    check("素材带上了类型", any(o.get("type") == "video" for o in s.get("objects", [])),
          json.dumps(s.get("objects", []), ensure_ascii=False))
    check("还没发布过，清单标记为不存在", s.get("playlist", {}).get("exists") is False,
          str(s.get("playlist", {}).get("exists")))
    check("状态里给出存储位置，页面能显示发到哪", 
          bool(s.get("storage", {}).get("target")), json.dumps(s.get("storage"), ensure_ascii=False))

    # ---------------------------------------------------- 4. 发布
    group("=== 4. 发布 ===")

    st, data, _ = req("POST", "/api/publish", body={"items": []}, cookie=ck)
    check("没确认就发空清单会被拒（防手滑把十块屏一次清空）", st == 400, f"{st} {data[:160]}")
    check("拒绝理由说清了后果", "下架" in jbody(data).get("error", ""), data[:200])

    st, data, _ = req("POST", "/api/publish", body={"items": [], "allowEmpty": True}, cookie=ck)
    j = jbody(data)
    check("★ 显式确认后可以发布空清单（= 下架全部远端内容）",
          st == 200 and j.get("ok") is True and j.get("empty") is True, f"{st} {data[:160]}")
    pl = json.loads((ROOT / "playlist.json").read_text(encoding="utf-8"))
    check("空清单确实写进去了，items 是空数组", pl["items"] == [], str(pl["items"]))
    check("空清单也有 revision（端侧靠它判断清单变了没）",
          bool(pl.get("revision")), str(pl.get("revision")))
    check("空清单仍带 defaults，端侧解析不会缺字段",
          isinstance(pl.get("defaults"), dict) and "imageDurationSec" in pl["defaults"],
          json.dumps(pl.get("defaults"), ensure_ascii=False))
    check("★ 发布空清单不动素材文件（重新加回来不用重传）",
          (ROOT / "01_海报.jpg").is_file() and (ROOT / "02_视频.mp4").is_file(),
          str(sorted(p.name for p in ROOT.iterdir())))

    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": "不存在的.jpg"}]}, cookie=ck)
    check("引用了线上没有的素材，发布被中止", st == 400, f"{st} {data[:160]}")
    check("错误里点名了是哪个素材", "不存在的.jpg" in jbody(data).get("error", ""), data[:200])

    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": "../../逃逸.jpg"}]}, cookie=ck)
    check("发布里的非法路径被拒", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": "01_海报.jpg", "startTime": "25:99"}]}, cookie=ck)
    check("时段格式不对被拒", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": "01_海报.jpg", "days": [0, 9]}]}, cookie=ck)
    check("星期越界被拒", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": "01_海报.jpg", "durationSec": "很久"}]}, cookie=ck)
    check("停留秒数不是数字被拒", st == 400, f"{st} {data[:160]}")

    order = ["02_视频.mp4", "01_海报.jpg"]      # 故意跟文件名顺序反着来
    st, data, _ = req("POST", "/api/publish", body={
        "items": [{"file": order[0]}, {"file": order[1], "durationSec": 15}],
        "imageDurationSec": 6,
        "muted": True,
    }, cookie=ck)
    j = jbody(data)
    check("发布成功", st == 200 and j.get("ok") is True, f"{st} {data[:160]}")
    check("返回了新的 revision", bool(j.get("revision")), str(j.get("revision")))

    pl = json.loads((ROOT / "playlist.json").read_text(encoding="utf-8"))
    got = [it["file"] for it in pl["items"]]
    check("★ 清单里的顺序 = 请求里的顺序（不靠文件名排序）", got == order, str(got))
    check("schemaVersion 是 1", pl.get("schemaVersion") == 1, str(pl.get("schemaVersion")))
    check("图片停留秒数写进了 defaults",
          pl["defaults"]["imageDurationSec"] == 6, str(pl["defaults"]))
    check("静音写进了 defaults", pl["defaults"]["muted"] is True, str(pl["defaults"]))
    check("muted 时 volume 归零", pl["defaults"]["volume"] == 0.0, str(pl["defaults"]))

    by = {it["file"]: it for it in pl["items"]}
    check("★ type 由服务端按扩展名判定",
          by["02_视频.mp4"]["type"] == "video" and by["01_海报.jpg"]["type"] == "image",
          json.dumps(by, ensure_ascii=False))
    check("★ size 由服务端从远端实际大小填（不是页面报的）",
          by["01_海报.jpg"]["size"] == 500 and by["02_视频.mp4"]["size"] == 1500,
          str((by["01_海报.jpg"]["size"], by["02_视频.mp4"]["size"])))
    check("每条自己的停留秒数写进去了", by["01_海报.jpg"].get("durationSec") == 15,
          str(by["01_海报.jpg"]))

    st, data, _ = req("GET", "/api/state", cookie=ck)
    s = jbody(data)
    check("发布后状态里清单存在且项数对",
          s["playlist"]["exists"] is True and len(s["playlist"]["items"]) == 2,
          json.dumps(s.get("playlist"), ensure_ascii=False))
    check("★ 没有 missing（清单引用的素材都在线上）", s.get("missing") == [], str(s.get("missing")))
    check("★ 没进清单的素材被标成未使用（orphans）",
          s.get("orphans") == ["子目录/A店_01.jpg"], str(s.get("orphans")))

    st, data, _ = req("POST", "/api/publish", body={
        "items": [
            {"file": "01_海报.jpg", "startTime": "09:00", "endTime": "21:00", "days": [1, 2, 3]},
        ],
        "imageDurationSec": 8,
        "muted": False,
    }, cookie=ck)
    pl = json.loads((ROOT / "playlist.json").read_text(encoding="utf-8"))
    it = pl["items"][0]
    check("时段和星期能落进清单",
          it.get("startTime") == "09:00" and it.get("endTime") == "21:00" and it.get("days") == [1, 2, 3],
          json.dumps(it, ensure_ascii=False))
    check("不静音时 volume 有值", pl["defaults"]["volume"] != 0.0, str(pl["defaults"]))

    st, data, _ = req("POST", "/api/publish", body={
        "items": [{"file": "02_视频.mp4"}, {"file": "01_海报.jpg"}],
    }, cookie=ck)
    check("重新发布覆盖旧清单", st == 200, f"{st}")

    # ---------------------------------------------------- 5. 删除
    group("=== 5. 删除 ===")

    st, data, _ = req("POST", "/api/delete", body={"names": ["../../逃逸.jpg"]}, cookie=ck)
    check("删除接口拒绝穿越路径", st == 400, f"{st} {data[:160]}")
    check("目录外哨兵文件依然没被动过", OUTSIDE.read_bytes() == b"ORIGINAL",
          OUTSIDE.read_bytes()[:20].decode("utf-8", "replace"))

    st, data, _ = req("POST", "/api/delete", body={"names": ["不存在.jpg"]}, cookie=ck)
    j = jbody(data)
    check("删不存在的文件会明确报出来", j.get("ok") is False and j.get("failed"),
          json.dumps(j, ensure_ascii=False))
    check("失败原因里带上文件名", any("不存在.jpg" in f for f in (j.get("failed") or [])),
          str(j.get("failed")))

    st, data, _ = req("POST", "/api/delete", body={"names": ["02_视频.mp4"]}, cookie=ck)
    j = jbody(data)
    check("删除成功", st == 200 and j.get("deleted") == 1, json.dumps(j, ensure_ascii=False))
    check("★ 文件真的从远端没了", not (ROOT / "02_视频.mp4").is_file(), "")
    check("★ 删掉的项同时被摘出清单（不然端侧会去下不存在的文件）",
          j.get("removedFromPlaylist") == ["02_视频.mp4"],
          json.dumps(j.get("removedFromPlaylist"), ensure_ascii=False))

    pl = json.loads((ROOT / "playlist.json").read_text(encoding="utf-8"))
    check("清单被重写后只剩剩下那条",
          [it["file"] for it in pl["items"]] == ["01_海报.jpg"],
          str([it["file"] for it in pl["items"]]))

    st, data, _ = req("POST", "/api/delete", body={"names": ["01_海报.jpg"]}, cookie=ck)
    j = jbody(data)
    check("删掉清单里最后一个后，标记清单已清空", j.get("playlistEmptied") is True,
          json.dumps(j, ensure_ascii=False))
    pl = json.loads((ROOT / "playlist.json").read_text(encoding="utf-8"))
    check("清单确实空了", pl["items"] == [], str(pl["items"]))

    st, data, _ = req("POST", "/api/delete", body={"names": ["子目录/A店_01.jpg"]}, cookie=ck)
    check("删子目录里的文件成功", st == 200 and jbody(data).get("deleted") == 1, f"{st}")
    check("空掉的子目录被收走了", not (ROOT / "子目录").exists(), str(ROOT))

    st, data, _ = req("POST", "/api/delete", body={"names": []}, cookie=ck)
    check("空名单删除被拒", st == 400, f"{st}")

    # ---------------------------------------------------- 6. 素材内容查看
    group("=== 6. 素材内容查看 ===")

    st, data, _ = req("GET", "/api/preview?file=" + enc("01_海报.jpg"))
    check("没登录不能看素材内容", st == 401, f"{st} {data[:120]}")

    blob = bytes(range(256)) * 4                    # 1024 字节，每个字节都能对账
    st, data, _ = req("POST", "/api/upload?name=" + enc("预览用.jpg"), raw=blob, cookie=ck)
    check("准备素材：上传成功", st == 200, f"{st} {data[:120]}")

    st, data, heads = req("GET", "/api/preview?file=" + enc("预览用.jpg"), cookie=ck)
    h = dict((k.lower(), v) for k, v in heads)
    check("能看到素材内容", st == 200, f"{st} {data[:140]}")
    check("★ 内容和服务端存的一模一样（没被截断/改写）", data == blob, f"{len(data)} vs {len(blob)}")
    check("Content-Type 按扩展名给对了", h.get("content-type") == "image/jpeg",
          str(h.get("content-type")))
    check("声明支持 Range（视频拖进度条靠它）", h.get("accept-ranges") == "bytes",
          str(h.get("accept-ranges")))
    check("页面私有内容标记为 private 缓存", "private" in h.get("cache-control", ""),
          str(h.get("cache-control")))

    st, data, _ = req("POST", "/api/upload?name=" + enc("预览视频.mp4"),
                      raw=b"\x00\x00\x00\x18ftyp" + b"V" * 500, cookie=ck)
    st, data, heads = req("GET", "/api/preview?file=" + enc("预览视频.mp4"), cookie=ck)
    h = dict((k.lower(), v) for k, v in heads)
    check("视频的 Content-Type 是 video/mp4", h.get("content-type") == "video/mp4",
          str(h.get("content-type")))

    st, data, _ = req("GET", "/api/preview?file=" + enc("../../逃逸.jpg"), cookie=ck)
    check("预览接口拒绝穿越路径", st == 400, f"{st} {data[:140]}")

    st, data, _ = req("GET", "/api/preview?file=" + enc("playlist.json"), cookie=ck)
    check("★ 清单文件不能从这个口子读出去（只服务图片/视频）", st == 400,
          f"{st} {data[:140]}")

    st, data, _ = req("GET", "/api/preview?file=" + enc("没有这个.jpg"), cookie=ck)
    check("素材不存在时回 404", st == 404, f"{st} {data[:140]}")
    check("404 里点名是哪个文件", "没有这个.jpg" in jbody(data).get("error", ""), data[:180])

    # Range：视频拖进度条全靠它
    st, data, heads = req("GET", "/api/preview?file=" + enc("预览用.jpg"),
                          cookie=ck, extra={"Range": "bytes=0-99"})
    h = dict((k.lower(), v) for k, v in heads)
    check("Range 头请求回 206", st == 206, f"{st}")
    check("★ 取到的是前 100 字节且内容对得上", data == blob[:100], f"{len(data)} 字节")
    check("Content-Range 写对了总长度",
          h.get("content-range") == "bytes 0-99/1024", str(h.get("content-range")))

    st, data, heads = req("GET", "/api/preview?file=" + enc("预览用.jpg"),
                          cookie=ck, extra={"Range": "bytes=100-"})
    h = dict((k.lower(), v) for k, v in heads)
    check("★ bytes=100- 从 100 取到末尾", st == 206 and data == blob[100:], f"{st} {len(data)}")
    check("这种情况的 Content-Range 一直到末尾",
          h.get("content-range") == "bytes 100-1023/1024", str(h.get("content-range")))

    st, data, _ = req("GET", "/api/preview?file=" + enc("预览用.jpg"),
                      cookie=ck, extra={"Range": "bytes=-50"})
    check("★ bytes=-50 取最后 50 字节", st == 206 and data == blob[-50:], f"{st} {len(data)}")

    st, data, heads = req("GET", "/api/preview?file=" + enc("预览用.jpg"),
                          cookie=ck, extra={"Range": "bytes=5000-6000"})
    h = dict((k.lower(), v) for k, v in heads)
    check("Range 越界回 416", st == 416, f"{st}")
    check("416 里给出真实长度，播放器能自己纠正",
          h.get("content-range") == "bytes */1024", str(h.get("content-range")))

    st, data, _ = req("GET", "/api/preview?file=" + enc("预览用.jpg"),
                      cookie=ck, extra={"Range": "bytes=abc-def"})
    check("Range 格式不对也回 416，并说明问题", st == 416 and jbody(data).get("error"),
          f"{st} {data[:140]}")

    st, data, heads = req("HEAD", "/api/preview?file=" + enc("预览用.jpg"), cookie=ck)
    h = dict((k.lower(), v) for k, v in heads)
    check("HEAD 能拿到长度但不返回内容",
          st == 200 and data == b"" and h.get("content-length") == "1024",
          f"{st} body={len(data)} len={h.get('content-length')}")

    # ---------------------------------------------------- 7. 静态与杂项
    group("=== 7. 页面与杂项 ===")

    st, data, heads = req("GET", "/")
    ct = dict((k.lower(), v) for k, v in heads).get("content-type", "")
    check("首页能打开", st == 200 and b"<!DOCTYPE html>" in data[:100], f"{st} {ct}")
    check("首页是 HTML", "text/html" in ct, ct)

    st, data, heads = req("GET", "/app.js")
    check("前端脚本能取到", st == 200 and b"function" in data, f"{st}")

    st, data, heads = req("GET", "/app.css")
    check("样式能取到", st == 200, f"{st}")

    st, data, _ = req("GET", "/../backend.py")
    check("静态资源不接受穿越", st in (400, 403, 404), f"{st}")

    st, data, _ = req("GET", "/api/" + enc("不存在的接口"), cookie=ck)
    check("不存在的接口回 404", st == 404, f"{st}")

    c = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
    body = json.dumps({"code": "错"}).encode()
    c.request("POST", "/api/login", body=body,
              headers={"Content-Type": "application/json"})
    r1 = c.getresponse(); r1.read()
    c.request("GET", "/api/health")
    r2 = c.getresponse(); d2 = r2.read()
    c.close()
    check("HTTP/1.1 长连接下连续请求不出问题（不会挂住）",
          r1.status == 401 and r2.status == 200 and b'"ok"' in d2,
          f"{r1.status} / {r2.status}")

    # ---------------------------------------------------- 8. 改访问码
    group("=== 8. 改访问码（页面上能改，改完别的设备掉线）===")

    code_dir = Path("D:/android-toolchain/webtest/data")
    if code_dir.exists():
        shutil.rmtree(code_dir)
    code_path = code_dir / "code.json"

    store = admin.CodeStore(code_path)
    check("首次启动会随机生成一个码", len(store.code) >= 8, store.code)
    check("★ 生成的码立刻落盘（老版本重启就换，每次都得翻日志找）",
          code_path.is_file(), str(code_path))
    check("新生成的码算「可改」（来源是配置文件，不是环境变量）", store.editable, store.origin)
    check("码文件里存的就是那个码",
          json.loads(code_path.read_text(encoding="utf-8"))["code"] == store.code,
          code_path.read_text(encoding="utf-8"))

    again = admin.CodeStore(code_path)
    check("★ 重启后码不变（把它存起来的全部意义）", again.code == store.code, again.code)
    check("重启后不再算「新生成」（日志里不该再打一遍明文）", not again.fresh, again.origin)
    check("来源标成配置文件，页面上据此说「能改」", "配置文件" in again.origin, again.origin)

    env_store = admin.CodeStore(code_path, fixed="ENV-CODE")
    check("★ 环境变量/命令行给的码优先于文件", env_store.code == "ENV-CODE", env_store.code)
    check("★ 那种码标成不可改（改了下次重启又变回去，不如直接拒）",
          not env_store.editable, env_store.origin)

    broken_path = code_dir / "broken.json"
    broken_path.write_text("这不是 JSON", encoding="utf-8")
    bad_store = admin.CodeStore(broken_path)
    check("码文件坏了会重新生成一个，而不是崩掉或留个空锁",
          len(bad_store.code) >= 8 and broken_path.is_file(), bad_store.code)

    # 起一个「码可改」的服务：才是线上真实形态（没设环境变量的那种）
    root2 = Path("D:/android-toolchain/webtest/code-dst")
    if root2.exists():
        shutil.rmtree(root2)
    root2.mkdir(parents=True, exist_ok=True)
    backend2 = make_backend("local:%s" % root2)
    srv2 = admin.Server(("127.0.0.1", 0), admin.Handler, backend2, store.code,
                        "playlist.json", "", code_store=store)
    port2 = srv2.server_address[1]
    threading.Thread(target=srv2.serve_forever, daemon=True).start()

    OLD = store.code
    NEW = "新码-8888"

    ck2 = login(OLD, port=port2)
    check("用当前码能登录", ck2 is not None, str(ck2))

    j = jbody(req("GET", "/api/state", cookie=ck2, port=port2)[1])
    check("/api/state 报出访问码能不能改", j.get("code", {}).get("editable") is True, str(j.get("code")))
    check("★ 不回传访问码本身（页面上没有需要看到它的地方）",
          OLD not in json.dumps(j, ensure_ascii=False), "回传里出现了明文码")

    st, data, _ = req("POST", "/api/password", body={"current": "错的", "next": NEW},
                      cookie=ck2, port=port2)
    check("★ 当前码不对就改不了，而且不会被当成「登录过期」踢回登录页",
          st == 400 and "当前访问码不对" in jbody(data).get("error", ""), f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/password", body={"current": OLD, "next": "1"},
                      cookie=ck2, port=port2)
    check("新码太短被拒", st == 400 and "太短" in jbody(data).get("error", ""), f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/password",
                      body={"current": OLD, "next": NEW, "confirm": "不一样"},
                      cookie=ck2, port=port2)
    check("两次输入不一致被拒", st == 400 and "不一样" in jbody(data).get("error", ""),
          f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/password", body={"current": OLD, "next": OLD},
                      cookie=ck2, port=port2)
    check("新码跟旧码一样就拒掉（免得以为改成功了）", st == 400, f"{st} {data[:160]}")

    st, data, _ = req("POST", "/api/password", body={"current": OLD, "next": ""},
                      cookie=ck2, port=port2)
    check("★ 不允许把访问码清空（这个后台是长期挂在公网上的）",
          st == 400 and "不能空" in jbody(data).get("error", ""), f"{st} {data[:160]}")
    check("那几次失败都没动过码", store.code == OLD, store.code)

    st, data, heads = req("POST", "/api/password",
                          body={"current": OLD, "next": NEW, "confirm": NEW},
                          cookie=ck2, port=port2)
    check("改码成功", st == 200 and jbody(data).get("ok") is True, f"{st} {data[:160]}")
    new_ck = set_cookie(heads)
    check("★ 当前这台设备直接换上新凭证（不会把自己也踢出去）", new_ck is not None, str(new_ck))
    check("★ 码已经写进文件（重启读到的也是新码）",
          store.code == NEW and
          json.loads(code_path.read_text(encoding="utf-8"))["code"] == NEW,
          code_path.read_text(encoding="utf-8"))
    check("新凭证马上能用", req("GET", "/api/state", cookie=new_ck, port=port2)[0] == 200)
    check("★ 其他设备上的旧凭证立刻失效（改码的意义就在这）",
          req("GET", "/api/state", cookie=ck2, port=port2)[0] == 401)
    check("旧码登不进去了", login(OLD, port=port2) is None)
    check("新码能登进去", login(NEW, port=port2) is not None)

    # 环境变量固定码的那台：页面上改不了，并告诉你去哪改
    srv3 = admin.Server(("127.0.0.1", 0), admin.Handler, backend2, "ENV-CODE",
                        "playlist.json", "", code_store=env_store)
    port3 = srv3.server_address[1]
    threading.Thread(target=srv3.serve_forever, daemon=True).start()
    ck3 = login("ENV-CODE", port=port3)
    st, data, _ = req("POST", "/api/password", body={"current": "ENV-CODE", "next": "想改"},
                      cookie=ck3, port=port3)
    check("★ 环境变量来的码页面上改不了，并说清该去哪改",
          st == 400 and "SIGNAGE_ADMIN_CODE" in jbody(data).get("error", ""), f"{st} {data[:200]}")
    check("那台机器的 /api/state 也标成不可改",
          jbody(req("GET", "/api/state", cookie=ck3, port=port3)[1])
          .get("code", {}).get("editable") is False)
    srv3.shutdown()
    srv3.server_close()

    # 前端
    js = req("GET", "/app.js")[1].decode("utf-8")
    check("前端有改访问码的调用", "/api/password" in js, "")
    check("前端在「不可改」时会禁用保存按钮", "saveCodeBtn" in js and "editable" in js, "")
    page = req("GET", "/")[1].decode("utf-8")
    check("页面上有安全设置卡片", 'id="codeCard"' in page and 'id="curCode"' in page, "")
    check("页面上有「安全设置」入口按钮", 'id="codeBtn"' in page and 'id="saveCodeBtn"' in page, "")

    # ---------------------------------------------------- 9. 电视端出口
    group("=== 9. 电视端出口（免登录，给屏用的） ===")

    ck = login()

    # 前面的组把素材删得差不多了，这里自己传两个再发一份清单
    plain = "电视端测试.jpg"
    # 名字里带中文和空格：路径要按百分号编码传，解码错一步就取不到文件，
    # 而端侧对这种错的表现只是「这张图不更新」，所以专门测一个
    tricky = "秋季 促销.jpg"
    req("POST", "/api/upload?name=" + enc(plain), raw=b"IMG-A" * 100, cookie=ck)
    req("POST", "/api/upload?name=" + enc(tricky), raw=b"SPACE" * 40, cookie=ck)
    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": plain}, {"file": tricky}],
                            "imageDurationSec": 8, "muted": True}, cookie=ck)
    check("（前置）先发布一份清单", st == 200, f"{st} {data[:160]}")

    st, data, _ = req("GET", "/playlist.json")
    check("★ 电视端免登录就能拉到清单", st == 200 and jbody(data).get("items") is not None,
          f"{st} {data[:120]}")
    check("清单是以 JSON 给的（不是网页）", b"schemaVersion" in data, data[:120])

    st, data, _ = req("GET", "/" + enc(plain))
    check("★ 电视端免登录就能下到素材，字节完全一致",
          st == 200 and data == b"IMG-A" * 100, f"{st} {len(data)} 字节")

    st, data, _ = req("GET", "/" + enc(tricky))
    check("★ 名字里有中文和空格也能下（空格不能被解成 +）",
          st == 200 and data == b"SPACE" * 40, f"{st} {len(data)} 字节")

    st, data, _ = req("HEAD", "/" + enc(plain))
    check("HEAD 也能问（只回长度，不回 body）", st == 200 and not data, f"{st} {len(data)} 字节")

    st, data, _ = req("GET", "/../../etc/passwd.jpg")
    check("★ 上跳的路径取不到东西", st == 404, f"{st} {data[:120]}")

    st, data, _ = req("GET", "/playlist.preset.json")
    check("清单/preset 这类文件不给通过出口读（只放图片和视频）",
          st == 404, f"{st} {data[:120]}")

    st, data, _ = req("GET", "/api/nonexistent")
    check("不存在的接口仍然回「没有这个接口」，没被电视端出口吞掉",
          st == 404 and "接口" in jbody(data).get("error", ""), f"{st} {data[:160]}")

    st, data, _ = req("GET", "/")
    check("根路径还是管理页面（出口不抢首页）", st == 200 and b"html" in data.lower(), f"{st}")

    st, data, _ = req("GET", "/app.js")
    check("/app.js 还是前端文件，没被当成素材", st == 200 and b"function" in data, f"{st}")

    st, data, _ = req("GET", "/api/state", cookie=ck)
    check("页面上能看出「电视端出口开着」", jbody(data).get("publicRead") is True,
          str(jbody(data).get("publicRead")))

    # 关掉出口：屏就拉不到了，得说清为什么，不能只回个空 404
    srv4 = admin.Server(("127.0.0.1", 0), admin.Handler, make_backend(f"local:{ROOT}"),
                        "X-CODE", "playlist.json", "", public_read=False)
    port4 = srv4.server_address[1]
    threading.Thread(target=srv4.serve_forever, daemon=True).start()
    st, data, _ = req("GET", "/playlist.json", port=port4)
    check("★ 关掉出口后电视端拉不到，并且说清了为什么",
          st == 404 and "出口" in jbody(data).get("error", ""), f"{st} {data[:220]}")
    srv4.shutdown()
    srv4.server_close()

    # 还没发布过清单：要给明确提示，不能是一个让人以为是网络问题的空响应
    fresh = ROOT.parent / "selftest-empty-online"
    if fresh.exists():
        shutil.rmtree(fresh)
    srv5 = admin.Server(("127.0.0.1", 0), admin.Handler, make_backend(f"local:{fresh}"),
                        "X-CODE", "playlist.json", "")
    port5 = srv5.server_address[1]
    threading.Thread(target=srv5.serve_forever, daemon=True).start()
    st, data, _ = req("GET", "/playlist.json", port=port5)
    check("★ 还没发布过清单时，提示去点「发布到电视」而不是干瘪的 404",
          st == 404 and "发布" in jbody(data).get("error", ""), f"{st} {data[:220]}")
    srv5.shutdown()
    srv5.server_close()

    # 前端
    js = req("GET", "/app.js")[1].decode("utf-8")
    check("前端会算出电视端地址并展示", "renderEndpoint" in js and "tvUrl" in js, "")
    check("★ 从 127.0.0.1 打开时会提醒别把这个地址填到电视上",
          "127.0.0.1" in js and "局域网" in js, "")
    page = req("GET", "/")[1].decode("utf-8")
    check("页面上有「电视端填什么」卡片",
          'id="tvUrl"' in page and 'id="tvCopyBtn"' in page and 'id="tvHint"' in page, "")

    group("=== 10. 电视状态回传 ===")

    # 先发一版清单，拿它的版本号当「线上现在是哪一版」，后面拿来对账
    objs = jbody(req("GET", "/api/state", cookie=ck)[1]).get("objects") or []
    first = objs[0]["file"] if objs else ""
    st, data, _ = req("POST", "/api/publish",
                      body={"items": [{"file": first}] if first else [],
                            "imageDurationSec": 8, "muted": True, "allowEmpty": not first},
                      cookie=ck)
    live_rev = (jbody(req("GET", "/api/state", cookie=ck)[1]).get("playlist") or {}).get("revision")
    check("发布成功并拿到线上版本号", st == 200 and bool(live_rev), f"{st} {data[:200]}")

    def report(body, port=None, extra=None):
        return req("POST", "/api/report", body=body, port=port, extra=extra)

    def find_dev(key, port=None):
        d = jbody(req("GET", "/api/devices", cookie=ck, port=port)[1])
        for r in d.get("devices") or []:
            if r.get("key") == key:
                return r
        return {}

    # 屏上报自己的状态，**免登录**：十块屏没地方填访问码，换码时屏全挂在店里
    st, data, _ = report({
        "device": "前台", "version": "门店屏 v0.3.8 (11)", "mode": "remote",
        "playlistUrl": "http://127.0.0.1:%d/playlist.json" % PORT,
        "revision": live_rev, "playlistTotal": 3, "playableNow": 3,
        "playingIndex": 1, "playingFile": "01_海报.jpg", "online": True,
        "manualCount": 2, "localCount": 0, "remoteCount": 3,
        "reportIntervalSec": 300, "screen": "1920x1080",
        "storageLabel": "内置存储", "storageWritable": True,
    })
    check("★ 电视端免登录就能上报状态", st == 200 and jbody(data).get("ok") is True,
          f"{st} {data[:200]}")

    # 台账是管理数据，必须登录才能看
    st, data, _ = req("GET", "/api/devices")
    check("未登录看设备台账被挡住", st == 401, str(st))

    st, data, _ = req("GET", "/api/devices", cookie=ck)
    d = jbody(data)
    check("登录后能拿到设备台账", st == 200 and d.get("total") == 1 and d.get("onlineCount") == 1,
          f"{st} {str(d)[:200]}")

    dev = find_dev("前台")
    check("台账里有版本、模式和播放进度",
          str(dev.get("version", "")).startswith("门店屏")
          and dev.get("playingFile") == "01_海报.jpg"
          and dev.get("manualCount") == 2,
          str(dev)[:220])
    check("★ 在线的屏被算成在线", dev.get("online") is True, str(dev.get("online")))
    check("★ 台账里带上线上当前那一版，用来对账（谁的 revision 不一样就是还没更新）",
          d.get("currentRevision") == live_rev, str(d.get("currentRevision")))
    check("这台屏的 revision 跟线上一致", dev.get("revision") == live_rev, str(dev.get("revision")))

    # 还没更新上去的屏：服务端如实存下它报的旧版本，前端据此标记
    report({"device": "西墙", "revision": "r-上个星期", "reportIntervalSec": 300})
    check("★ 报旧版本的屏不会被伪装成最新版",
          find_dev("西墙").get("revision") == "r-上个星期", str(find_dev("西墙"))[:200])

    # 免登录的写接口，一律当成「有人在乱灌」来防
    report({"device": "灌水机", "__evil__": "x" * 5000, "message": "m" * 2000,
            "playableNow": 10 ** 9, "playingIndex": True})
    flood = find_dev("灌水机")
    check("★ 不认识的字段一律丢掉（免登录的写接口不能让人灌任意内容）",
          flood and "__evil__" not in flood, str(sorted(flood.keys()))[:220])
    check("超长的文本被截断", flood and len(flood.get("message", "")) == 500,
          str(len(flood.get("message", ""))))
    check("离谱的数字被夹到范围内", flood and flood.get("playableNow") == 100000,
          str(flood.get("playableNow")))
    check("★ 布尔值不会被当成数字收进去（Python 里 True 就是 1）",
          "playingIndex" not in flood, str(flood.get("playingIndex")))

    # 认人：人起的名字 > 机器报的标识 > 来源 IP
    report({"id": "abcdef123456", "reportIntervalSec": 120})
    check("没填名字时用机器标识认人", bool(find_dev("abcdef123456")), "")
    report({"reportIntervalSec": 120})
    all_keys = [r["key"] for r in
                (jbody(req("GET", "/api/devices", cookie=ck)[1]).get("devices") or [])]
    check("啥都没有时用来源 IP 兜底（至少能看见有这么一台）",
          any(k.startswith("ip:") for k in all_keys), str(all_keys)[:220])

    # 老电视的系统时钟经常是错的。判断在线只能看服务器收到的时刻
    report({"device": "时钟错的屏", "reportedAtMs": 946684800000})   # 2000 年
    wrong = find_dev("时钟错的屏")
    check("★ 屏的时钟错了也不影响在线判定（用的是服务器收到的时刻）",
          wrong and abs(wrong.get("lastSeen", 0) - time.time()) < 60,
          str(wrong.get("lastSeen")))

    # 上报体重不是 JSON：得回一句能看懂的话，不能让端侧只看到一个没有原因的 500
    st, data, _ = req("POST", "/api/report", raw=b"not json at all")
    check("上报体重不是 JSON 时，回可读的错误",
          st == 400 and "JSON" in jbody(data).get("error", ""), f"{st} {data[:200]}")

    st, data, _ = req("GET", "/api/report")
    check("GET 打上报接口会说「没有这个接口」", st == 404, f"{st} {data[:160]}")

    # 台账得落盘。云上重启一次就变成「一台设备都没有」的话，会让人白白跑一趟店里
    saved = admin.ReportStore(DATA / "reports.json")
    check("★ 台账落盘了，服务重启后还认得这些屏",
          "前台" in saved.snapshot() and len(saved.snapshot()) >= 4,
          str(sorted(saved.snapshot().keys()))[:200])

    # 数量上限：有人拿脚本往里灌也不能把文件撑爆
    cap = admin.ReportStore(DATA / "cap.json")
    base = time.time()
    for i in range(admin.REPORT_MAX_DEVICES + 7):
        cap.update({"device": "屏%03d" % i}, "10.0.0.1", base + i)
    check("★ 台账有台数上限，灌再多也只留最近的心跳",
          len(cap.snapshot()) == admin.REPORT_MAX_DEVICES, str(len(cap.snapshot())))

    # 离线判定：心跳断了这么久就算离线。阈值跟着每台屏自己的上报间隔走
    off = admin.ReportStore(DATA / "off.json")
    now = time.time()
    off.update({"device": "老屏", "reportIntervalSec": 300}, "10.0.0.9", now - 4000)
    off.update({"device": "刚报过", "reportIntervalSec": 300}, "10.0.0.9", now - 10)
    rows = off.rows(now)
    by = {r["key"]: r for r in rows}
    check("★ 心跳断太久算离线", by["老屏"]["online"] is False, str(by["老屏"]["silentSec"]))
    check("刚报过的是在线", by["刚报过"]["online"] is True, str(by["刚报过"]["silentSec"]))
    check("在线的排在离线前面", rows[0]["key"] == "刚报过", str([r["key"] for r in rows]))
    off2 = admin.ReportStore(DATA / "off2.json")
    off2.update({"device": "勤快的", "reportIntervalSec": 60}, "10.0.0.9", now - 400)
    check("★ 上报间隔再短也留 15 分钟宽限，抖一次不会被标成离线",
          off2.rows(now)[0]["online"] is True,
          str(off2.rows(now)[0]["offlineAfterSec"]))

    # 上报口令是可选的加强项（要把端口放到公网时才用）。
    # 口令故意用纯 ASCII：HTTP 头本身只能放 latin-1，中文口令在头里传不过去 ——
    # 端侧也不会发中文头，这里不制造那种用不了的能力
    TOK = "REPORT-TOKEN-9"
    srv6 = admin.Server(("127.0.0.1", 0), admin.Handler, make_backend(f"local:{fresh}"),
                        "X-CODE", "playlist.json", "",
                        report_store=admin.ReportStore(DATA / "r6.json"),
                        report_token=TOK)
    port6 = srv6.server_address[1]
    threading.Thread(target=srv6.serve_forever, daemon=True).start()
    st, data, _ = req("POST", "/api/report", body={"device": "没带口令"}, port=port6)
    check("★ 设了口令之后不带口令报不上来", st == 403, f"{st} {data[:200]}")
    st2, data2, _ = req("POST", "/api/report", body={"device": "口令错"},
                        port=port6, extra={"X-Report-Token": "WRONG-TOKEN"})
    check("口令错了也报不上来", st2 == 403, f"{st2} {data2[:200]}")
    check("★ 上报失败用 403 而不是 401（401 在咱们这套里表示「该登录了」，别混成一个信号）",
          st == 403 and st2 == 403, f"{st} {st2}")
    st3, data3, _ = req("POST", "/api/report", body={"device": "口令对"},
                        port=port6, extra={"X-Report-Token": TOK})
    check("口令对了就收下", st3 == 200 and jbody(data3).get("ok") is True,
          f"{st3} {data3[:200]}")
    srv6.shutdown()
    srv6.server_close()

    # 前端
    js2 = req("GET", "/app.js")[1].decode("utf-8")
    check("前端有设备卡片，并且会单独定时刷新它",
          "renderDevices" in js2 and "loadDevices" in js2 and "startDevPoll" in js2, "")
    check("★ 设备卡片是按 revision 跟线上对账的（谁的版本不一样就标出来）",
          "currentRevision" in js2 and "dev-stale" in js2, "")
    page2 = req("GET", "/")[1].decode("utf-8")
    check("页面上有「电视状态」卡片",
          'id="devList"' in page2 and 'id="devSum"' in page2, "")

    srv2.shutdown()
    srv2.server_close()


if __name__ == "__main__":
    sys.exit(main())
