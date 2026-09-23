#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
门店屏网页后台 · 容器化自检

改完 docker-compose.yml / Dockerfile 之后跑一遍（**不需要装 Docker**）：

    python check-deploy.py
    python check-deploy.py --python D:/clean/Scripts/python.exe     # 指定解释器

它干三件事：

  1. 校验 compose / Dockerfile 的语法和关键字段（有 pyyaml 就查得细一些）。
     两个 Dockerfile（Dockerfile / Dockerfile.min）和两份 compose 都过一遍，
     并断言两个 Dockerfile 从仓库拷的文件完全一致 —— CI 打的是 .min 那份，
     人平时改的是另一份，最容易在这儿漂移
  2. **照着 Dockerfile 里 COPY 的那几行**，在临时目录里搭一份群晖上的目录布局，
     多一个文件都不拷
  3. 用这份布局起一次真服务，走一遍本地目录模式的主要路径

第 2、3 步是重点，而且第 2 步是**从 Dockerfile 里解析出来的**，不是手写的清单 ——
所以它验的就是 Dockerfile 本身。漏拷 web/ 的表现是页面 404，漏拷 tools/ 的表现
是启动就报 import 错，这两种都得在 NAS 上折腾半天才看得出来，在这儿先卡住。

想连「官方 python 镜像里没有 boto3，本地目录模式照样能跑」一起验，
就用一个干净的、没装过第三方包的解释器来跑本脚本：

    python -m venv --without-pip D:/clean
    D:/clean/Scripts/python.exe check-deploy.py
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
COMPOSE = HERE / "docker-compose.yml"
COMPOSE_MIN = HERE / "docker-compose.min.yml"
DOCKERFILE = HERE / "Dockerfile"          # 群晖上现场构建那份（slim + boto3）
DOCKERFILE_MIN = HERE / "Dockerfile.min"  # 最小镜像那份（alpine + boto3），CI 打它
ADMIN_PY = HERE / "signage_admin.py"

# CI 推镜像用的地址：ghcr.io/<用户名>/<仓库名>。仓库改名的话，这里和
# docker-compose.min.yml 的 image: 两处一起改（build.yml 里是用
# ${GITHUB_REPOSITORY} 算出来的，不用动）
GHCR_REPO = "ghcr.io/binhe-cpu/signage-tv"

# CI 的 workflow。镜像是在那儿真 build、真起容器的（本机常常没 Docker），
# 所以「CI 那段脚本写得对不对」也得有人查 —— 放在这儿一起查。
CI_WORKFLOW = ROOT / ".github" / "workflows" / "build.yml"


def say(msg=""):
    print(msg, flush=True)


class Checker:
    def __init__(self):
        self.fails = []
        self.n = 0

    def ok(self, name, cond, extra=""):
        self.n += 1
        if cond:
            say(f"  ok   {name}")
        else:
            say(f"  FAIL {name}" + (f"  —— {extra}" if extra else ""))
            self.fails.append(name)

    def done(self):
        say("")
        if self.fails:
            say(f"{self.n} 项里失败 {len(self.fails)} 项：")
            for f in self.fails:
                say(f"  - {f}")
            return False
        say(f"{self.n} 项全过")
        return True


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


# ---------------------------------------------------------------- 一、静态校验

def parse_copies(text: str):
    """从 Dockerfile 里抠出 COPY 指令，还原成 [(源, 目标目录)]

    Docker 的语义：目标以 / 结尾就是目录，源文件拷进去之后保持自己的名字；
    源是目录就把它**里面的东西**拷进目标。这里按这个规则还原成实际落点。
    """
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line.upper().startswith("COPY "):
            continue
        parts = line.split()[1:]
        if len(parts) < 2:
            continue
        # COPY --from=xxx 是从**别的构建阶段**拿东西，跟「仓库里哪些文件会进镜像」
        # 无关（Dockerfile.min 的 boto3 就是拷进来的）。不排除它，一致性断言会假红。
        if any(p.startswith("--from=") for p in parts):
            continue
        srcs, dst = parts[:-1], parts[-1]
        for s in srcs:
            out.append((s, dst))
    return out


