#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把网页后台打成不依赖 Python 的独立 exe，并顺手冒烟测一遍。

    python server/build-exe.py            # 完整流程：装依赖 -> 打包 -> 冒烟
    python server/build-exe.py --no-smoke # 只打包
    python server/build-exe.py --keep-work# 出问题时不删中间产物，方便看警告日志

为什么要这个脚本而不是直接敲 pyinstaller：

1. 单文件 exe 有一个特别容易踩的坑 —— 每次启动都解包到一个新的临时目录，
   而服务端要存访问码（data/code.json）。存错了地方的表现是「访问码每次重启
   都变一个」，页面记不住登录，但日志看起来一切正常。打完必须真跑一遍验证。
2. `web/` 目录漏打进去的表现是「exe 起来了、日志正常、打开网页 404」，
   同样不是编译期能发现的。所以冒烟里就查这一条。
3. C 盘紧张：中间产物默认丢到 D 盘，只把最终 exe 拷回 server/dist/。
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SPEC = HERE / "signage-admin.spec"
EXE_NAME = "signage-admin"
OUT_DIR = HERE / "dist"                 # 最终 exe 落这儿（要拷走的就是它）
MIRROR = "https://pypi.tuna.tsinghua.edu.cn/simple"

# 版本号只有一个来源：signage_admin.py 顶部那个常量。硬编码一份必然对不上
_m = re.search(r'^ADMIN_VERSION\s*=\s*"([^"]+)"',
               (HERE / "signage_admin.py").read_text(encoding="utf-8"), re.M)
ADMIN_VERSION = _m.group(1) if _m else ""

# 中间产物默认放 D 盘：C 盘只剩几个 G，PyInstaller 的 work 目录能吃掉几百兆
if Path("D:/").exists():
    DEFAULT_WORK = Path("D:/signage-build/work")
    DEFAULT_DIST = Path("D:/signage-build/dist")
else:
    DEFAULT_WORK = HERE / "build"
    DEFAULT_DIST = HERE / "dist-tmp"


def say(msg=""):
    print(msg, flush=True)


def make_runnable(p: Path):
    """
    给产物补上可执行位。

    看着是废话（Windows 哪有什么执行位），但 PyInstaller 新生成的 exe 权限是 644，
    在 Git Bash / MSYS 环境下会被拦成「Permission denied」——现象特别像杀软或沙箱
    把 exe 拦了，实际只是少个 x 位，白查半天。
    """
    try:
        os.chmod(p, 0o755)
    except OSError:
        pass


def step(n, total, msg):
    say(f"[{n}/{total}] {msg}")


def run(cmd, **kw):
    say("      $ " + " ".join(str(c) for c in cmd))
    return subprocess.run([str(c) for c in cmd], **kw)


# ------------------------------------------------------------------ 打包

def ensure_pyinstaller():
    """
    没装就装一个（装进当前解释器，不碰系统 Python）。

    源的选择有讲究：这台机器上 pip 走沙箱代理能到 pypi.org，**国内镜像反而连不上**。
    所以默认源优先、镜像当备选。反过来先试镜像的话，报错是
    「No matching distribution found for pyinstaller」—— 看着像「这个包不存在」，
    实际是源不通，很容易往错的方向查。
    """
    try:
        import PyInstaller  # noqa: F401
        return True
    except ImportError:
        pass
    say("      没装 PyInstaller，装一下…")
    for extra in ([], ["-i", MIRROR]):
        r = run([sys.executable, "-m", "pip", "install", "-U", "pyinstaller"] + extra)
        if r.returncode == 0:
            return True
        say("      这个源不行，换一个再试…")
    raise SystemExit(
        "！PyInstaller 装不上。手动装一个：\n"
        "      python -m pip install -U pyinstaller\n"
        "    国内网络可以加 -i " + MIRROR
    )


def check_boto3():
    """boto3 只影响「对象存储直传」那条路，本地目录模式用不上。
    但打包的是一次性的东西，缺了它这个 exe 就永远连不上 COS，所以要说清楚"""
    try:
        import boto3  # noqa: F401
        return True
    except ImportError:
        say("      ！没装 boto3：打出来的 exe 只能用本地目录模式（local:…），")
        say("        想连 COS 就先 pip install boto3 再重新打包。")
        return False