def read_admin_version() -> str:
    """服务端自称的版本号。用来钉住最小镜像的 tag —— 升级时改了代码忘了改 compose，
    表现是容器起不来（找不到镜像），这种错在 NAS 上很难看出来。"""
    try:
        text = ADMIN_PY.read_text(encoding="utf-8")
    except OSError:
        return ""
    m = re.search(r'^ADMIN_VERSION\s*=\s*"([^"]+)"', text, re.M)
    return m.group(1) if m else ""


def check_one_dockerfile(c, path: Path, base: str, label: str) -> list:
    """一个 Dockerfile 的通用断言，返回它从仓库拷了哪些文件"""
    if not path.exists():
        c.ok(f"{label} 存在", False, str(path))
        return []
    df = path.read_text(encoding="utf-8")
    c.ok(f"{label} 基于 {base}", f"FROM {base}" in df)
    c.ok(f"{label} 会装 boto3（对象存储模式要靠它）", "boto3" in df)
    c.ok(f"{label} 设了 PYTHONDONTWRITEBYTECODE",
         "PYTHONDONTWRITEBYTECODE" in df)
    c.ok(f"{label} 声明了 /data 和 /site 两个卷",
         'VOLUME ["/data", "/site"]' in df)
    c.ok(f"{label} 暴露 8600", "EXPOSE 8600" in df)
    # 这条 label 是把镜像包关联到仓库用的。少了它，第一次推 ghcr 可能被拒
    # （GITHUB_TOKEN 对「同命名空间下没关联仓库的包」没有写权限），
    # 而且包页面上看不到源码地址。它在 Dockerfile 里不显眼，容易被顺手删掉。
    c.ok(f"{label} 有 org.opencontainers.image.source（推 ghcr 靠它关联仓库）",
         "org.opencontainers.image.source" in df)
    c.ok(f"{label} 的启动命令指向 /app/server/signage_admin.py",
         "/app/server/signage_admin.py" in df)

    # FROM 上写 --platform=linux/amd64 会把镜像钉死在一个架构上：ARM 的群晖
    # （DS223j、DS124 这些）要么装不上，要么只能让 qemu 顶着模拟跑 —— 慢，而且
    # 出问题时报错五花八门。多架构的正确写法是**什么都不写**，让 buildx 按
    # 目标平台去构建。这条很容易被「本地跑得起来就行」的改法顺手加上。
    froms = [l.strip() for l in df.splitlines()
             if l.strip().upper().startswith("FROM ")]
    c.ok(f"{label} 的 FROM 没有硬编码 --platform（钉死架构会坑 ARM 的 NAS）",
         bool(froms) and not any("--platform" in l for l in froms), str(froms))

    copies = parse_copies(df)
    c.ok(f"{label} 里能解析出 COPY 指令", bool(copies), str(copies))
    for src, _ in copies:
        c.ok(f"{label} COPY 的源存在：{src}", (ROOT / src.rstrip("/")).exists())
    return copies


def check_files(c) -> list:
    say("一、镜像与部署配置的语法、关键字段")

    copies = check_one_dockerfile(c, DOCKERFILE, "python:3.12-slim", "Dockerfile")
    copies_min = check_one_dockerfile(c, DOCKERFILE_MIN, "python:3.12-alpine",
                                     "Dockerfile.min")
    # 重点：两份从仓库拷的文件必须完全一致（连落点）。漏 web/ = 页面 404，
    # 漏 tools/ = 启动就 import 报错 —— 而 CI 打的是 .min 那份。
    c.ok("两个 Dockerfile 从仓库拷的文件完全一致（含落点）",
         bool(copies) and sorted(copies) == sorted(copies_min),
         f"Dockerfile={copies}  Dockerfile.min={copies_min}")

    # docker build 的上下文是「.」（就是仓库根），-f 只指定 Dockerfile 在哪。
    # 所以 .dockerignore 放 server/ 里不生效 —— 这个位置很容易搞错。
    c.ok("仓库根目录有 .dockerignore（放 server/ 里不生效，上下文根是仓库根）",
         (ROOT / ".dockerignore").exists())

    # 放在这儿而不是 check_compose_min 后面：上面几个分支有 early return，
    # 放末尾的话没装 pyyaml 时就整段跳过了
    check_ci_docker(c)

    text = COMPOSE.read_text(encoding="utf-8")
    try:
        import yaml
    except ImportError:
        say("  （没装 pyyaml，只做文本层面的检查；pip install pyyaml 能查得更细）")
        for want, why in (("./app:/app:ro", "代码"), ("./data:/data", "数据"),
                          ("./site:/site", "素材"), ("8600:8600", "端口")):
            c.ok(f"compose 里有 {why} 的配置", want in text)
        return copies

    try:
        data = yaml.safe_load(text)
    except Exception as e:
        c.ok("compose 能被 YAML 解析", False, str(e))
        return copies
    c.ok("compose 能被 YAML 解析", isinstance(data, dict))

    svc = data.get("services") or {}
    s = svc.get("signage-admin") or {}
    c.ok("有一个叫 signage-admin 的 service", bool(s), f"实际有 {list(svc)}")
    c.ok("没有过时的 version 字段（新版 compose 会告警）", "version" not in data)
    c.ok("image 指向官方 python 镜像",
         str(s.get("image", "")).startswith("python:"), str(s.get("image")))
    c.ok("restart 是 unless-stopped（NAS 重启后自己起来）",
         s.get("restart") == "unless-stopped", str(s.get("restart")))

    vols = s.get("volumes") or []
    for want, why in (("./app:/app:ro", "代码目录（只读）"),
                      ("./data:/data", "数据目录"),
                      ("./site:/site", "素材目录"),
                      ("/etc/localtime:/etc/localtime:ro", "时区")):
        c.ok(f"挂了{why}", want in vols, str(vols))

    env = s.get("environment") or {}
    if isinstance(env, list):
        env = dict(x.split("=", 1) for x in env if "=" in x)
    c.ok("SIGNAGE_DATA_DIR 指向 /data",
         env.get("SIGNAGE_DATA_DIR") == "/data", repr(env.get("SIGNAGE_DATA_DIR")))
    c.ok("SIGNAGE_STORAGE 是 local:/site",
         env.get("SIGNAGE_STORAGE") == "local:/site", repr(env.get("SIGNAGE_STORAGE")))
    adv = str(env.get("SIGNAGE_ADVERTISE_BASE") or "").strip()
    c.ok("设了 SIGNAGE_ADVERTISE_BASE（容器里不设，日志会打容器内网地址）", bool(adv))
    c.ok("SIGNAGE_ADVERTISE_BASE 是 http:// 开头的完整地址",
         adv.startswith("http://") and ":" in adv, adv)
    c.ok("SIGNAGE_ADVERTISE_BASE 不是占位默认值（127.0.0.1 电视连不上）",
         "127.0.0.1" not in adv and "localhost" not in adv, adv)
    c.ok("没有误设 SIGNAGE_REPORT_TOKEN（端侧没地方填它）",
         not str(env.get("SIGNAGE_REPORT_TOKEN") or "").strip())

    ports = s.get("ports") or []
    c.ok("端口映射到了 8600", any(str(p).endswith(":8600") for p in ports), str(ports))
    c.ok("有健康检查", bool(s.get("healthcheck")))
    cmd = s.get("command") or []
    c.ok("启动命令指向 /app/server/signage_admin.py",
         any("/app/server/signage_admin.py" in str(x) for x in cmd), str(cmd))

    check_compose_min(c)
    return copies