def build(work: Path, dist: Path, clean: bool, keep_work: bool):
    if not SPEC.is_file():
        raise SystemExit(f"！找不到 spec：{SPEC}")
    if clean:
        for d in (work, dist):
            if d.exists():
                say(f"      清掉 {d}")
                shutil.rmtree(d, ignore_errors=True)
    for d in (work, dist):
        d.mkdir(parents=True, exist_ok=True)

    r = run([sys.executable, "-m", "PyInstaller", str(SPEC),
             "--noconfirm", "--clean",
             "--distpath", str(dist), "--workpath", str(work)])
    if r.returncode != 0:
        raise SystemExit("！PyInstaller 失败，看上面的报错（或加 --keep-work 留着中间产物）")

    exe = dist / (EXE_NAME + (".exe" if os.name == "nt" else ""))
    if not exe.is_file():
        raise SystemExit(f"！没找到产物：{exe}")
    if not keep_work:
        shutil.rmtree(work, ignore_errors=True)
    return exe


# ------------------------------------------------------------------ 冒烟

def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Runner:
    """起一个 exe，实时收着它的输出（`/status` 那种排错信息全靠这里）"""

    def __init__(self, exe: Path, args, cwd: Path):
        env = os.environ.copy()
        # 清掉所有 SIGNAGE_* —— 冒烟要测的就是「什么都不设」时的默认行为
        for k in [k for k in env if k.startswith("SIGNAGE_")]:
            env.pop(k, None)
        env["PYTHONIOENCODING"] = "utf-8"   # 管道里不给这个，中文出来是乱码
        env["PYTHONUTF8"] = "1"
        self.proc = subprocess.Popen(
            [str(exe)] + [str(a) for a in args], cwd=str(cwd), env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1,
        )
        self.lines = []
        self.t = threading.Thread(target=self._pump, daemon=True)
        self.t.start()

    def _pump(self):
        try:
            for line in self.proc.stdout:
                self.lines.append(line.rstrip("\n"))
        except Exception:
            pass

    def out(self) -> str:
        return "\n".join(self.lines)

    def wait_http(self, port: int, timeout=120) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                return False
            try:
                with urllib.request.urlopen(
                        f"http://127.0.0.1:{port}/api/health", timeout=2) as r:
                    if r.status == 200:
                        return True
            except Exception:
                time.sleep(0.3)
        return False

    def stop(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=8)