def check_compose_min(c) -> None:
    """最小镜像那份部署文件。它跟原版有个本质区别：**不挂代码**，代码在镜像里。

    挂了 ./app:/app 会用宿主目录盖住镜像里的代码（目录不存在还会起不来），
    所以「没有挂 /app」这条是它最容易写错的地方。
    """
    if not COMPOSE_MIN.exists():
        c.ok("最小镜像版的 docker-compose.min.yml 存在", False, str(COMPOSE_MIN))
        return
    text = COMPOSE_MIN.read_text(encoding="utf-8")
    ver = read_admin_version()

    # 镜像本身是多架构的，compose 里写 platform: 会把它钉死 —— ARM 的 NAS 上要么
    # 装不上，要么拉 amd64 那份顶着 qemu 跑。所以这一项**必须没有**。
    # 用带行首缩进的正则而不是 `"platform" in text`：注释里提到这个词是正常的
    # （上面就有一段「不要加 platform:」的说明）。
    c.ok("最小版 compose 没写 platform:（写了会把多架构镜像钉死在某个架构上）",
         not re.search(r"^\s+platform\s*:", text, re.M),
         "找到 platform: 那一行")
    c.ok("最小版 compose 拉的是 ghcr 上的镜像，tag 跟服务端版本一致",
         bool(ver) and f"{GHCR_REPO}:{ver}" in text,
         f"服务端版本 {ver!r} / 期望 {GHCR_REPO}:{ver}")
    # 老写法（本地打 tag 再导进去）已经换成 ghcr 了，留着说明有人只改了一半。
    # 只查 image: 那一行 —— 注释里的本地构建命令提到这个名字是正常的
    c.ok("最小版 compose 的 image: 里没有残留的本地镜像名 signage-admin:min-",
         "image: signage-admin:min-" not in text)

    try:
        import yaml
    except ImportError:
        # 没 pyyaml 就只做文本层面的检查，别在这假装通过
        c.ok("最小版 compose 没有挂 ./app（代码在镜像里，挂了会盖住它）",
             "./app:/app" not in text)
        c.ok("最小版 compose 挂了 data / site / 时区",
             all(x in text for x in ("./data:/data", "./site:/site",
                                     "/etc/localtime:/etc/localtime:ro")))
        return

    data = yaml.safe_load(text) or {}
    s = (data.get("services") or {}).get("signage-admin") or {}
    c.ok("最小版有一个叫 signage-admin 的 service", bool(s),
         f"实际有 {list(data.get('services') or {})}")
    c.ok("最小版没有过时的 version 字段", "version" not in data)
    img = str(s.get("image") or "")
    c.ok("最小版 image 指向 ghcr 上的镜像，tag 就是服务端版本号",
         bool(ver) and img == f"{GHCR_REPO}:{ver}", img or "(空)")

    vols = [str(v) for v in (s.get("volumes") or [])]
    c.ok("最小版**没有**挂 ./app（代码在镜像里，挂了会盖住它，目录不存在还起不来）",
         not any("/app" in v for v in vols), str(vols))
    for want, why in (("./data:/data", "数据目录"),
                      ("./site:/site", "素材目录"),
                      ("/etc/localtime:/etc/localtime:ro", "时区")):
        c.ok(f"最小版挂了{why}", want in vols, str(vols))

    env = s.get("environment") or {}
    if isinstance(env, list):
        env = dict(x.split("=", 1) for x in env if "=" in x)
    c.ok("最小版 SIGNAGE_DATA_DIR 指向 /data",
         env.get("SIGNAGE_DATA_DIR") == "/data", repr(env.get("SIGNAGE_DATA_DIR")))
    adv = str(env.get("SIGNAGE_ADVERTISE_BASE") or "").strip()
    c.ok("最小版设了 SIGNAGE_ADVERTISE_BASE（容器里不设，日志会打内网地址）",
         adv.startswith("http://"), adv)
    c.ok("最小版没有误设 SIGNAGE_REPORT_TOKEN（端侧没地方填它）",
         not str(env.get("SIGNAGE_REPORT_TOKEN") or "").strip())
    c.ok("最小版端口映射到了 8600",
         any(str(p).endswith(":8600") for p in (s.get("ports") or [])),
         str(s.get("ports")))
    c.ok("最小版有健康检查", bool(s.get("healthcheck")))


# ------------------------------------------------- 一之二、CI 里的镜像构建

def check_ci_docker(c) -> None:
    """CI 里那个打镜像的 job。

    本机常常没 Docker（这个脚本的整个设计前提），所以「真 build、真起容器」只有
    CI 干得了 —— 那段脚本写错了，得等 CI 红一次才知道。多架构这几条尤其阴：
    漏了 qemu 会在 arm64 那条直接挂（还算看得见），漏了 --provenance=false 则是
    构建、冒烟、推送全绿，只有 NAS 上拉不动，报错还指不到原因。
    """
    if not CI_WORKFLOW.exists():
        c.ok("CI 工作流存在（.github/workflows/build.yml）", False,
             str(CI_WORKFLOW))
        return
    t = CI_WORKFLOW.read_text(encoding="utf-8")

    c.ok("CI 装了 qemu（arm64 那条要在 amd64 的 runner 上模拟着跑）",
         "docker/setup-qemu-action" in t)
    c.ok("CI 建了 buildx（多架构 manifest 靠它，普通 docker build 出不来）",
         "docker/setup-buildx-action" in t)
    # qemu 必须排在 buildx 前面：builder 是先起容器再注册解释器的，
    # 反过来的话 builder 里没有 binfmt，arm64 那条会以
    # "exec format error" 挂掉，看着像 Dockerfile 写错了。
    i_qemu = t.find("docker/setup-qemu-action")
    i_bx = t.find("docker/setup-buildx-action")
    c.ok("qemu 那步排在 buildx 前面（顺序反了就是 exec format error）",
         0 <= i_qemu < i_bx, f"qemu 在 {i_qemu}，buildx 在 {i_bx}")

    # 下面这几条一律用「行首缩进的正则」而不是 `"xxx" in t`：
    # 同一个词在注释和 ::error 的报错文案里也会出现（--provenance=false 在注释
    # 和报错提示里各有一处、imagetools 在 Summary 那句话里还有一处），用 in 判断
    # 的话真指令被删掉了断言照样绿 —— 那就成了摆设。
    # （第一版就是这么写的，拿桩把文件打坏验证时才发现，见下面的注释。）
    c.ok("CI 一次构建两个架构（--platform linux/amd64,linux/arm64）",
         bool(re.search(r"^\s+--platform linux/amd64,linux/arm64\s*\\$", t, re.M)))
    # buildx 默认附带 attestation manifest，manifest list 里会多出两条
    # unknown/unknown。老版本 Docker（群晖上常见的那些）遇到不认识的平台直接报
    # "no matching manifest for linux/amd64" —— 包明明在那儿，就是拉不动。
    c.ok("CI 关了 attestation（--provenance=false，不关老 Docker 拉不动）",
         bool(re.search(r"^\s+--provenance=false\s*\\$", t, re.M)))
    c.ok("CI 用 buildx --push 直接推多架构（不是本地 tag 再 docker push）",
         bool(re.search(r"^\s+--push\s", t, re.M)))
    c.ok("CI 里真有一行在验 manifest（docker buildx imagetools inspect）",
         bool(re.search(r"^\s+docker buildx imagetools inspect", t, re.M)))
    # 两个架构各出一份 tar.gz。不能一次 save 两个：docker save 只认本地镜像，
    # 本地同一个 tag 只能存一个架构（后拉的把先拉的顶掉）。
    # 钉住「循环 + 文件名拼架构」这两行。别退化成在整份 workflow 里找字面
    # "-arm64-"：Release 说明里就有这几个字，那种断言删掉真循环也不会红。
    c.ok("CI 导出两个架构的 tar.gz（循环遍历 amd64/arm64，文件名拼架构）",
         "for A in amd64 arm64" in t
         and "signage-admin-docker-$A-$V.tar.gz" in t)


# ---------------------------------------------------------------- 二、搭布局

def build_layout(tmp: Path, copies) -> None:
    """照 Dockerfile 的 COPY 清单把文件摆好，位置跟容器里一模一样"""
    for src, dst in copies:
        s = ROOT / src.rstrip("/")
        target = tmp / dst.lstrip("/")
        if s.is_dir():
            shutil.copytree(s, target, dirs_exist_ok=True)
        else:
            target.mkdir(parents=True, exist_ok=True)
            shutil.copy2(s, target / s.name)
    # 群晖上这两个是空目录，容器里对应挂载点
    (tmp / "data").mkdir(exist_ok=True)
    (tmp / "site").mkdir(exist_ok=True)


# ---------------------------------------------------------------- HTTP 客户端

class Client:
    def __init__(self, port: int, cookies: bool = True):
        self.base = f"http://127.0.0.1:{port}"
        if cookies:
            self.jar = http.cookiejar.CookieJar()
            self.opener = urllib.request.build_opener(
                urllib.request.HTTPCookieProcessor(self.jar))
        else:
            # 电视端的角色：不带任何登录凭证
            self.opener = urllib.request.build_opener()

    def _send(self, path, data, method, headers, timeout):
        r = urllib.request.Request(self.base + path, data=data, method=method,
                                   headers=dict(headers or {}))
        try:
            with self.opener.open(r, timeout=timeout) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def req(self, path, method="GET", body=None, headers=None, timeout=20):
        data, hdrs = None, dict(headers or {})
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            hdrs["Content-Type"] = "application/json"
        return self._send(path, data, method, hdrs, timeout)

    def req_raw(self, path, blob, method="POST", headers=None, timeout=30):
        return self._send(path, blob, method, headers, timeout)