class Client:
    def __init__(self, port: int):
        self.base = f"http://127.0.0.1:{port}"
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))

    def req(self, path, method="GET", body=None, headers=None, timeout=15):
        data = None
        hdrs = dict(headers or {})
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        r = urllib.request.Request(self.base + path, data=data, method=method, headers=hdrs)
        try:
            with self.opener.open(r, timeout=timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()


class Checker:
    def __init__(self):
        self.fails = []
        self.n = 0

    def ok(self, name, cond, extra=""):
        self.n += 1
        if cond:
            say(f"      ok   {name}")
        else:
            say(f"      FAIL {name}{('  —— ' + str(extra)) if extra else ''}")
            self.fails.append(name)

    def done(self):
        say("")
        if self.fails:
            say(f"冒烟：{self.n} 项里失败 {len(self.fails)} 项：")
            for f in self.fails:
                say(f"  - {f}")
            return False
        say(f"冒烟：{self.n} 项全过")
        return True


def smoke(exe: Path) -> bool:
    c = Checker()
    tmp = Path(tempfile.mkdtemp(prefix="signage-smoke-"))
    exe2 = tmp / exe.name
    # 优先硬链接而不是复制：同一个卷上是零字节的，万一临时目录删不掉（沙箱/杀软拦删除）
    # 留下的也只是个空壳，不会攒出几个 27MB 的副本。跨盘时退回复制
    try:
        os.link(exe, exe2)
    except OSError:
        shutil.copy2(exe, exe2)
    make_runnable(exe2)
    online = tmp / "online"
    port = free_port()
    run1 = run2 = run3 = run4 = None
    try:
        # --version 先来一发：连解释器都起不来的话，后面全都白测
        r = subprocess.run([str(exe2), "--version"], capture_output=True,
                           text=True, encoding="utf-8", errors="replace", timeout=120)
        c.ok(f"--version 能跑通（{ADMIN_VERSION}）",
             r.returncode == 0 and ADMIN_VERSION in (r.stdout or ""),
             (r.stdout or "") + (r.stderr or ""))

        args = ["--port", str(port), "--storage", f"local:{online}"]
        run1 = Runner(exe2, args, tmp)
        if not run1.wait_http(port):
            say("")
            say("      —— exe 没起来，它的输出：")
            say(run1.out())
            c.ok("服务能起来", False)
            return c.done()

        cl = Client(port)

        st, body = cl.req("/")
        c.ok("网页能打开（web/ 打进包里了）", st == 200 and b"app.js" in body,
             f"status={st}")

        st, body = cl.req("/app.js")
        c.ok("前端 js 能取到", st == 200 and len(body) > 1000, f"status={st}")

        st, body = cl.req("/api/health")
        c.ok("健康检查", st == 200 and b'"ok"' in body, f"status={st}")

        st, _ = cl.req("/api/state")
        c.ok("没登录时状态接口要挡住", st == 401, f"status={st}")

        # 访问码：从 exe 旁边的 data/code.json 取（默认数据目录就应该是那儿）
        code_file = tmp / "data" / "code.json"
        c.ok("数据目录落在 exe 旁边（不是临时解包目录）", code_file.is_file(), str(code_file))
        if not code_file.is_file():
            say(run1.out())
            return c.done()
        code = json.loads(code_file.read_text(encoding="utf-8")).get("code") or ""
        c.ok("访问码是个非空串", len(code) >= 4, repr(code))

        st, body = cl.req("/api/login", method="POST", body={"code": code + "-错的"})
        c.ok("错的访问码登不进去", st == 401, f"status={st}")

        st, body = cl.req("/api/login", method="POST", body={"code": code})
        c.ok("对的访问码能登录", st == 200, f"status={st} body={body[:200]!r}")

        st, body = cl.req("/api/devices")
        c.ok("登录后能拿设备台账", st == 200, f"status={st}")

        st, body = cl.req("/api/publish", method="POST",
                          body={"items": [], "allowEmpty": True})
        c.ok("能发布（空清单下架）", st == 200, f"status={st} body={body[:200]!r}")

        # 电视端出口：另开一个不带 cookie 的客户端，模拟屏
        tv = Client(port)
        st, body = tv.req("/playlist.json")
        c.ok("电视端能免登录拉清单", st == 200, f"status={st} body={body[:200]!r}")

        # 字段名是 device（人起的名字），不是 name —— 服务端只认白名单里的字段，
        # 写错了不会报错，只会安静地退回用来源 IP 当标识
        st, body = tv.req("/api/report", method="POST",
                          body={"device": "smoke-screen", "version": "0.3.8", "revision": 1})
        c.ok("屏能免登录上报状态", st == 200, f"status={st} body={body[:200]!r}")

        st, body = cl.req("/api/devices")
        c.ok("上报的屏出现在台账里", st == 200 and "smoke-screen" in body.decode("utf-8", "replace"),
             f"status={st} body={body[:300]!r}")

        run1.stop()
        run1 = None

        # 重启一遍：访问码和台账还在，才说明数据真的落在文件里了
        port2 = free_port()
        run2 = Runner(exe2, ["--port", str(port2), "--storage", f"local:{online}"], tmp)
        if run2.wait_http(port2):
            cl2 = Client(port2)
            st, _ = cl2.req("/api/login", method="POST", body={"code": code})
            c.ok("重启后同一个访问码还能登录", st == 200, f"status={st}")
            st, body = cl2.req("/api/devices")
            c.ok("重启后台账还在", st == 200 and "smoke-screen" in body.decode("utf-8", "replace"),
                 f"status={st}")
        else:
            say(run2.out())
            c.ok("重启能起来", False)
        run2.stop()
        run2 = None

        # ---- 什么都不给时的兜底：存储默认落 exe 旁边的 site/
        port3 = free_port()
        run3 = Runner(exe2, ["--port", str(port3)], tmp)
        if run3.wait_http(port3):
            cl3 = Client(port3)
            cl3.req("/api/login", method="POST", body={"code": code})
            st, _ = cl3.req("/api/publish", method="POST",
                            body={"items": [], "allowEmpty": True})
            c.ok("没给存储目标时默认发到 exe 旁边的 site/",
                 (tmp / "site" / "playlist.json").is_file(), f"status={st}")
        else:
            say(run3.out())
            c.ok("没给存储目标时也能起来", False)
        run3.stop()
        run3 = None

        # ---- 真·双击：一个参数都没有，全靠 exe 旁边那份配置，
        #      而且故意换一个工作目录（双击时工作目录是哪儿说不准）
        elsewhere = tmp / "elsewhere"
        elsewhere.mkdir(exist_ok=True)
        cfg_port = free_port()
        (tmp / "signage-server.json").write_text(json.dumps({
            "port": cfg_port,
            "storage": f"local:{tmp / 'online-cfg'}",
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        run4 = Runner(exe2, [], elsewhere)
        if run4.wait_http(cfg_port):
            cl4 = Client(cfg_port)
            st, _ = cl4.req("/")
            c.ok("双击（只有配置文件、没有命令行）能起来", st == 200, f"status={st}")
            cl4.req("/api/login", method="POST", body={"code": code})
            st, _ = cl4.req("/api/publish", method="POST",
                            body={"items": [], "allowEmpty": True})
            c.ok("存储目录也是从配置文件读的",
                 (tmp / "online-cfg" / "playlist.json").is_file(), f"status={st}")
        else:
            say(run4.out())
            c.ok("双击（只有配置文件）能起来", False)
        return c.done()
    finally:
        for r in (run1, run2, run3, run4):
            if r is not None:
                r.stop()
        # 先把 exe 那一条删掉再删目录：沙箱/杀软拦「整个目录的删除」是常事，
        # 剩个空壳无所谓，剩几个大文件就是几百兆垃圾
        try:
            exe2.unlink()
        except OSError:
            pass
        shutil.rmtree(tmp, ignore_errors=True)
        if tmp.exists():
            say(f"      ！临时目录没删干净（多半是删除被拦了，手动删掉即可）：{tmp}")


# ------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser(description="打包门店屏网页后台为独立 exe")
    ap.add_argument("--no-smoke", action="store_true", help="打完不跑冒烟测试")
    ap.add_argument("--smoke-only", action="store_true",
                    help="不重新打包，直接对 server/dist 里已有的 exe 跑冒烟（想快速复验一遍时用）")
    ap.add_argument("--keep-work", action="store_true", help="留着 PyInstaller 中间产物")
    ap.add_argument("--no-clean", action="store_true", help="不先删旧的 build/dist")
    ap.add_argument("--work", default=str(DEFAULT_WORK), help=f"中间产物目录（默认 {DEFAULT_WORK}）")
    ap.add_argument("--dist", default=str(DEFAULT_DIST), help=f"临时产物目录（默认 {DEFAULT_DIST}）")
    args = ap.parse_args()

    if args.smoke_only:
        final = OUT_DIR / (EXE_NAME + (".exe" if os.name == "nt" else ""))
        if not final.is_file():
            raise SystemExit(f"！{final} 不存在，先正常打一次包")
        make_runnable(final)
        say(f"只跑冒烟：{final}")
        say("")
        raise SystemExit(0 if smoke(final) else 1)

    steps = 3 if args.no_smoke else 4

    step(1, steps, f"检查环境（Python {sys.version.split()[0]}，{sys.executable}）")
    if sys.version_info < (3, 8):
        raise SystemExit("！PyInstaller 6 要 Python 3.8 以上")
    ensure_pyinstaller()
    has_boto3 = check_boto3()

    step(2, steps, "打包")
    exe = build(Path(args.work), Path(args.dist), clean=not args.no_clean,
                keep_work=args.keep_work)

    step(3, steps, "拷到 server/dist/")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    final = OUT_DIR / exe.name
    shutil.copy2(exe, final)
    make_runnable(final)

    size_mb = final.stat().st_size / 2 ** 20
    say(f"      {final}")
    say(f"      体积 {size_mb:.1f} MB（单文件，双击就能跑）")
    # 把配置示例也摆一份在 exe 旁边：不然用户拿着一堆参数名不知道往哪写
    ex_cfg = HERE / "signage-server.example.json"
    if ex_cfg.is_file():
        shutil.copy2(ex_cfg, OUT_DIR / ex_cfg.name)
        say(f"      配置示例 {OUT_DIR / ex_cfg.name}（改成 signage-server.json 就生效）")
    if not has_boto3:
        say("      ！这个 exe 只能本地目录模式（没打进 boto3）")

    good = True
    if not args.no_smoke:
        step(4, steps, "冒烟测试：把 exe 拷到临时目录里真跑一遍")
        good = smoke(final)

    say("")
    say(f"产物：{final}")
    say("换一台电脑要拷的：signage-admin.exe，以及一个可选的 signage-server.json")
    say("数据落在 exe 旁边的 data/ 和 site/ 里，搬家的时候一起拷。")
    if not good:
        raise SystemExit("！冒烟没过，别把这个 exe 拿去用")


if __name__ == "__main__":
    main()