class Runner:
    """起一个真服务子进程，顺便把它的日志收着 —— 起不来的时候全靠它说明问题"""

    def __init__(self, py, script: Path, env, port: int, cwd: Path):
        self.port = port
        self.lines = []
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
        self.p = subprocess.Popen(
            [str(py), str(script), "--host", "127.0.0.1", "--port", str(port)],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
            env=env, cwd=str(cwd), creationflags=flags,
        )
        self.t = threading.Thread(target=self._drain, daemon=True)
        self.t.start()

    def _drain(self):
        try:
            for line in self.p.stdout:
                self.lines.append(line.rstrip())
        except Exception:
            pass

    def wait(self, timeout=30.0) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            if self.p.poll() is not None:
                return False
            try:
                with socket.create_connection(("127.0.0.1", self.port), 0.4):
                    return True
            except OSError:
                time.sleep(0.15)
        return False

    def log(self) -> str:
        return "\n".join(self.lines)

    def stop(self):
        try:
            self.p.terminate()
            self.p.wait(timeout=8)
        except Exception:
            try:
                self.p.kill()
            except Exception:
                pass


# ---------------------------------------------------------------- 三、真跑一遍

def check_runtime(c, py: Path, copies) -> None:
    say("")
    say("三、只拿 Dockerfile 拷的那几个文件，起一次真服务")

    tmp = Path(tempfile.mkdtemp(prefix="signage-deploy-"))
    runs = []
    try:
        build_layout(tmp, copies)
        app = tmp / "app"

        env = os.environ.copy()
        env["SIGNAGE_DATA_DIR"] = str(tmp / "data")
        env["SIGNAGE_STORAGE"] = "local:" + str(tmp / "site")
        env["SIGNAGE_ADVERTISE_BASE"] = "http://10.9.9.9:7700"
        env["PYTHONUNBUFFERED"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        # 这几个不能让外面的环境漏进来，不然验的就不是默认行为了
        for k in ("SIGNAGE_ADMIN_CODE", "SIGNAGE_PUBLIC_READ", "SIGNAGE_REPORT_TOKEN",
                  "SIGNAGE_PLAYLIST", "SIGNAGE_HOST", "SIGNAGE_PORT"):
            env.pop(k, None)

        script = app / "server" / "signage_admin.py"
        port = free_port()
        # cwd 设成 app/ —— 对应容器里的 WORKDIR /app
        run = Runner(py, script, env, port, cwd=app)
        runs.append(run)

        up = run.wait(30)
        c.ok("仅凭这几个文件就能起来（文件没漏拷）", up,
             "\n" + run.log()[-1200:] if not up else "")
        if not up:
            return

        # 端口能连上不代表日志已经打完了：启动提示是在 bind 之后才 print 的，
        # 而 TCP 握手只要内核 backlog 就成。松一口气再读，免得偶发抓空
        time.sleep(0.8)
        text = run.log()
        c.ok("启动日志里用的是 SIGNAGE_ADVERTISE_BASE 给的地址",
             "http://10.9.9.9:7700" in text, text[-600:])
        c.ok("启动日志里没有漏出容器内网地址那类假地址",
             "172.17." not in text, text[-600:])
        c.ok("日志里打印了电视端该填的地址",
             f"http://10.9.9.9:7700/" in text, text[-600:])

        cl = Client(port)
        st, _ = cl.req("/api/health")
        c.ok("探活接口通", st == 200, f"status={st}")

        st, body = cl.req("/")
        html = body.decode("utf-8", "replace")
        c.ok("页面能打开（web/ 没漏拷）",
             st == 200 and "<html" in html.lower(), f"status={st} len={len(body)}")
        st, body = cl.req("/app.js")
        c.ok("静态资源能取到", st == 200 and len(body) > 200, f"status={st}")

        code_file = tmp / "data" / "code.json"
        c.ok("数据目录里落了访问码", code_file.is_file(), str(code_file))
        code = json.loads(code_file.read_text(encoding="utf-8"))["code"]

        st, _ = cl.req("/api/login", method="POST", body={"code": code})
        c.ok("用数据目录里的码能登录", st == 200, f"status={st}")

        st, body = cl.req("/api/state")
        c.ok("状态接口通（tools/signage_core.py 没漏拷）",
             st == 200 and b'"ok"' in body, f"status={st} {body[:160]!r}")

        blob = b"\xff\xd8\xff\xe0" + b"fake-jpeg" * 64
        st, body = cl.req_raw("/api/upload?name=t1.jpg", blob)
        c.ok("能上传素材（本地模式的代传通道）",
             st == 200, f"status={st} {body[:160]!r}")

        st, body = cl.req("/api/publish", method="POST",
                          body={"items": [{"file": "t1.jpg"}], "imageDurationSec": 8})
        c.ok("能发布清单", st == 200, f"status={st} {body[:200]!r}")

        # 电视端：不带任何登录凭证
        tv = Client(port, cookies=False)
        st, body = tv.req("/playlist.json")
        c.ok("电视端免登录就能拉到清单",
             st == 200 and b"t1.jpg" in body, f"status={st} {body[:200]!r}")
        st, body = tv.req("/t1.jpg")
        c.ok("电视端免登录就能拉到素材",
             st == 200 and len(body) == len(blob), f"status={st} len={len(body)}")

        st, _ = tv.req("/api/report", method="POST",
                       body={"device": "群晖冒烟", "version": "0.3.8", "revision": 1})
        c.ok("屏能免登录上报状态", st == 200, f"status={st}")

        st, body = cl.req("/api/devices")
        c.ok("页面上能看到这台屏",
             st == 200 and "群晖冒烟" in body.decode("utf-8", "replace"),
             f"status={st} {body[:200]!r}")

        # 重启一遍：数据得真的落在文件里，不是活在内存里
        run.stop()
        port2 = free_port()
        run2 = Runner(py, script, {**env, "SIGNAGE_ADVERTISE_BASE": "http://10.9.9.9:7701"},
                      port2, cwd=app)
        runs.append(run2)
        up2 = run2.wait(30)
        c.ok("重启能起来", up2, "\n" + run2.log()[-800:] if not up2 else "")
        if up2:
            cl2 = Client(port2)
            st, _ = cl2.req("/api/login", method="POST", body={"code": code})
            c.ok("重启后还是同一个访问码（没退化成每次换码）", st == 200, f"status={st}")
            st, body = cl2.req("/api/devices")
            c.ok("重启后台账还在",
                 st == 200 and "群晖冒烟" in body.decode("utf-8", "replace"),
                 f"status={st}")
    finally:
        for r in runs:
            r.stop()
        shutil.rmtree(tmp, ignore_errors=True)


# ---------------------------------------------------------------- 入口

def probe(py: Path) -> str:
    """看看这个解释器是什么来头 —— 有没有 boto3 决定了这次验的是不是零依赖环境"""
    code = ("import sys;print('Python ' + sys.version.split()[0]);"
            "\ntry:\n import boto3;print('boto3: 有')\nexcept Exception:print('boto3: 没有')")
    try:
        r = subprocess.run([str(py), "-c", code], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=30)
        return (r.stdout or "").strip().replace("\n", "，")
    except Exception as e:
        return f"（探不出来：{e}）"


def main():
    ap = argparse.ArgumentParser(description="门店屏网页后台 · 容器化自检")
    ap.add_argument("--python", default=sys.executable,
                    help="用哪个解释器起服务（默认当前这个）")
    args = ap.parse_args()

    py = Path(args.python)
    if not py.exists():
        raise SystemExit(f"！找不到这个解释器：{py}")

    say("门店屏网页后台 · 容器化自检")
    say(f"  解释器：{py}")
    about = probe(py)
    say(f"  自述：  {about}")
    if "boto3: 没有" not in about:
        say("  提示：这个解释器装了 boto3，验不出「零依赖」那件事。")
        say("        想连那个一起验，就用一个干净解释器跑：")
        say("        python -m venv --without-pip D:/clean")
        say("        D:/clean/Scripts/python.exe check-deploy.py")
    say("")

    c = Checker()
    copies = check_files(c)
    if not copies:
        say("")
        say("Dockerfile 里解析不出 COPY，后面的真机验证没法做。")
        raise SystemExit(not c.done())

    check_runtime(c, py, copies)
    say("")
    good = c.done()
    if not good:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
